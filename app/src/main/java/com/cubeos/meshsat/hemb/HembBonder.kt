package com.cubeos.meshsat.hemb

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HeMB bonder — distributes RLNC-coded symbols across heterogeneous bearers.
 *
 * Wire-compatible with Go bridge's internal/hemb/bonder.go.
 *
 * N=1: zero-overhead passthrough (no header, no RLNC coding).
 * N>1: RLNC-coded symbols distributed cost-weighted across bearers.
 */
class HembBonder(
    private val bearers: List<HembBearerProfile>,
    private val deliverFn: ((ByteArray) -> Unit)? = null,
    private val eventListener: HembEventListener? = null,
) {
    companion object {
        /** Global stream ID counter — shared across all bonder instances to prevent collisions. */
        private val globalStreamSeq = AtomicInteger(0)
    }
    private val reassembly: HembReassemblyBuffer? =
        if (bearers.size > 1) HembReassemblyBuffer(deliverFn, eventListener) else null

    // Per-instance counter removed — use global companion object counter
    // to prevent stream ID collisions across ephemeral bonder instances.

    // Stats counters (thread-safe).
    private val _symbolsSent = AtomicLong(0)
    private val _symbolsReceived = AtomicLong(0)
    private val _generationsDecoded = AtomicLong(0)
    private val _generationsFailed = AtomicLong(0)
    private val _bytesFree = AtomicLong(0)
    private val _bytesPaid = AtomicLong(0)
    private val _costMicro = AtomicLong(0) // cost * 1e6

    /**
     * Send a payload across all bearers using RLNC coding.
     */
    suspend fun send(payload: ByteArray) {
        require(bearers.isNotEmpty()) { "hemb: no bearers configured" }

        if (bearers.size == 1) {
            sendN1(payload, bearers[0])
            return
        }

        sendMulti(payload)
    }

    private suspend fun sendN1(payload: ByteArray, bearer: HembBearerProfile) {
        emitEvent(eventListener, HembEventType.SYMBOL_SENT, SymbolSentPayload(
            bearer = BearerRef(bearer.index, bearer.channelType),
            payloadBytes = payload.size,
            costEstimate = bearer.costPerMsg,
        ))
        if (bearer.isFree) {
            _bytesFree.addAndGet(payload.size.toLong())
        } else {
            _bytesPaid.addAndGet(payload.size.toLong())
            _costMicro.addAndGet((bearer.costPerMsg * 1e6).toLong())
        }
        _symbolsSent.incrementAndGet()
        bearer.sendFn(payload)
    }

    private data class BearerAlloc(
        val bearer: HembBearerProfile,
        var source: Int = 0,
        var repair: Int = 0,
    ) {
        val total: Int get() = source + repair
    }

    private suspend fun sendMulti(payload: ByteArray) {
        // Filter to healthy bearers.
        val online = bearers.filter { it.healthScore > 0 }
        if (online.isEmpty()) throw IllegalStateException("hemb: no healthy bearers")
        if (online.size == 1) {
            sendN1(payload, online[0])
            return
        }

        val streamId = globalStreamSeq.incrementAndGet() and 0xFF

        // Step 1: compute minimum data capacity across bearer set.
        val minMtu = online.minOf { it.mtu - HembFrame.headerOverhead(it.headerMode) }
        if (minMtu <= 2) throw IllegalStateException("hemb: bearer MTU too small")

        // Step 2: estimate K.
        var roughSymSize = minMtu - 1 // minus 1 coeff byte estimate
        if (roughSymSize <= 0) roughSymSize = 1
        var k = ((payload.size + roughSymSize - 1) / roughSymSize).coerceIn(1, 255)

        // Step 3: compute exact symSize given K.
        var symSize = minMtu - k
        if (symSize <= 0) {
            k = minMtu / 2
            if (k == 0) k = 1
            symSize = minMtu - k
        }
        if (symSize <= 0) throw IllegalStateException("hemb: payload too large for MTU set")

        // Step 4: recalculate K from exact symSize.
        k = ((payload.size + symSize - 1) / symSize).coerceIn(1, 255)
        if (k > 255) {
            k = 255
            symSize = (payload.size + k - 1) / k
        }

        // Segment payload into K chunks.
        val segments = HembRlncEncoder.segmentPayload(payload, symSize)
        val actualK = segments.size

        // Allocate symbols to bearers.
        val allocs = allocateSymbols(online, actualK)
        val totalN = allocs.sumOf { it.total }

        emitEvent(eventListener, HembEventType.STREAM_OPENED, StreamOpenedPayload(
            streamId = streamId,
            bearerCount = online.size,
            payloadBytes = payload.size,
            generations = 1,
            k = actualK,
            n = totalN,
        ))

        // RLNC encode.
        val symbols = HembRlncEncoder.encode(0, segments, totalN)

        // Distribute symbols across bearers and send.
        var si = 0
        for (alloc in allocs) {
            for (j in 0 until alloc.total) {
                val sym = symbols[si++]
                val isRepair = j >= alloc.source
                val frame = HembFrame.marshalExtended(
                    streamId = streamId,
                    sym = sym,
                    bearerIndex = alloc.bearer.index,
                    totalN = totalN,
                    flags = if (isRepair) HembFrame.FLAG_REPAIR else HembFrame.FLAG_DATA,
                )

                emitEvent(eventListener, HembEventType.SYMBOL_SENT, SymbolSentPayload(
                    symbol = SymbolRef(streamId, 0, sym.symbolIndex),
                    bearer = BearerRef(alloc.bearer.index, alloc.bearer.channelType),
                    payloadBytes = frame.size,
                    isRepair = isRepair,
                    costEstimate = alloc.bearer.costPerMsg,
                ))

                if (alloc.bearer.isFree) {
                    _bytesFree.addAndGet(frame.size.toLong())
                } else {
                    _bytesPaid.addAndGet(frame.size.toLong())
                    _costMicro.addAndGet((alloc.bearer.costPerMsg * 1e6).toLong())
                }
                _symbolsSent.incrementAndGet()

                try {
                    alloc.bearer.sendFn(frame)
                } catch (_: Exception) {
                    // Bearer failure — continue sending to other bearers.
                }
            }
        }
    }

    /**
     * Cost-weighted free-first symbol allocation.
     * Matches Go bridge's allocateSymbols() in bonder.go.
     */
    private fun allocateSymbols(bearers: List<HembBearerProfile>, k: Int): List<BearerAlloc> {
        val free = bearers.filter { it.isFree }.sortedByDescending { it.mtu }
        val paid = bearers.filter { !it.isFree }.sortedBy { it.costPerMsg }

        val allocMap = bearers.associate { it.index to BearerAlloc(it) }.toMutableMap()

        // Phase 1: fill free bearers with source symbols.
        var remaining = k
        for (fb in free) {
            if (remaining == 0) break
            allocMap[fb.index]!!.source = remaining
            remaining = 0
        }

        // Phase 2: waterfall to paid if free insufficient.
        for (pb in paid) {
            if (remaining == 0) break
            allocMap[pb.index]!!.source = remaining
            remaining = 0
        }

        // Phase 3: add per-bearer repair symbols.
        return bearers.mapNotNull { b ->
            val a = allocMap[b.index]!!
            a.repair = if (a.source == 0 && b.isFree) {
                HembProfiles.repairSymbols(b, k)
            } else {
                HembProfiles.repairSymbols(b, a.source)
            }
            if (a.total > 0) a else null
        }
    }

    /**
     * Process an inbound coded symbol from a bearer.
     * N=1: delivers raw payload directly.
     * N>1: parses frame, adds to reassembly buffer.
     */
    fun receiveSymbol(bearerIndex: Int, data: ByteArray): ByteArray? {
        if (bearers.size == 1) {
            _symbolsReceived.incrementAndGet()
            emitEvent(eventListener, HembEventType.SYMBOL_RECEIVED, SymbolReceivedPayload(
                bearer = BearerRef(bearerIndex, bearers[0].channelType),
                payloadBytes = data.size,
                received = 1,
                required = 1,
            ))
            deliverFn?.invoke(data)
            return data
        }

        // N>1: parse and reassemble.
        val parsed = HembFrame.parseSymbol(data) ?: return null
        _symbolsReceived.incrementAndGet()
        emitEvent(eventListener, HembEventType.SYMBOL_RECEIVED, SymbolReceivedPayload(
            symbol = SymbolRef(parsed.streamId, parsed.symbol.genId, parsed.symbol.symbolIndex),
            bearer = BearerRef(bearerIndex, ""),
            payloadBytes = data.size,
        ))

        return reassembly?.addSymbol(parsed.streamId, bearerIndex, parsed.symbol)
    }

    /** Returns current bonding statistics. */
    fun stats(): HembBondStats = HembBondStats(
        activeStreams = reassembly?.activeStreamCount ?: 0,
        symbolsSent = _symbolsSent.get(),
        symbolsReceived = _symbolsReceived.get(),
        generationsDecoded = _generationsDecoded.get(),
        generationsFailed = _generationsFailed.get(),
        bytesFree = _bytesFree.get(),
        bytesPaid = _bytesPaid.get(),
        costIncurred = _costMicro.get() / 1e6,
    )
}
