package net.meshsat.android.ui

import net.meshsat.android.ble.MeshtasticProtocol

/**
 * Who a conversation is with (MESHSAT-1249). A conversation is keyed by the other party: a mesh
 * node id ("!a1b2c3d4"), everyone on the mesh ([MESH_ALL]), a phone number, or the satellite link
 * (the modem's IMEI, which is what an incoming satellite message is filed under). Messages the
 * phone sent used to be filed under "self", so every reply ended up in one chat of that name.
 */
object Peers {
    /** Everyone on the mesh channel: Meshtastic's own name for a broadcast. */
    const val MESH_ALL = "^all"

    /** The satellite conversation before the modem's IMEI is known. */
    const val SATELLITE = "satellite"

    /** Legacy key of messages sent before the redesign, with no recipient. */
    const val LEGACY_SELF = "self"

    fun isMeshNode(peer: String): Boolean =
        peer.length == 9 && peer[0] == '!' && peer.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    fun isSatellite(peer: String): Boolean =
        peer == SATELLITE || (peer.length == 15 && peer.all { it.isDigit() })

    fun isMesh(peer: String): Boolean = peer == MESH_ALL || isMeshNode(peer)

    /** The node number of a mesh peer, or null. */
    fun nodeNum(peer: String): Long? = if (isMeshNode(peer)) peer.drop(1).toLongOrNull(16) else null

    /** How a reply goes by default: the way this kind of peer is reached. */
    fun defaultTransport(peer: String): String = when {
        isMesh(peer) -> "mesh"
        isSatellite(peer) || peer == LEGACY_SELF -> "iridium"
        else -> "sms"
    }

    /** The ways a message to this peer can go. */
    fun transportsFor(peer: String): List<String> = when {
        isMesh(peer) -> listOf("mesh")
        isSatellite(peer) || peer == LEGACY_SELF -> listOf("iridium")
        else -> listOf("sms")
    }

    /** What the user calls this peer. */
    fun displayName(peer: String, nodes: List<MeshtasticProtocol.MeshNodeInfo> = emptyList()): String = when {
        peer == MESH_ALL -> "Everyone on the mesh"
        isMeshNode(peer) -> nodes.firstOrNull { MeshtasticProtocol.formatNodeId(it.nodeNum) == peer }
            ?.longName?.ifBlank { null } ?: "Node $peer"
        isSatellite(peer) -> "Satellite"
        peer == LEGACY_SELF -> "Sent from this phone"
        else -> peer
    }

    /** A second line under the name: the id people may need, never the modem's IMEI. */
    fun detail(peer: String): String? = when {
        isMeshNode(peer) -> peer
        isSatellite(peer) -> "By satellite, through Rock7 to the Hub"
        peer == MESH_ALL -> "The mesh channel"
        else -> null
    }
}
