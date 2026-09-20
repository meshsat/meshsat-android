package net.meshsat.android.pair

import net.meshsat.android.routing.Identity
import java.security.MessageDigest
import java.util.Base64

/**
 * A person's card, handed over face to face by QR code (MESHSAT-566, MESHSAT-575).
 *
 * Two people in the same operation hold up a screen and the other scans it. The card carries
 * the ways to reach someone and the Ed25519 key of the phone that made it, signed by that same
 * key. The signature proves the card was made by the holder of that key and has not been
 * altered since; it cannot prove who that person is, which is what the fingerprint on screen is
 * for - both sides read it aloud, and it matches or it does not.
 *
 * Wire format, one line, printable:
 *
 *     meshsat:contact:1:<base64url payload>.<base64url signature>
 *
 * The payload is a fixed order of fields joined by a unit separator (0x1F), never JSON: it has
 * to hash to the same bytes on both sides for the signature to mean anything, and a field that
 * happens to contain a separator would otherwise change the shape of the record. Separators are
 * therefore rejected on the way in rather than escaped.
 */
object ContactQR {

    const val PREFIX = "meshsat:contact:1:"

    /** Field separator inside the signed payload. */
    private const val SEP = '\u001F'

    /** Keeps a card inside a QR code that a phone camera can read at arm's length. */
    const val MAX_NAME = 48

    /**
     * How the card reached this phone, which is all the trust there is to record.
     *
     * [SCANNED] came off a screen through this phone's camera, so someone was standing there.
     * [IMPORTED] arrived as text through any other route and could have been forwarded by
     * anyone; the signature still holds, but nothing says who handed it over.
     */
    enum class Trust { SCANNED, IMPORTED }

    data class Card(
        val name: String,
        /** Raw 32-byte Ed25519 public key of the phone that made the card. */
        val signingPubRaw: ByteArray,
        /** Meshtastic node id, e.g. !bf6ee7bc, or blank. */
        val meshNodeId: String = "",
        /** The Hub bridge id, or blank. */
        val bridgeId: String = "",
        /** Seconds since the epoch, so an old card is recognisable as old. */
        val issuedAtSec: Long = 0,
    ) {
        /**
         * Eight bytes of SHA-256 over the key, as four groups. Short enough to read out over a
         * noisy channel, long enough that forging a collision is not worth anyone's afternoon.
         */
        val fingerprint: String get() = fingerprintOf(signingPubRaw)

        override fun equals(other: Any?): Boolean =
            other is Card && name == other.name && signingPubRaw.contentEquals(other.signingPubRaw) &&
                meshNodeId == other.meshNodeId && bridgeId == other.bridgeId && issuedAtSec == other.issuedAtSec

        override fun hashCode(): Int = name.hashCode() * 31 + signingPubRaw.contentHashCode()
    }

    /** What came of reading a card. Every failure says which, so the screen can say why. */
    sealed class Result {
        data class Ok(val card: Card) : Result()
        /** Not a MeshSat card at all, or a version this app does not know. */
        object NotACard : Result()
        /** A card, but malformed: wrong field count, bad base64, wrong key length. */
        object Malformed : Result()
        /** The signature does not match the key in the card: altered in transit, or forged. */
        object BadSignature : Result()
    }

    fun fingerprintOf(signingPubRaw: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signingPubRaw)
        return digest.take(8).joinToString("") { "%02x".format(it) }
            .chunked(4).joinToString(" ")
    }

    /**
     * The bytes that are signed. Anything that is part of the card goes in here, in this order,
     * or it is not covered by the signature and can be changed by whoever passes the card on.
     */
    private fun payloadOf(card: Card): ByteArray = buildString {
        append(card.name); append(SEP)
        append(b64(card.signingPubRaw)); append(SEP)
        append(card.meshNodeId); append(SEP)
        append(card.bridgeId); append(SEP)
        append(card.issuedAtSec)
    }.toByteArray(Charsets.UTF_8)

    /**
     * The card as one line of text, signed by [identity]. The identity must be the one whose
     * public key the card carries; signing someone else's card would produce something that
     * fails [decode] on every phone that reads it.
     */
    fun encode(card: Card, identity: Identity): String {
        require(card.name.isNotBlank()) { "a card needs a name" }
        require(card.name.length <= MAX_NAME) { "name over $MAX_NAME characters" }
        require(!card.name.contains(SEP) && !card.meshNodeId.contains(SEP) && !card.bridgeId.contains(SEP)) {
            "a field may not contain the separator"
        }
        val payload = payloadOf(card)
        return PREFIX + b64(payload) + "." + b64(identity.sign(payload))
    }

    /** Read a card and check its signature. Never throws: every way in is someone else's input. */
    fun decode(text: String): Result {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return Result.NotACard
        val body = trimmed.removePrefix(PREFIX)
        val dot = body.indexOf('.')
        if (dot <= 0 || dot == body.length - 1) return Result.Malformed

        val payload = unB64(body.substring(0, dot)) ?: return Result.Malformed
        val signature = unB64(body.substring(dot + 1)) ?: return Result.Malformed

        val fields = String(payload, Charsets.UTF_8).split(SEP)
        if (fields.size != 5) return Result.Malformed

        val pub = unB64(fields[1]) ?: return Result.Malformed
        if (pub.size != 32) return Result.Malformed
        val issuedAt = fields[4].toLongOrNull() ?: return Result.Malformed
        if (fields[0].isBlank()) return Result.Malformed

        if (!Identity.verifyWithRaw(pub, payload, signature)) return Result.BadSignature

        return Result.Ok(
            Card(
                name = fields[0],
                signingPubRaw = pub,
                meshNodeId = fields[2],
                bridgeId = fields[3],
                issuedAtSec = issuedAt,
            )
        )
    }

    // java.util.Base64, not android.util.Base64: this has to run in the JVM tests as well.
    private fun b64(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun unB64(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
