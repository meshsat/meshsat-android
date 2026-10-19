package net.meshsat.android.hubmsg

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The identities of the Hub app route hub_msg (HeMB-NG stage 2, contract C1e, MESHSAT-1584): a
 * UUIDv7 for every operation, message and event; a UUIDv5 under the pinned namespace only for
 * the two documented exceptions. The same rules as the Bridge's and the Hub's internal/hubmsg
 * (ids.go); the shared vectors are the fixture release fixtures-v1.1 (ids.json).
 */
object HubMsgIds {
    /** UUIDv5(NAMESPACE_URL, "https://meshsat.net/ns/hemb-ng/v1"). */
    const val NAMESPACE = "a389cf74-e475-53d9-b656-d994a485fe0a"

    private val random = SecureRandom()

    /** What the two UUIDv5 exceptions are checked against. runStartMs < 0 means absent. */
    data class Context(
        val sender: String = "",
        val name: String = "",
        val bridgeId: String = "",
        val runStartMs: Long = -1,
    )

    /** The 16 bytes of a UUID in its canonical text (lower case, 8-4-4-4-12), or null. */
    fun parseCanonical(s: String): ByteArray? {
        if (s.length != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-') return null
        val hex = s.substring(0, 8) + s.substring(9, 13) + s.substring(14, 18) + s.substring(19, 23) + s.substring(24, 36)
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(16) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun format(b: ByteArray): String {
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    private fun rfcVariant(b: ByteArray) = (b[8].toInt() and 0xFF) ushr 6 == 0b10
    private fun version(b: ByteArray) = (b[6].toInt() and 0xFF) ushr 4

    /** Whether [s] is a UUIDv7 in canonical text with the RFC variant. */
    fun validV7(s: String): Boolean {
        val b = parseCanonical(s) ?: return false
        return rfcVariant(b) && version(b) == 7
    }

    /** C1e: v7 everywhere; v5 only for a Hub-derived mid (C1c) or an old-format SOS id (C10). */
    fun idValid(s: String, field: String, c: Context = Context()): Boolean {
        val b = parseCanonical(s) ?: return false
        if (!rfcVariant(b)) return false
        return when (version(b)) {
            7 -> true
            5 -> when {
                field == "mid" && c.sender == "hub" && c.name.isNotEmpty() -> uuidV5(c.name) == s
                field == "sos_id" && c.bridgeId.isNotEmpty() && c.runStartMs >= 0 -> uuidV5("${c.bridgeId}:${c.runStartMs}") == s
                else -> false
            }
            else -> false
        }
    }

    /** The UUIDv5 of [name] under [NAMESPACE] (RFC 9562 5.5: SHA-1, not a security use). */
    fun uuidV5(name: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(requireNotNull(parseCanonical(NAMESPACE)))
        md.update(name.toByteArray(Charsets.UTF_8))
        val b = md.digest().copyOf(16)
        b[6] = (0x50 or (b[6].toInt() and 0x0F)).toByte()
        b[8] = (0x80 or (b[8].toInt() and 0x3F)).toByte()
        return format(b)
    }

    /** A UUIDv7 for [nowMs] (RFC 9562 5.7): the time in its first 48 bits, the rest random. */
    fun newV7(nowMs: Long): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        val ms = nowMs.coerceAtLeast(0)
        for (i in 0 until 6) b[i] = (ms ushr (8 * (5 - i))).toByte()
        b[6] = (0x70 or (b[6].toInt() and 0x0F)).toByte()
        b[8] = (0x80 or (b[8].toInt() and 0x3F)).toByte()
        return format(b)
    }
}

/**
 * The content digest (C1d): SHA-256 over each field as a 4-byte big-endian length and its bytes,
 * in this order: the tag, the mid's 16 raw bytes, the recipient principal, the content type,
 * mid_expires_at (unix ms, 8 bytes big-endian), the body. Every hop recomputes it from the
 * delivered fields; a carried value is only compared, never trusted (digest.go).
 */
object HubMsgDigest {
    const val TAG = "meshsat.content/1"

    fun preimage(mid: String, recipient: String, contentType: String, midExpiresAt: Long, body: ByteArray): ByteArray {
        val m = requireNotNull(HubMsgIds.parseCanonical(mid)) { "mid is not a canonical UUID" }
        // The reference packs it unsigned (struct ">Q") and refuses what does not fit.
        require(midExpiresAt >= 0) { "mid_expires_at is negative" }
        val exp = ByteArray(8) { i -> (midExpiresAt ushr (8 * (7 - i))).toByte() }
        val out = java.io.ByteArrayOutputStream()
        for (f in listOf(TAG.toByteArray(Charsets.UTF_8), m, recipient.toByteArray(Charsets.UTF_8),
            contentType.toByteArray(Charsets.UTF_8), exp, body)) {
            val n = f.size
            out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            out.write(f)
        }
        return out.toByteArray()
    }

    fun sha256Hex(mid: String, recipient: String, contentType: String, midExpiresAt: Long, body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(preimage(mid, recipient, contentType, midExpiresAt, body))
            .joinToString("") { "%02x".format(it) }

    /** The digest, or null for fields it cannot be computed over (a bad mid, a negative deadline). */
    fun sha256HexOrNull(mid: String, recipient: String, contentType: String, midExpiresAt: Long, body: ByteArray): String? =
        try { sha256Hex(mid, recipient, contentType, midExpiresAt, body) } catch (e: IllegalArgumentException) { null }
}
