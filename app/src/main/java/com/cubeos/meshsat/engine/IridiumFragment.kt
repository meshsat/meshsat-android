package com.cubeos.meshsat.engine

/**
 * Iridium SBD 2-byte fragment header encoder/decoder and reassembly buffer.
 * Compatible with meshsat bridge and meshsat-hub fragment protocol.
 *
 * Header format (2 bytes):
 *   Byte 0: [FRAG_INDEX:4bit | FRAG_TOTAL:4bit]
 *     - FRAG_INDEX: 0-15 (0 = first fragment)
 *     - FRAG_TOTAL: 1-16 (encoded as total-1, so 0=1, 15=16)
 *   Byte 1: MSG_ID (uint8, wrapping counter per device)
 *   Bytes 2+: payload
 *
 * Fragment payload = MTU - 2 (header bytes).
 */
object IridiumFragment {

    const val HEADER_SIZE = 2
    const val MO_MTU = 340
    const val MT_MTU = 270
    const val MAX_FRAGMENTS = 16
    const val FRAG_PAYLOAD = MO_MTU - HEADER_SIZE // 338

    /**
     * Encode a 2-byte fragment header.
     */
    fun encodeHeader(fragIndex: Int, fragTotal: Int, msgID: Int): ByteArray {
        return byteArrayOf(
            ((fragIndex and 0x0F) shl 4 or ((fragTotal - 1) and 0x0F)).toByte(),
            (msgID and 0xFF).toByte(),
        )
    }

    /**
     * Decode a 2-byte fragment header.
     * Returns Triple(fragIndex, fragTotal, msgID).
     */
    fun decodeHeader(b0: Byte, b1: Byte): Triple<Int, Int, Int> {
        val fragIndex = (b0.toInt() and 0xFF) shr 4
        val fragTotal = (b0.toInt() and 0x0F) + 1
        val msgID = b1.toInt() and 0xFF
        return Triple(fragIndex, fragTotal, msgID)
    }

    /**
     * Fragment a message into MTU-sized SBD payloads.
     * Returns null if the message fits in a single frame (no fragmentation needed).
     * [msgID] should be a wrapping counter (0-255).
     */
    fun fragment(data: ByteArray, mtu: Int = MO_MTU, msgID: Int): List<ByteArray>? {
        if (data.size <= mtu) return null

        val fragPayload = mtu - HEADER_SIZE
        if (fragPayload <= 0) return null

        var nFrags = (data.size + fragPayload - 1) / fragPayload
        val truncatedData = if (nFrags > MAX_FRAGMENTS) {
            nFrags = MAX_FRAGMENTS
            data.copyOfRange(0, nFrags * fragPayload)
        } else {
            data
        }

        return (0 until nFrags).map { i ->
            val start = i * fragPayload
            val end = minOf(start + fragPayload, truncatedData.size)
            val hdr = encodeHeader(i, nFrags, msgID)
            hdr + truncatedData.copyOfRange(start, end)
        }
    }

    /**
     * Thread-safe reassembly buffer for incoming fragmented messages.
     * Keyed by msgID (single-device use on Android).
     */
    class ReassemblyBuffer(private val maxAgeMs: Long = 5 * 60 * 1000L) {
        private val lock = Any()
        private val pending = mutableMapOf<Int, PendingMessage>()

        private class PendingMessage(
            val fragments: Array<ByteArray?>,
            val total: Int,
            var received: Int = 0,
            val createdAt: Long = System.currentTimeMillis(),
        )

        /**
         * Add a fragment. Returns the reassembled message if all fragments received, null otherwise.
         * [data] must include the 2-byte header.
         */
        fun addFragment(data: ByteArray): ByteArray? {
            require(data.size >= HEADER_SIZE) { "fragment too short: ${data.size} bytes" }

            val (fragIndex, fragTotal, msgID) = decodeHeader(data[0], data[1])
            val payload = data.copyOfRange(HEADER_SIZE, data.size)

            synchronized(lock) {
                val pm = pending.getOrPut(msgID) {
                    PendingMessage(
                        fragments = arrayOfNulls(fragTotal),
                        total = fragTotal,
                    )
                }

                require(fragIndex < pm.total) { "fragment index $fragIndex >= total ${pm.total}" }

                if (pm.fragments[fragIndex] == null) {
                    pm.received++
                }
                pm.fragments[fragIndex] = payload

                if (pm.received < pm.total) return null

                // All fragments received — reassemble.
                val result = pm.fragments.fold(ByteArray(0)) { acc, frag -> acc + frag!! }
                pending.remove(msgID)
                return result
            }
        }

        /** Remove pending reassemblies older than maxAgeMs. */
        fun expire(): Int {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            synchronized(lock) {
                val expired = pending.entries.count { it.value.createdAt < cutoff }
                pending.entries.removeAll { it.value.createdAt < cutoff }
                return expired
            }
        }

        fun pendingCount(): Int = synchronized(lock) { pending.size }
    }
}
