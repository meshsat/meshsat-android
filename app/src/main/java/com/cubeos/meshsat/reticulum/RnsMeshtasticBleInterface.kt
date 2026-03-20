package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.ble.MeshtasticProtocol
import com.cubeos.meshsat.routing.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reticulum interface over Meshtastic BLE radio.
 *
 * Encapsulates Reticulum packets inside Meshtastic PRIVATE_APP portnum (256)
 * messages. Handles the LoRa MTU constraint (~230 bytes at SF7) by fragmenting
 * larger Reticulum packets into numbered chunks reassembled on receive.
 *
 * Wire format within the Meshtastic Data payload:
 *
 * **Single-fragment (fits in one LoRa packet):**
 *   [0]     0x00 (unfragmented marker)
 *   [1..]   Reticulum packet bytes
 *
 * **Multi-fragment:**
 *   [0]     fragment_header: (fragment_index[5:1] | more_fragments[0])
 *            — bits [7:6] = 0b01 (fragmented marker)
 *            — bits [5:1] = fragment index (0–31)
 *            — bit  [0]   = 1 if more fragments follow, 0 if last
 *   [1:3]   reassembly_id (uint16 big-endian, from packet hash)
 *   [3..]   fragment payload
 *
 * Maximum 32 fragments per Reticulum packet. At ~227 bytes per fragment
 * payload this supports packets up to ~7 KB, well above the 500-byte
 * Reticulum MTU.
 */
class RnsMeshtasticBleInterface(
    private val ble: MeshtasticBle,
    private val scope: CoroutineScope,
    override val interfaceId: String = "mesh_rns_0",
    override val name: String = "Meshtastic BLE (Reticulum)",
    /** Meshtastic channel to use for Reticulum traffic. */
    private val meshChannel: Int = 0,
    /** Target node for directed Reticulum traffic (0xFFFFFFFF = broadcast). */
    private val targetNode: Long = BROADCAST_ADDR,
) : RnsInterface {

    companion object {
        private const val TAG = "RnsMeshBleIface"

        /** Meshtastic PRIVATE_APP portnum for Reticulum encapsulation. */
        const val PORTNUM_PRIVATE_APP = 256

        /** Broadcast address. */
        const val BROADCAST_ADDR = 0xFFFFFFFFL

        /**
         * LoRa payload budget per Meshtastic packet.
         * At SF7/BW125 the max Meshtastic payload is ~233 bytes.
         * We use 230 as a safe margin across modem presets.
         */
        const val LORA_PAYLOAD_MAX = 230

        /** Single-fragment header: 1 byte (0x00 marker). */
        private const val SINGLE_HEADER_SIZE = 1

        /** Multi-fragment header: 1 byte flags + 2 bytes reassembly ID. */
        private const val FRAG_HEADER_SIZE = 3

        /** Payload capacity per fragment. */
        const val FRAG_PAYLOAD_MAX = LORA_PAYLOAD_MAX - FRAG_HEADER_SIZE  // 227

        /** Maximum number of fragments per reassembly. */
        private const val MAX_FRAGMENTS = 32

        /** Reassembly timeout (ms). */
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L

        /** Maximum concurrent reassembly sessions. */
        private const val MAX_REASSEMBLY_SESSIONS = 64

        /** Fragmented marker in bits [7:6] of the header byte. */
        private const val FRAG_MARKER = 0x40  // 0b01_00000_0
    }

    /**
     * Effective Reticulum MTU: the standard 500 bytes.
     * The interface handles fragmentation transparently.
     */
    override val mtu: Int = RnsConstants.MTU

    override val costCents: Int = 0
    override val latencyMs: Int = 500  // typical LoRa latency

    override val isOnline: Boolean
        get() = ble.state.value == MeshtasticBle.State.Connected

    private var receiveCallback: RnsReceiveCallback? = null
    private var collectJob: Job? = null

    // --- Reassembly state ---

    private data class ReassemblySession(
        val fragments: Array<ByteArray?>,
        val totalExpected: Int,    // set when we receive the last fragment
        var receivedCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        var lastFragment: Boolean = false
        var lastIndex: Int = -1
    }

    private val reassemblyLock = Any()
    private val sessions = LinkedHashMap<Int, ReassemblySession>()

    // --- Send ---

    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "BLE not connected"

        val fragments = fragment(packet)
        for (frag in fragments) {
            val toRadio = encodePrivateAppToRadio(frag, targetNode, meshChannel)
            ble.sendToRadio(toRadio)
        }

        Log.d(TAG, "Sent RNS packet (${packet.size}B, ${fragments.size} frag) to mesh ch=$meshChannel")
        return null
    }

    // --- Receive ---

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    override suspend fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            ble.receivedData.collect { raw ->
                handleBleData(raw)
            }
        }
        Log.i(TAG, "Started listening on BLE receivedData")
    }

    override suspend fun stop() {
        collectJob?.cancel()
        collectJob = null
        synchronized(reassemblyLock) { sessions.clear() }
        Log.i(TAG, "Stopped")
    }

    /**
     * Process raw BLE data from the radio. Filters for PRIVATE_APP portnum
     * and extracts the Reticulum packet (with reassembly if fragmented).
     */
    private fun handleBleData(raw: ByteArray) {
        // Parse FromRadio → MeshPacket → decoded → portnum + payload
        val payload = parsePrivateAppPayload(raw) ?: return

        if (payload.isEmpty()) return

        // Check if single or fragmented
        val header = payload[0].toInt() and 0xFF
        if (header == 0x00) {
            // Single-fragment: strip the 1-byte marker
            if (payload.size < 2) return
            val rnsPacket = payload.copyOfRange(SINGLE_HEADER_SIZE, payload.size)
            deliverPacket(rnsPacket)
        } else if ((header and 0xC0) == FRAG_MARKER) {
            // Multi-fragment
            handleFragment(payload)
        } else {
            Log.d(TAG, "Unknown RNS encapsulation header: 0x${header.toString(16)}")
        }
    }

    private fun deliverPacket(rnsPacket: ByteArray) {
        if (rnsPacket.size < 18) {
            Log.d(TAG, "RNS packet too small: ${rnsPacket.size}")
            return
        }
        Log.d(TAG, "Received RNS packet (${rnsPacket.size}B)")
        receiveCallback?.onReceive(interfaceId, rnsPacket)
    }

    // --- Fragmentation ---

    /**
     * Fragment a Reticulum packet for LoRa transmission.
     *
     * Returns a list of Meshtastic Data payloads, each ≤ [LORA_PAYLOAD_MAX].
     */
    internal fun fragment(packet: ByteArray): List<ByteArray> {
        // Fits in a single frame?
        if (packet.size + SINGLE_HEADER_SIZE <= LORA_PAYLOAD_MAX) {
            return listOf(byteArrayOf(0x00) + packet)
        }

        // Multi-fragment
        val reassemblyId = computeReassemblyId(packet)
        val chunks = packet.toList().chunked(FRAG_PAYLOAD_MAX)
        require(chunks.size <= MAX_FRAGMENTS) {
            "Packet too large: ${packet.size}B requires ${chunks.size} fragments (max $MAX_FRAGMENTS)"
        }

        return chunks.mapIndexed { index, chunk ->
            val isLast = (index == chunks.size - 1)
            val headerByte = FRAG_MARKER or ((index and 0x1F) shl 1) or (if (isLast) 0 else 1)
            val buf = ByteBuffer.allocate(FRAG_HEADER_SIZE + chunk.size)
            buf.order(ByteOrder.BIG_ENDIAN)
            buf.put(headerByte.toByte())
            buf.putShort(reassemblyId.toShort())
            buf.put(chunk.toByteArray())
            buf.array()
        }
    }

    /**
     * Handle a received multi-fragment payload. Reassembles and delivers
     * the complete Reticulum packet when all fragments arrive.
     */
    private fun handleFragment(payload: ByteArray) {
        if (payload.size < FRAG_HEADER_SIZE) return

        val header = payload[0].toInt() and 0xFF
        val fragIndex = (header shr 1) and 0x1F
        val moreFragments = (header and 0x01) == 1
        val reassemblyId = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val fragData = payload.copyOfRange(FRAG_HEADER_SIZE, payload.size)

        synchronized(reassemblyLock) {
            pruneExpiredSessions()

            val session = sessions.getOrPut(reassemblyId) {
                if (sessions.size >= MAX_REASSEMBLY_SESSIONS) {
                    // Evict oldest
                    sessions.remove(sessions.keys.first())
                }
                ReassemblySession(
                    fragments = arrayOfNulls(MAX_FRAGMENTS),
                    totalExpected = -1,
                )
            }

            if (session.fragments[fragIndex] != null) {
                // Duplicate fragment
                return
            }

            session.fragments[fragIndex] = fragData
            session.receivedCount++

            if (!moreFragments) {
                session.lastFragment = true
                session.lastIndex = fragIndex
            }

            // Check if complete
            if (session.lastFragment) {
                val total = session.lastIndex + 1
                var complete = true
                for (i in 0 until total) {
                    if (session.fragments[i] == null) {
                        complete = false
                        break
                    }
                }
                if (complete) {
                    // Reassemble
                    val assembled = ByteArray(
                        (0 until total).sumOf { session.fragments[it]!!.size }
                    )
                    var offset = 0
                    for (i in 0 until total) {
                        val frag = session.fragments[i]!!
                        System.arraycopy(frag, 0, assembled, offset, frag.size)
                        offset += frag.size
                    }
                    sessions.remove(reassemblyId)
                    Log.d(TAG, "Reassembled RNS packet: ${assembled.size}B from $total fragments")
                    deliverPacket(assembled)
                }
            }
        }
    }

    private fun pruneExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value.createdAt > REASSEMBLY_TIMEOUT_MS }
    }

    /** Compute a 16-bit reassembly ID from a packet hash. */
    private fun computeReassemblyId(packet: ByteArray): Int {
        // Simple hash: use first 2 bytes of SHA-256
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(packet)
        return ((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF)
    }

    // --- Meshtastic protobuf encoding/decoding ---

    /**
     * Encode a Reticulum fragment as a Meshtastic ToRadio protobuf
     * using portnum 256 (PRIVATE_APP).
     */
    private fun encodePrivateAppToRadio(
        payload: ByteArray,
        to: Long = BROADCAST_ADDR,
        channel: Int = 0,
    ): ByteArray {
        // Data: portnum=256, payload=fragment bytes
        val dataProto = encodeVarintField(1, PORTNUM_PRIVATE_APP.toLong()) +
            encodeBytesField(2, payload)

        // MeshPacket: to, channel, decoded
        val meshPacket = encodeVarintField(2, to) +
            encodeVarintField(3, channel.toLong()) +
            encodeBytesField(4, dataProto)

        // ToRadio: field 1 = MeshPacket
        return encodeBytesField(1, meshPacket)
    }

    /**
     * Parse a FromRadio protobuf and extract the PRIVATE_APP payload.
     * Returns null if this is not a PRIVATE_APP packet.
     */
    private fun parsePrivateAppPayload(data: ByteArray): ByteArray? {
        // FromRadio: field 2 (LEN) = MeshPacket
        val meshPacketBytes = extractField(data, fieldNumber = 2, wireType = 2) ?: return null

        // MeshPacket: field 4 (LEN) = decoded (Data)
        val decodedBytes = extractField(meshPacketBytes, fieldNumber = 4, wireType = 2) ?: return null

        // Data: field 1 (varint) = portnum
        val portnum = extractVarint(decodedBytes, fieldNumber = 1)?.toInt() ?: return null
        if (portnum != PORTNUM_PRIVATE_APP) return null

        // Data: field 2 (LEN) = payload
        return extractField(decodedBytes, fieldNumber = 2, wireType = 2)
    }

    // --- Protobuf primitives (same as MeshtasticProtocol, inlined to avoid coupling) ---

    private fun extractVarint(data: ByteArray, fieldNumber: Int): Long? {
        var i = 0
        while (i < data.size) {
            val (currentTag, tagLen) = readVarint(data, i)
            i += tagLen
            val wireType = (currentTag and 0x07).toInt()
            val field = (currentTag shr 3).toInt()
            if (field == fieldNumber && wireType == 0) {
                val (value, _) = readVarint(data, i)
                return value
            }
            i = skipField(data, i, wireType) ?: return null
        }
        return null
    }

    private fun extractField(data: ByteArray, fieldNumber: Int, wireType: Int): ByteArray? {
        var i = 0
        while (i < data.size) {
            val (currentTag, tagLen) = readVarint(data, i)
            i += tagLen
            val wt = (currentTag and 0x07).toInt()
            val field = (currentTag shr 3).toInt()
            if (field == fieldNumber && wt == wireType) {
                if (wt == 2) {
                    val (len, lenBytes) = readVarint(data, i)
                    i += lenBytes
                    val end = i + len.toInt()
                    if (end > data.size) return null
                    return data.copyOfRange(i, end)
                }
            }
            i = skipField(data, i, wt) ?: return null
        }
        return null
    }

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to (i - offset)
    }

    private fun skipField(data: ByteArray, offset: Int, wireType: Int): Int? {
        return when (wireType) {
            0 -> { val (_, len) = readVarint(data, offset); offset + len }
            1 -> offset + 8
            2 -> { val (len, lenBytes) = readVarint(data, offset); offset + lenBytes + len.toInt() }
            5 -> offset + 4
            else -> null
        }
    }

    private fun encodeVarint(value: Long): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            result.add(b.toByte())
        } while (v != 0L)
        return result.toByteArray()
    }

    private fun encodeVarintField(fieldNumber: Int, value: Long): ByteArray {
        val tag = (fieldNumber shl 3) or 0
        return encodeVarint(tag.toLong()) + encodeVarint(value)
    }

    private fun encodeBytesField(fieldNumber: Int, data: ByteArray): ByteArray {
        val tag = (fieldNumber shl 3) or 2
        return encodeVarint(tag.toLong()) + encodeVarint(data.size.toLong()) + data
    }
}
