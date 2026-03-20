package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.routing.toHex
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A known path to a Reticulum destination.
 *
 * Multiple paths may exist per destination (via different interfaces).
 * The best path is selected based on cost, hops, and latency.
 */
data class RnsPath(
    val destHash: ByteArray,           // 16 bytes
    val nextHop: ByteArray?,           // 16 bytes (null = direct, no relay)
    val interfaceId: String,           // which RnsInterface carries this path
    val hops: Int,                     // hop count from announce
    val costCents: Int,                // per-message cost of the interface
    val latencyMs: Int,                // estimated latency
    val createdAt: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var expiresAt: Long = System.currentTimeMillis() + DEFAULT_PATH_TTL_MS,
    var announceCount: Int = 1,
) {
    val destHashHex: String get() = destHash.toHex()
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt

    /**
     * Composite path score (lower is better).
     * Prefers: free > paid, fewer hops > more hops, lower latency > higher.
     */
    val score: Int get() = costCents * 1000 + hops * 100 + latencyMs / 100

    override fun equals(other: Any?): Boolean =
        other is RnsPath && destHash.contentEquals(other.destHash) && interfaceId == other.interfaceId

    override fun hashCode(): Int = destHash.contentHashCode() xor interfaceId.hashCode()

    companion object {
        const val DEFAULT_PATH_TTL_MS = 30 * 60 * 1000L  // 30 minutes
    }
}

/**
 * Reticulum path table with cost-aware routing.
 *
 * Maintains multiple paths per destination (one per interface) and selects
 * the best path based on a composite score of cost, hops, and latency.
 *
 * Path discovery is driven by announces: when an announce arrives on an
 * interface, a path entry is created/updated for that destination via
 * that interface.
 *
 * Cost-aware routing prefers:
 *   1. Free transports (LoRa, AX.25) over paid (SMS, Iridium)
 *   2. Fewer hops (closer path)
 *   3. Lower latency
 *
 * When the best path's interface goes offline, the next-best path is used
 * automatically (failover).
 *
 * [MESHSAT-222]
 */
class RnsPathTable(
    private val interfaces: () -> List<RnsInterface>,
    private val pathTtl: Duration = 30.minutes,
) {
    private val lock = ReentrantReadWriteLock()

    // destHashHex → list of paths (one per interface)
    private val paths = HashMap<String, MutableList<RnsPath>>()

    /**
     * Record or update a path from an announce.
     *
     * @param destHash 16-byte destination hash
     * @param interfaceId Interface that received the announce
     * @param hops Hop count from the announce packet
     * @param nextHop Next hop destination hash (null = direct)
     * @return true if this is a new destination (not just a new path)
     */
    fun updateFromAnnounce(
        destHash: ByteArray,
        interfaceId: String,
        hops: Int,
        nextHop: ByteArray? = null,
    ): Boolean {
        val key = destHash.toHex()
        val iface = interfaces().find { it.interfaceId == interfaceId }
        val costCents = iface?.costCents ?: 0
        val latencyMs = iface?.latencyMs ?: 0
        val now = System.currentTimeMillis()
        val ttlMs = pathTtl.inWholeMilliseconds

        lock.write {
            val existing = paths.getOrPut(key) { mutableListOf() }

            // Find existing path via same interface
            val pathIdx = existing.indexOfFirst { it.interfaceId == interfaceId }
            if (pathIdx >= 0) {
                val path = existing[pathIdx]
                // Update: prefer lower hop count
                if (hops <= path.hops) {
                    existing[pathIdx] = path.copy(
                        hops = hops,
                        nextHop = nextHop,
                        costCents = costCents,
                        latencyMs = latencyMs,
                        lastSeen = now,
                        expiresAt = now + ttlMs,
                        announceCount = path.announceCount + 1,
                    )
                } else {
                    existing[pathIdx] = path.copy(
                        lastSeen = now,
                        expiresAt = now + ttlMs,
                        announceCount = path.announceCount + 1,
                    )
                }
                return false
            }

            // New path via this interface
            existing.add(RnsPath(
                destHash = destHash.copyOf(),
                nextHop = nextHop?.copyOf(),
                interfaceId = interfaceId,
                hops = hops,
                costCents = costCents,
                latencyMs = latencyMs,
                createdAt = now,
                lastSeen = now,
                expiresAt = now + ttlMs,
            ))

            Log.d(TAG, "Path added: $key via $interfaceId hops=$hops cost=$costCents")
            return existing.size == 1  // true if first path to this dest
        }
    }

    /**
     * Get the best path to a destination.
     *
     * Selects the path with the lowest score among online interfaces.
     * Falls back to offline interface paths if no online path exists.
     */
    fun bestPath(destHash: ByteArray): RnsPath? {
        val key = destHash.toHex()
        val onlineIds = interfaces().filter { it.isOnline }.map { it.interfaceId }.toSet()

        return lock.read {
            val candidates = paths[key]?.filter { !it.isExpired } ?: return@read null

            // Prefer online interfaces
            val onlinePaths = candidates.filter { it.interfaceId in onlineIds }
            if (onlinePaths.isNotEmpty()) {
                return@read onlinePaths.minByOrNull { it.score }
            }

            // Fallback to any non-expired path
            candidates.minByOrNull { it.score }
        }
    }

    /**
     * Get all paths to a destination, sorted by score (best first).
     */
    fun allPaths(destHash: ByteArray): List<RnsPath> {
        val key = destHash.toHex()
        return lock.read {
            paths[key]?.filter { !it.isExpired }?.sortedBy { it.score } ?: emptyList()
        }
    }

    /**
     * Check if a path exists to a destination.
     */
    fun hasPath(destHash: ByteArray): Boolean {
        val key = destHash.toHex()
        return lock.read {
            paths[key]?.any { !it.isExpired } == true
        }
    }

    /**
     * Get the hop count to a destination via the best path.
     * Returns -1 if no path exists.
     */
    fun hopsTo(destHash: ByteArray): Int {
        return bestPath(destHash)?.hops ?: -1
    }

    /**
     * Remove all paths to a destination.
     */
    fun removeDest(destHash: ByteArray) {
        lock.write { paths.remove(destHash.toHex()) }
    }

    /**
     * Remove all paths via a specific interface.
     * Called when an interface is disabled or permanently removed.
     */
    fun removeInterface(interfaceId: String) {
        lock.write {
            paths.values.forEach { it.removeAll { p -> p.interfaceId == interfaceId } }
            paths.entries.removeAll { it.value.isEmpty() }
        }
    }

    /**
     * Invalidate paths via an interface (e.g., when it goes offline).
     * Doesn't remove — they may come back when the interface reconnects.
     * Instead, marks them with reduced TTL.
     */
    fun markInterfaceStale(interfaceId: String) {
        val now = System.currentTimeMillis()
        val staleTtl = 5 * 60 * 1000L  // 5 minutes grace
        lock.write {
            paths.values.forEach { pathList ->
                pathList.filter { it.interfaceId == interfaceId }.forEach {
                    it.expiresAt = minOf(it.expiresAt, now + staleTtl)
                }
            }
        }
    }

    /**
     * Prune expired paths. Call periodically (e.g., every 5 minutes).
     */
    fun prune() {
        lock.write {
            paths.values.forEach { it.removeAll { p -> p.isExpired } }
            paths.entries.removeAll { it.value.isEmpty() }
        }
    }

    /** Total number of unique destinations with at least one path. */
    fun destCount(): Int = lock.read { paths.size }

    /** Total number of path entries across all destinations. */
    fun pathCount(): Int = lock.read { paths.values.sumOf { it.size } }

    /** Snapshot of all destinations and their best paths. */
    fun snapshot(): Map<String, RnsPath?> = lock.read {
        paths.mapValues { (_, pathList) ->
            pathList.filter { !it.isExpired }.minByOrNull { it.score }
        }
    }

    companion object {
        private const val TAG = "RnsPathTable"
    }
}

/**
 * Path request/response for active path discovery.
 *
 * When no cached path exists for a destination, a path request is
 * flooded. Nodes that know a path respond with a path response.
 *
 * Request: RnsPacket(type=DATA, dest=target, context=PATH_RESPONSE)
 * Response: RnsPacket(type=DATA, dest=requester, context=PATH_RESPONSE)
 *            with payload = [dest_hash(16) + next_hop(16) + hops(1)]
 */
object RnsPathDiscovery {

    const val PATH_REQUEST_SIZE = RnsConstants.DEST_HASH_LEN  // 16 bytes (dest hash only)
    const val PATH_RESPONSE_SIZE = RnsConstants.DEST_HASH_LEN * 2 + 1  // 33 bytes

    /**
     * Create a path request packet.
     * Flooded with the target destination hash in the packet header.
     */
    fun createRequest(targetDestHash: ByteArray): ByteArray {
        val packet = RnsPacket.data(
            destHash = targetDestHash,
            payload = targetDestHash,  // redundant, but matches RNS spec
            destType = RnsConstants.DEST_PLAIN,
            context = RnsConstants.CTX_PATH_RESPONSE,
        )
        return packet.marshal()
    }

    /**
     * Create a path response packet.
     *
     * @param requesterDestHash Who asked for the path
     * @param targetDestHash The destination they're looking for
     * @param nextHop Next hop toward the target (or our own hash if direct)
     * @param hops Hop count to the target from here
     */
    fun createResponse(
        requesterDestHash: ByteArray,
        targetDestHash: ByteArray,
        nextHop: ByteArray,
        hops: Int,
    ): ByteArray {
        val payload = targetDestHash + nextHop + byteArrayOf((hops and 0xFF).toByte())
        val packet = RnsPacket.data(
            destHash = requesterDestHash,
            payload = payload,
            destType = RnsConstants.DEST_PLAIN,
            context = RnsConstants.CTX_PATH_RESPONSE,
        )
        return packet.marshal()
    }

    /**
     * Parse a path response payload.
     * Returns (targetDestHash, nextHop, hops) or null if invalid.
     */
    fun parseResponse(payload: ByteArray): Triple<ByteArray, ByteArray, Int>? {
        if (payload.size < PATH_RESPONSE_SIZE) return null
        val target = payload.copyOfRange(0, RnsConstants.DEST_HASH_LEN)
        val nextHop = payload.copyOfRange(RnsConstants.DEST_HASH_LEN, RnsConstants.DEST_HASH_LEN * 2)
        val hops = payload[RnsConstants.DEST_HASH_LEN * 2].toInt() and 0xFF
        return Triple(target, nextHop, hops)
    }
}
