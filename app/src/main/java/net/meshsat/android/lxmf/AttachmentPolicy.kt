package net.meshsat.android.lxmf

/**
 * Where a voice note or a photo may travel (MESHSAT-1735): only on a Reticulum link, as the
 * Bridge's lxmf_0 carries them. Every other bearer gets the text alone: a satellite frame, an
 * SMS, a LongFast packet or the Hub's message route cannot take the bytes, and the person is
 * told so before sending, with the size and never a price. The iOS port applies the same rules.
 */
object AttachmentPolicy {
    /** The conversation transport that carries attachments: the LXMF endpoint over Reticulum. */
    const val LXMF = "lxmf"

    /** A voice note is at most this long; longer is refused before it is encoded. */
    const val MAX_NOTE_MS = 30_000L

    /** Whether a message on [transport] takes its attachments along. */
    fun carries(transport: String): Boolean = transport == LXMF

    /** What the person sees as a bearer: the composer's words for it. */
    fun bearerName(transport: String): String = when (transport) {
        "iridium" -> "satellite"
        "sms" -> "SMS"
        "mesh" -> "the mesh"
        "hub" -> "the Hub"
        "tak" -> "TAK"
        LXMF -> "Reticulum"
        else -> transport
    }

    /** "12 s" or "0.8 s". */
    fun seconds(ms: Long): String = if (ms >= 10_000) "${ms / 1000} s" else "%.1f s".format(ms / 1000.0)

    /** "18 KB" or "640 B". */
    fun size(bytes: Int): String = if (bytes >= 1024) "${(bytes + 512) / 1024} KB" else "$bytes B"

    /**
     * The one line under the message box before a send that has attachments pending:
     * what goes, what stays, and how big it is. Null when nothing is attached.
     */
    fun note(transport: String, audioBytes: Int, audioMs: Long, imageBytes: Int): String? {
        val parts = ArrayList<String>(2)
        if (audioBytes > 0) parts.add("voice note ${seconds(audioMs)}, ${size(audioBytes)}")
        if (imageBytes > 0) parts.add("photo ${size(imageBytes)}")
        if (parts.isEmpty()) return null
        val what = parts.joinToString(" and ")
        return if (carries(transport)) {
            "With a $what, by Reticulum; the far end sees it once the whole message is across."
        } else {
            "By ${bearerName(transport)} only the text goes: a $what travels by Reticulum alone, so it is not sent."
        }
    }

    /**
     * Whether the send button may act: a text goes anywhere; without one, only a Reticulum
     * message with an attachment has something to send.
     */
    fun canSend(transport: String, text: String, hasAttachments: Boolean): Boolean =
        text.isNotBlank() || (hasAttachments && carries(transport))

    /** Whether the bytes fit the wire: the two together under [LxAttachments.MAX_BYTES]. */
    fun fits(audioBytes: Int, imageBytes: Int): Boolean = audioBytes + imageBytes <= LxAttachments.MAX_BYTES
}
