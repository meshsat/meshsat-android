package net.meshsat.android.tak

/**
 * Chats addressed to this phone on TAK (MESHSAT-1707): a dispatcher in a TAK client, or an
 * operator on the Hub's map, writes to one crew, and the Hub hands the chat to the phone that owns
 * that crew on its own tak/cot/in. Pure, so it is tested without Android.
 */
object TakChats {

    /** A chat a dispatcher wrote to this phone. */
    data class Chat(val fromUid: String, val fromCallsign: String, val text: String)

    /**
     * The chat [ev] is, when it is a GeoChat addressed to [ownUid] or [ownCallsign]: by the server
     * routing (`<marti><dest>`, by uid or by callsign) or, without one, by `__chat`. A chat to the
     * room, to somebody else, from this phone itself, or with no words is not.
     */
    fun chatFor(ev: CotEvent, ownUid: String, ownCallsign: String): Chat? {
        if (ev.type != CotType.CHAT) return null
        val d = ev.detail ?: return null
        val text = d.remarks?.text?.trim().orEmpty()
        val fromUid = d.link?.uid?.ifEmpty { null } ?: d.chat?.uid0.orEmpty()
        val fromCallsign = d.chat?.senderCallsign?.ifEmpty { null } ?: d.contact?.callsign.orEmpty()
        if (text.isEmpty() || (fromUid.isEmpty() && fromCallsign.isEmpty()) || fromUid == ownUid) return null
        val toMe = if (d.marti.isNotEmpty()) {
            d.marti.any { it.uid == ownUid || (it.uid.isEmpty() && it.callsign == ownCallsign) }
        } else {
            d.chat?.id == ownUid || d.chat?.uid1 == ownUid
        }
        return if (toMe) Chat(fromUid, fromCallsign, text) else null
    }
}
