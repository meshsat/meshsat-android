package com.cubeos.meshsat.hemb

import java.util.concurrent.ConcurrentHashMap

/**
 * Collects RLNC-coded symbols from multiple bearers and decodes when
 * K independent symbols are received.
 *
 * Wire-compatible with Go bridge's internal/hemb/reassemble.go.
 */
class HembReassemblyBuffer(
    private val deliverFn: ((ByteArray) -> Unit)? = null,
    private val eventListener: HembEventListener? = null,
) {
    private val streams = ConcurrentHashMap<Int, StreamState>()

    private class StreamState {
        val generations = ConcurrentHashMap<Int, GenerationState>()
        val createdAt = System.currentTimeMillis()
    }

    private class GenerationState(val k: Int, val symSize: Int) {
        val decoder = HembRlncDecoder(k, symSize)
        var decoded = false
        val bearersSeen = mutableSetOf<Int>()
        val firstSymbolAt = System.currentTimeMillis()
        var symbolCount = 0
    }

    /**
     * Process an inbound HeMB frame. Returns decoded payload if a
     * generation just completed, null otherwise.
     */
    fun addFrame(data: ByteArray): ByteArray? {
        val parsed = HembFrame.parseSymbol(data) ?: return null
        return addSymbol(parsed.streamId, parsed.bearerIndex, parsed.symbol)
    }

    /**
     * Process a coded symbol. Returns decoded payload when K symbols collected.
     */
    @Synchronized
    fun addSymbol(streamId: Int, bearerIndex: Int, sym: HembCodedSymbol): ByteArray? {
        val stream = streams.getOrPut(streamId) { StreamState() }
        val gen = stream.generations.getOrPut(sym.genId) {
            GenerationState(k = sym.k, symSize = sym.data.size)
        }

        if (gen.decoded) return null

        gen.bearersSeen.add(bearerIndex)
        gen.symbolCount++
        gen.decoder.feed(sym)

        if (gen.decoder.isSolvable) {
            gen.decoded = true
            val decodeStart = System.nanoTime()
            val segments = gen.decoder.solve() ?: return null
            val decodeTimeUs = (System.nanoTime() - decodeStart) / 1000

            val payload = ByteArray(segments.sumOf { it.size })
            var offset = 0
            for (seg in segments) {
                System.arraycopy(seg, 0, payload, offset, seg.size)
                offset += seg.size
            }

            emitEvent(eventListener, HembEventType.GENERATION_DECODED, GenerationDecodedPayload(
                streamId = streamId,
                generationId = sym.genId,
                k = gen.k,
                n = gen.symbolCount,
                received = gen.symbolCount,
                decodeTimeUs = decodeTimeUs,
                payloadBytes = payload.size,
                bearers = gen.bearersSeen.map { idx ->
                    BearerContribution(BearerRef(idx, ""), 0, gen.firstSymbolAt, System.currentTimeMillis(), 0.0)
                },
                costTotal = 0.0,
            ))

            deliverFn?.invoke(payload)

            // Remove decoded generation to free stream+gen ID for reuse.
            stream.generations.remove(sym.genId)
            if (stream.generations.isEmpty()) {
                streams.remove(streamId)
            }

            return payload
        }

        return null
    }

    /** Number of active (incomplete) streams. */
    val activeStreamCount: Int get() = streams.size

    /** Remove streams older than maxAgeMs. Returns count of reaped streams. */
    fun reap(maxAgeMs: Long = 5 * 60 * 1000): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        streams.entries.removeIf { (streamId, state) ->
            if (state.createdAt < cutoff) {
                for ((genId, gen) in state.generations) {
                    if (!gen.decoded) {
                        emitEvent(eventListener, HembEventType.GENERATION_FAILED, GenerationFailedPayload(
                            streamId = streamId,
                            generationId = genId,
                            k = gen.k,
                            received = gen.symbolCount,
                            reason = "timeout",
                            bearers = gen.bearersSeen.map { idx ->
                                BearerContribution(BearerRef(idx, ""), 0, gen.firstSymbolAt, System.currentTimeMillis(), 0.0)
                            },
                            costWasted = 0.0,
                        ))
                    }
                }
                removed++
                true
            } else false
        }
        return removed
    }
}
