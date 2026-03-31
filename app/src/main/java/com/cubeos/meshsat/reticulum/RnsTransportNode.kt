package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.routing.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reticulum Transport Node — cross-interface packet relay.
 *
 * This is the central router that makes the Android app a full Transport
 * Node, not just a client. It:
 * - Receives packets from all registered interfaces
 * - Delivers locally-addressed packets to handlers
 * - Forwards packets for other destinations across interfaces
 * - Handles announce relay with transport node flag
 * - Responds to path discovery requests
 * - Enforces hop count limits and packet dedup
 *
 * [MESHSAT-199]
 */
class RnsTransportNode(
    private val localDestHash: ByteArray,
    private val announceHandler: RnsAnnounceHandler,
    private val linkManager: RnsLinkManager,
    private val pathTable: RnsPathTable,
    private val forwardingTable: RnsForwardingTable,
    private val interfaces: () -> Map<String, RnsInterface>,
    private val scope: CoroutineScope,
    private val announceIntervalMs: Long = ANNOUNCE_INTERVAL_MS,
) {
    companion object {
        private const val TAG = "RnsTransportNode"

        /** Maximum hop count before dropping a packet. */
        const val MAX_HOPS = 128

        /** Paid interface prefixes — protocol overhead MUST NOT be broadcast to these. */
        private val PAID_PREFIXES = listOf("iridium", "astrocast", "sms", "cellular")

        /**
         * Check if an interface ID represents a paid transport.
         * Paid transports: Iridium SBD/IMT ($0.05+/msg), Astrocast, SMS.
         * Protocol overhead (announces, time sync, keepalives) must NEVER
         * be sent to paid interfaces — only user-initiated messages.
         */
        fun isPaidInterface(id: String): Boolean =
            PAID_PREFIXES.any { id.startsWith(it) }

        /** TTL for packet dedup cache entries. */
        const val DEDUP_TTL_MS = 5 * 60 * 1000L  // 5 minutes

        /** Max entries in dedup cache. */
        const val MAX_DEDUP_ENTRIES = 10_000

        /** Forwarding table prune interval. */
        const val PRUNE_INTERVAL_MS = 2 * 60 * 1000L  // 2 minutes

        /** Announce interval. */
        const val ANNOUNCE_INTERVAL_MS = 10 * 60 * 1000L  // 10 minutes
    }

    /** Callback for locally-addressed data packets. */
    fun interface LocalDeliveryCallback {
        fun onLocalPacket(packet: RnsPacket, sourceInterface: String)
    }

    var localDeliveryCallback: LocalDeliveryCallback? = null

    /** Callback for HeMB frames detected inside Reticulum data packets. */
    fun interface HembFrameCallback {
        fun onHembFrame(sourceInterface: String, frameData: ByteArray)
    }

    var hembCallback: HembFrameCallback? = null

    // Public accessors for dashboard/settings widgets (MESHSAT-394/396)
    val destHashHex: String get() = localDestHash.joinToString("") { "%02x".format(it) }
    fun interfaceCount(): Int = interfaces().size
    fun onlineInterfaceCount(): Int = interfaces().count { (_, iface) -> iface.isOnline }
    fun destCount(): Int = pathTable.destCount()
    fun pathCount(): Int = pathTable.pathCount()

    // Packet dedup cache: packetHash → timestamp
    private val seenPackets = ConcurrentHashMap<String, Long>()

    @Volatile
    private var running = false

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    fun start() {
        running = true

        // Register receive callbacks on all interfaces
        interfaces().forEach { (id, iface) ->
            iface.setReceiveCallback(RnsReceiveCallback { _, raw ->
                scope.launch { onPacketReceived(id, raw) }
            })
        }

        // Start periodic tasks
        scope.launch { pruneLoop() }
        scope.launch { announceLoop() }

        Log.i(TAG, "Transport node started (${interfaces().size} interfaces)")
    }

    fun stop() {
        running = false
        interfaces().forEach { (_, iface) ->
            iface.setReceiveCallback(null)
        }
        Log.i(TAG, "Transport node stopped")
    }

    // ═══════════════════════════════════════════════════════════════
    // Packet reception — unified entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handle a raw packet received from an interface.
     * Routes to announce handler, link manager, local delivery, or forwarding.
     */
    fun onPacketReceived(sourceInterface: String, raw: ByteArray) {
        if (!running) return

        // Log all incoming packets for debugging (WARN level for visibility)
        if (raw.size >= 2) {
            val h0 = raw[0].toInt() and 0xFF
            val h1 = raw[1].toInt() and 0xFF
            Log.w(TAG, "RECV: iface=$sourceInterface len=${raw.size} head=${String.format("%02x%02x", h0, h1)}")
        }

        // HeMB frame detection — check BEFORE Reticulum unmarshal.
        if (raw.isNotEmpty() && com.cubeos.meshsat.hemb.HembFrame.isHembFrame(raw)) {
            Log.i(TAG, "hemb: HeMB frame detected from $sourceInterface (${raw.size}B)")
            hembCallback?.onHembFrame(sourceInterface, raw)
            return
        }

        val packet = try {
            RnsPacket.unmarshal(raw)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to unmarshal packet from $sourceInterface: ${e.message}")
            return
        }

        // Dedup check
        val packetHash = packetHash(raw)
        val now = System.currentTimeMillis()
        val prev = seenPackets.putIfAbsent(packetHash, now)
        if (prev != null) {
            return // Already seen
        }
        if (seenPackets.size > MAX_DEDUP_ENTRIES) {
            pruneSeenCache()
        }

        // Dispatch by packet type
        when (packet.packetType) {
            RnsConstants.PACKET_ANNOUNCE -> handleAnnounce(packet, raw, sourceInterface)
            RnsConstants.PACKET_LINKREQUEST -> handleLinkRequest(packet, raw, sourceInterface)
            RnsConstants.PACKET_PROOF -> handleProof(packet, raw, sourceInterface)
            RnsConstants.PACKET_DATA -> handleData(packet, raw, sourceInterface)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Announce handling — relay + learn forwarding paths
    // ═══════════════════════════════════════════════════════════════

    private fun handleAnnounce(packet: RnsPacket, raw: ByteArray, sourceInterface: String) {
        // Learn forwarding path from announce
        val interfaceMap = interfaces()
        val iface = interfaceMap[sourceInterface]
        forwardingTable.learn(
            destHash = packet.destHash,
            nextHop = null, // direct from this interface
            egressInterface = sourceInterface,
            hops = packet.hops,
            costCents = iface?.costCents ?: 0,
        )

        // Delegate to announce handler (handles verification, dedup, relay)
        announceHandler.handleAnnounce(raw, sourceInterface)

        // Relay announce on all OTHER free interfaces (transport node duty)
        // Never relay announces to paid interfaces — costs money per message.
        if (packet.hops < MAX_HOPS) {
            val relayPacket = packet.copy(hops = packet.hops + 1)
            val relayRaw = relayPacket.marshal()
            interfaceMap.forEach { (id, iface) ->
                if (id != sourceInterface && iface.isOnline && !isPaidInterface(id)) {
                    scope.launch {
                        // Random relay delay to reduce collisions
                        delay((100L..2000L).random())
                        iface.send(relayRaw)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Link handling — deliver locally
    // ═══════════════════════════════════════════════════════════════

    private fun handleLinkRequest(packet: RnsPacket, raw: ByteArray, sourceInterface: String) {
        if (isLocalDest(packet.destHash)) {
            // Link request addressed to us
            val proofRaw = linkManager.handleLinkRequest(raw)
            if (proofRaw != null) {
                // Send proof back on the same interface
                val iface = interfaces()[sourceInterface]
                scope.launch { iface?.send(proofRaw) }
            }
        } else {
            // Forward link request to the destination
            forwardPacket(packet, raw, sourceInterface)
        }
    }

    private fun handleProof(packet: RnsPacket, raw: ByteArray, sourceInterface: String) {
        // Proofs are addressed to link IDs (not dest hashes).
        // If we have a pending outbound link with this ID, complete the handshake.
        // Otherwise, forward the proof to the destination.
        // Note: for transport node relay, we just forward proofs we don't own.
        forwardPacket(packet, raw, sourceInterface)
    }

    // ═══════════════════════════════════════════════════════════════
    // Data handling — local delivery or forwarding
    // ═══════════════════════════════════════════════════════════════

    private fun handleData(packet: RnsPacket, raw: ByteArray, sourceInterface: String) {
        // HeMB frame detection — check inner payload before routing.
        if (packet.data.isNotEmpty() && com.cubeos.meshsat.hemb.HembFrame.isHembFrame(packet.data)) {
            hembCallback?.onHembFrame(sourceInterface, packet.data)
            return
        }

        // Check if addressed to us
        if (isLocalDest(packet.destHash)) {
            handleLocalData(packet, sourceInterface)
            return
        }

        // Check for path request (CTX_PATH_RESPONSE context = path request)
        if (packet.context == RnsConstants.CTX_PATH_RESPONSE && packet.data.size >= 16) {
            handlePathRequest(packet, sourceInterface)
            return
        }

        // Forward to destination
        forwardPacket(packet, raw, sourceInterface)
    }

    private fun handleLocalData(packet: RnsPacket, sourceInterface: String) {
        localDeliveryCallback?.onLocalPacket(packet, sourceInterface)
    }

    // ═══════════════════════════════════════════════════════════════
    // Path discovery — respond to path requests
    // ═══════════════════════════════════════════════════════════════

    private fun handlePathRequest(packet: RnsPacket, sourceInterface: String) {
        val targetHash = packet.data.copyOfRange(0, RnsConstants.DEST_HASH_LEN)

        // Check if we know a path to the target
        val entry = forwardingTable.lookup(targetHash)
        val path = pathTable.bestPath(targetHash)

        val nextHop: ByteArray?
        val hops: Int

        if (entry != null) {
            nextHop = entry.nextHop ?: localDestHash
            hops = entry.hops
        } else if (path != null) {
            nextHop = path.nextHop ?: localDestHash
            hops = path.hops
        } else {
            // We don't know this destination — flood the request on other interfaces
            forwardPacket(packet, packet.marshal(), sourceInterface)
            return
        }

        // Build path response
        val responseData = ByteArray(RnsConstants.DEST_HASH_LEN * 2 + 1)
        targetHash.copyInto(responseData, 0)
        nextHop.copyInto(responseData, RnsConstants.DEST_HASH_LEN)
        responseData[RnsConstants.DEST_HASH_LEN * 2] = hops.toByte()

        val response = RnsPacket.data(
            destHash = packet.destHash,
            payload = responseData,
            context = RnsConstants.CTX_PATH_RESPONSE,
        )

        val iface = interfaces()[sourceInterface]
        scope.launch { iface?.send(response.marshal()) }
        Log.d(TAG, "Path response sent for ${targetHash.toHex()} via $sourceInterface (${hops} hops)")
    }

    // ═══════════════════════════════════════════════════════════════
    // Packet forwarding — cross-interface relay
    // ═══════════════════════════════════════════════════════════════

    /**
     * Forward a packet to its destination via the best available interface.
     * Decrements hop count; drops if TTL exhausted.
     */
    private fun forwardPacket(packet: RnsPacket, raw: ByteArray, ingressInterface: String) {
        // TTL check
        if (packet.hops >= MAX_HOPS) {
            Log.d(TAG, "Dropping packet: max hops reached (${packet.hops})")
            return
        }

        // Find best egress interface
        val entry = forwardingTable.lookup(packet.destHash)
        val path = if (entry == null) pathTable.bestPath(packet.destHash) else null

        val egressId = entry?.egressInterface ?: path?.interfaceId
        if (egressId == null) {
            // No known path — flood on all interfaces except ingress
            floodPacket(packet, ingressInterface)
            return
        }

        // Don't forward back to ingress
        if (egressId == ingressInterface) {
            // Try alternative paths
            val alternatives = forwardingTable.allEntries(packet.destHash)
                .filter { it.egressInterface != ingressInterface }
            val altId = alternatives.firstOrNull()?.egressInterface
            if (altId == null) {
                Log.d(TAG, "No alternative path for ${packet.destHash.toHex()}, dropping")
                return
            }
            sendOnInterface(packet, altId)
            return
        }

        sendOnInterface(packet, egressId)
    }

    /**
     * Flood a packet on all interfaces except the ingress.
     * Used when no specific path is known.
     */
    private fun floodPacket(packet: RnsPacket, ingressInterface: String) {
        val forwarded = packet.copy(hops = packet.hops + 1)

        // Wrap for transport if not already HEADER_2
        val wirePacket = if (forwarded.headerType == RnsConstants.HEADER_1) {
            RnsPacket.wrapForTransport(forwarded, localDestHash)
        } else {
            forwarded
        }

        if (!RnsPacket.validateSize(wirePacket)) {
            Log.d(TAG, "Packet too large to flood after transport wrapping")
            return
        }

        // Check if this is protocol overhead (time sync, custody, RLNC) — skip paid interfaces
        val isProtocolOverhead = packet.context in listOf(
            RnsConstants.CTX_TIME_SYNC_REQ, RnsConstants.CTX_TIME_SYNC_RESP,
            RnsConstants.CTX_CUSTODY_OFFER, RnsConstants.CTX_CUSTODY_ACK,
            RnsConstants.CTX_RLNC, RnsConstants.CTX_KEEPALIVE,
        )

        val wireRaw = wirePacket.marshal()
        interfaces().forEach { (id, iface) ->
            if (id != ingressInterface && iface.isOnline) {
                if (isProtocolOverhead && isPaidInterface(id)) return@forEach // skip paid
                scope.launch { iface.send(wireRaw) }
            }
        }
    }

    private fun sendOnInterface(packet: RnsPacket, interfaceId: String) {
        val forwarded = packet.copy(hops = packet.hops + 1)

        // Wrap for transport if not already HEADER_2
        val wirePacket = if (forwarded.headerType == RnsConstants.HEADER_1) {
            RnsPacket.wrapForTransport(forwarded, localDestHash)
        } else {
            forwarded
        }

        if (!RnsPacket.validateSize(wirePacket)) {
            Log.d(TAG, "Packet too large for interface $interfaceId after transport wrapping")
            return
        }

        val iface = interfaces()[interfaceId]
        if (iface != null && iface.isOnline) {
            scope.launch {
                val err = iface.send(wirePacket.marshal())
                if (err != null) {
                    Log.w(TAG, "Forward to $interfaceId failed: $err")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Send — outgoing packets from this node
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a data packet to a destination. Uses the best known path.
     * Returns error message or null on success.
     */
    suspend fun sendData(destHash: ByteArray, data: ByteArray, context: Byte = RnsConstants.CTX_NONE): String? {
        val packet = RnsPacket.data(destHash = destHash, payload = data, context = context)
        if (!RnsPacket.validateSize(packet)) {
            return "packet exceeds MTU"
        }

        val entry = forwardingTable.lookup(destHash)
        val path = if (entry == null) pathTable.bestPath(destHash) else null
        val egressId = entry?.egressInterface ?: path?.interfaceId

        if (egressId != null) {
            val iface = interfaces()[egressId]
            if (iface != null && iface.isOnline) {
                return iface.send(packet.marshal())
            }
        }

        // No specific path — flood
        val raw = packet.marshal()
        interfaces().forEach { (_, iface) ->
            if (iface.isOnline) {
                iface.send(raw)
            }
        }
        return null
    }

    /**
     * Broadcast an announce on all online interfaces.
     */
    suspend fun broadcastAnnounce() {
        val announceRaw = announceHandler.createAnnounce(
            MeshSatAppData.DEVICE_ANDROID,
            (MeshSatAppData.CAP_MESH.toInt() or
                    MeshSatAppData.CAP_SATELLITE.toInt() or
                    MeshSatAppData.CAP_SMS.toInt() or
                    MeshSatAppData.CAP_APRS.toInt() or
                    MeshSatAppData.CAP_MQTT.toInt() or
                    CAP_TRANSPORT_NODE.toInt()).toByte(),
        )
        var sent = 0
        interfaces().forEach { (id, iface) ->
            if (iface.isOnline && !isPaidInterface(id)) {
                val err = iface.send(announceRaw)
                if (err == null) sent++ else Log.w(TAG, "Announce send failed on $id: $err")
            }
        }
        Log.i(TAG, "Announce broadcast on $sent/${interfaces().size} interfaces")
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun isLocalDest(destHash: ByteArray): Boolean {
        return destHash.contentEquals(localDestHash) ||
                announceHandler.isLocal(destHash)
    }

    private fun packetHash(raw: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(raw).copyOfRange(0, 16).toHex()
    }

    private fun pruneSeenCache() {
        val now = System.currentTimeMillis()
        seenPackets.entries.removeAll { now - it.value > DEDUP_TTL_MS }
    }

    private suspend fun pruneLoop() {
        while (running && scope.isActive) {
            delay(PRUNE_INTERVAL_MS)
            val removed = forwardingTable.prune()
            pruneSeenCache()
            if (removed > 0) {
                Log.d(TAG, "Pruned $removed forwarding entries, ${seenPackets.size} dedup entries")
            }
        }
    }

    private suspend fun announceLoop() {
        // Initial announce after short delay
        delay(5000)
        if (running) broadcastAnnounce()

        while (running && scope.isActive) {
            delay(announceIntervalMs)
            if (running) broadcastAnnounce()
        }
    }
}

/** Transport node capability flag for MeshSatAppData. */
const val CAP_TRANSPORT_NODE: Byte = 0x20
