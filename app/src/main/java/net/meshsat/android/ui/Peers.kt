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

    /** An LXMF delivery address: 32 hex characters, what Sideband and CrossTalk show (MESHSAT-1553). */
    fun isLxmf(peer: String): Boolean =
        peer.length == 32 && peer.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    /** Another MeshSat app or kit through the Hub: "bridge:<its bridge id>", the Hub's principal (MESHSAT-1584). */
    fun isHub(peer: String): Boolean = net.meshsat.android.hubmsg.HubMsgWire.bridgeOf(peer).isNotEmpty()

    /** The principal of a bridge id, as a conversation key. */
    fun hubPeer(bridgeId: String): String = HUB_PREFIX + bridgeId.trim()

    const val HUB_PREFIX = "bridge:"

    /**
     * A dispatcher on TAK, or an operator on the Hub's map, who wrote to this phone (MESHSAT-1707):
     * "tak:<uid>#<callsign>". The callsign is in the key so the conversation can be named; a TAK
     * uid has no "#".
     */
    const val TAK_PREFIX = "tak:"

    fun isTak(peer: String): Boolean = peer.startsWith(TAK_PREFIX) && takUid(peer).isNotEmpty()

    fun takPeer(uid: String, callsign: String): String = "$TAK_PREFIX$uid#$callsign"

    fun takUid(peer: String): String = peer.removePrefix(TAK_PREFIX).substringBefore('#')

    fun takCallsign(peer: String): String = peer.removePrefix(TAK_PREFIX).substringAfter('#', "")

    /** The node number of a mesh peer, or null. */
    fun nodeNum(peer: String): Long? = if (isMeshNode(peer)) peer.drop(1).toLongOrNull(16) else null

    /** How a reply goes by default: the way this kind of peer is reached. */
    fun defaultTransport(peer: String): String = when {
        isTak(peer) -> "tak"
        isHub(peer) -> "hub"
        isLxmf(peer) -> "lxmf"
        isMesh(peer) -> "mesh"
        isSatellite(peer) || peer == LEGACY_SELF -> "iridium"
        else -> "sms"
    }

    /** The ways a message to this peer can go. */
    fun transportsFor(peer: String): List<String> = when {
        isTak(peer) -> listOf("tak")
        isHub(peer) -> listOf("hub")
        isLxmf(peer) -> listOf("lxmf")
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
        isLxmf(peer) -> lxmfName(peer) ?: "Reticulum ${peer.take(8)}"
        isHub(peer) -> peer.removePrefix(HUB_PREFIX)
        isTak(peer) -> takCallsign(peer).ifEmpty { takUid(peer) }
        else -> peer
    }

    /** The display name an LXMF peer announced, when the node has heard it. */
    fun lxmfName(peer: String): String? = try {
        val stack = net.meshsat.android.service.GatewayService.rnsStack ?: return null
        val dest = ByteArray(16) { i -> peer.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        stack.lxmf.peerInfo(dest)?.displayName?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** A second line under the name: the id people may need, never the modem's IMEI. */
    fun detail(peer: String): String? = when {
        isMeshNode(peer) -> peer
        isLxmf(peer) -> "By Reticulum (LXMF), $peer"
        isHub(peer) -> "Through the Hub to its MeshSat app"
        isTak(peer) -> "On TAK, through the Hub"
        isSatellite(peer) -> "By satellite, through Rock7 to the Hub"
        peer == MESH_ALL -> "The mesh channel"
        else -> null
    }
}
