package net.meshsat.android.engine

/**
 * One satellite message is one SBD frame: 340 bytes, and nothing longer is sent (MESHSAT-1280).
 *
 * The phone used to cut a longer message into parts behind a 2-byte header. For a two-part
 * message that header's first byte is 0x01 - the same byte as the protocol version prefix - and
 * the Hub, which must tell the two apart on that one byte, settled that the version byte wins
 * (meshsat-hub fragment.Reassembler.Claims, 20 Sep 2026) on the understanding that no sender
 * produced such a message. The phone was the one sender that did: a message of 341 to 676 bytes
 * would have been read as one mangled whole message plus a second one, at seven credits or more.
 * The Bridge never did this; its driver refuses anything over 340 bytes. The phone now does the
 * same, and says so before the message is sent rather than after.
 *
 * Reassembly of parts coming IN stays: the Hub still sends long messages to a modem that way.
 */
object SatelliteLimits {

    const val MAX_MO_BYTES = 340

    fun fits(bytes: Int): Boolean = bytes <= MAX_MO_BYTES

    /** What to tell a person whose message is too long, in the words of the compose bar. */
    fun tooLong(bytes: Int): String =
        "Too long for a satellite message: $bytes bytes, $MAX_MO_BYTES at most. Shorten it or send it in two."
}
