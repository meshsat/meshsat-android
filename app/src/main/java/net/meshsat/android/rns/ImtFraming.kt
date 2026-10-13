package net.meshsat.android.rns

/**
 * IMT framing of Reticulum packets, byte-compatible with CrossTalk's
 * IridiumIMTInterface (IridiumIMTCodec) and the Bridge's imt_framing.go: every
 * Iridium Messaging Transport message that carries a Reticulum packet is
 * "RNSI" + version 0x01 + the raw packet. A message without the header, or
 * whose remainder is not a Reticulum packet, is a text for the plain satellite
 * conversation and never a packet, so Option A and Option B share one modem.
 * [MESHSAT-1553]
 */
object ImtFraming {
    val HEADER: ByteArray = byteArrayOf('R'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'I'.code.toByte(), 0x01)
    private val MAGIC = HEADER.copyOf(4)

    fun encode(packet: ByteArray): ByteArray = HEADER + packet

    /**
     * The packet inside an IMT message, or null when the message is not a framed
     * Reticulum packet (no magic, another version, nothing after the header, or
     * bytes that do not parse as a packet header).
     */
    fun decode(data: ByteArray): ByteArray? {
        if (data.size <= HEADER.size) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
        if (data[4] != HEADER[4]) return null
        val rest = data.copyOfRange(HEADER.size, data.size)
        return if (Header.unpackOrNull(rest) != null) rest else null
    }

    fun isFramed(data: ByteArray): Boolean = decode(data) != null
}

/**
 * Drops repeats of an IMT message within a window: CrossTalk keeps resending an
 * MO every 30 s until the modem acknowledges it, and the network can deliver a
 * late acknowledgement's copy again.
 */
class ImtDedup(private val ttlMs: Long = 15 * 60_000L, private val max: Int = 256) {
    private val seen = LinkedHashMap<String, Long>()

    /** Whether [data] was delivered within the window; records it. */
    @Synchronized
    fun seen(data: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        val k = Rns.sha256(data).hex()
        seen.entries.removeAll { nowMs - it.value > ttlMs }
        if (seen.containsKey(k)) return true
        if (seen.size >= max) seen.remove(seen.keys.first())
        seen[k] = nowMs
        return false
    }
}
