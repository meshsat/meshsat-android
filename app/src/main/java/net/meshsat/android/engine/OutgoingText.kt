package net.meshsat.android.engine

/**
 * What a typed message looks like on a bearer that carries text (MESHSAT-1286).
 *
 * On 20 September two messages typed in the app reached a stand's Meshtastic channel as
 * "ATEPAE4AAAA=": an MSVQ-SC frame behind a version byte, in base64. The kit's screen and the
 * T-Deck Pros beside it showed exactly that, and the kit relayed each one over Iridium at cost.
 * Compression was on by default for the mesh, as if every radio on a channel were MeshSat.
 *
 * The rule is the Bridge's (MESHSAT-792, MESHSAT-1282): a text bearer carries the bare text.
 * Coding, compression and the version byte belong to byte bearers and to links where both ends
 * are known to be MeshSat. A chat line fits a LoRa packet with room to spare, so on the mesh the
 * coding bought nothing and cost every other radio the message.
 */
object OutgoingText {

    /** A Meshtastic text channel is shared with radios that are not MeshSat: send what was typed. */
    fun onMesh(typed: String): String = typed
}
