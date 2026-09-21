package net.meshsat.android.engine

/**
 * Whose name goes on a message this phone passes on to the Hub (MESHSAT-1274).
 *
 * The Hub files a message under the id in its topic and body, and matches sender-scoped routes
 * against it exactly. The rule, traced through the Hub and the Bridge on 21 Sep 2026: the id names
 * who ORIGINATED the message, and `bridge_id` names the gateway that carried it. The Bridge sends
 * a mesh text under the sender's node id; the Hub's own SMS path files a text under the sender's
 * number. The phone filed everything under its satellite modem's IMEI, so an SMS from a person was
 * the RockBLOCK's message, and no route scoped to that person's number could ever match it.
 */
object HubOrigin {

    private val MESH_NODE = Regex("""![0-9a-fA-F]{8}""")

    /**
     * @param sourceBearer the link the message arrived on (sms_0, mesh_0, iridium_0 ...), empty
     *   for a message written on this phone
     * @param origin who sent it, as that link knows them; may be empty
     */
    fun deviceIdFor(sourceBearer: String, origin: String, modemImei: String, bridgeId: String): String {
        val sender = origin.trim()
        val id = when {
            // The modem did carry these: its IMEI is the truthful name, and the Hub keys the
            // satellite delivery receipt on it.
            sourceBearer.startsWith("iridium") -> modemImei
            sourceBearer.startsWith("mesh") -> sender.takeIf { MESH_NODE.matches(it) }.orEmpty()
            sourceBearer.startsWith("sms") || sourceBearer.startsWith("aprs") -> sender
            // Written here: this phone is the originator.
            else -> bridgeId
        }
        // Never nothing: an unknown sender is still this gateway's message, not the modem's.
        return id.ifBlank { bridgeId }.ifBlank { modemImei }
    }
}
