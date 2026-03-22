package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.routing.DestinationTable
import com.cubeos.meshsat.routing.Identity
import com.cubeos.meshsat.routing.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Callback invoked when an announce should be forwarded to interfaces.
 * Receives the fully framed Reticulum packet (header + data).
 */
fun interface RnsRelayCallback {
    fun onRelay(packet: ByteArray, destHash: ByteArray)
}

/**
 * Callback invoked when a new (verified) announce is received.
 */
fun interface RnsAnnounceCallback {
    fun onAnnounce(
        destHash: ByteArray,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        appData: ByteArray?,
        hops: Int,
        sourceInterface: String,
    )
}

/**
 * Reticulum-compatible announce handler.
 *
 * Creates announces using RnsPacket framing (packet_type=ANNOUNCE) with
 * RnsAnnounce data payload. Processes incoming Reticulum announce packets
 * with deduplication, verification, and relay.
 *
 * Wire format of a full announce on the network:
 *   [RnsPacket header: flags(1) + hops(1) + dest_hash(16)]
 *   [RnsAnnounce data: encryption_pub(32) + signing_pub(32) + name_hash(10) +
 *    random_hash(10) + signature(64) + app_data(N)]
 *
 * Total minimum: 18 + 148 = 166 bytes.
 */
class RnsAnnounceHandler(
    private val identity: Identity,
    private val table: DestinationTable? = null,
    private val relayCallback: RnsRelayCallback? = null,
    private val announceCallback: RnsAnnounceCallback? = null,
    private val scope: CoroutineScope,
    private val maxHops: Int = RnsConstants.MAX_HOPS,
    private val dedupTtl: Duration = 30.minutes,
    private val maxDedupEntries: Int = 10000,
    private val minRelayDelay: Duration = 100.milliseconds,
    private val maxRelayDelay: Duration = 2.seconds,
    private val appName: String = RnsDestination.APP_NAME,
    private val aspects: Array<String> = arrayOf(RnsDestination.ASPECT_NODE),
) {
    private val lock = ReentrantLock()
    private val seen = LinkedHashMap<String, Long>(256, 0.75f, true)
    private val localHashes = ConcurrentHashMap<String, Boolean>()

    /** Our Reticulum destination hash (computed from identity + app name). */
    val localDestHash: ByteArray = RnsDestination.computeDestHash(
        identity.encryptionPubRaw, identity.signingPubRaw, appName, *aspects
    )

    init {
        localHashes[localDestHash.toHex()] = true
    }

    /**
     * Create a signed Reticulum announce packet for this node.
     *
     * @param deviceType MeshSat device type (DEVICE_ANDROID, etc.)
     * @param capabilities Capability flags (CAP_MESH | CAP_SMS | ...)
     * @return Serialized Reticulum packet ready to send on interfaces
     */
    fun createAnnounce(
        deviceType: Byte = MeshSatAppData.DEVICE_ANDROID,
        capabilities: Byte = 0,
    ): ByteArray {
        // App data carries MeshSat-specific device metadata only;
        // both public keys are in the Reticulum announce public_key field.
        val appData = MeshSatAppData.encode(deviceType, capabilities)

        val (announce, destHash) = RnsAnnounce.create(
            encryptionPubRaw = identity.encryptionPubRaw,
            signingPubRaw = identity.signingPubRaw,
            appName = appName,
            aspects = aspects,
            appData = appData,
            signFn = { identity.sign(it) },
        )

        val packet = RnsPacket.announce(destHash, announce.marshal())
        return packet.marshal()
    }

    /**
     * Process an incoming Reticulum announce packet.
     *
     * @param raw Raw wire bytes (full RnsPacket)
     * @param sourceInterface Interface identifier that received this packet
     * @return true if the announce was new and valid
     */
    fun handleAnnounce(raw: ByteArray, sourceInterface: String): Boolean {
        // Parse the Reticulum packet framing
        val packet = try {
            RnsPacket.unmarshal(raw)
        } catch (e: Exception) {
            Log.d(TAG, "Packet unmarshal failed from $sourceInterface: ${e.message}")
            return false
        }

        if (packet.packetType != RnsConstants.PACKET_ANNOUNCE) {
            Log.d(TAG, "Not an announce packet (type=${packet.packetType})")
            return false
        }

        // Parse the announce data payload (both keys are in the public_key field)
        val announce = try {
            RnsAnnounce.unmarshal(packet.data)
        } catch (e: Exception) {
            Log.d(TAG, "Announce data unmarshal failed: ${e.message}")
            return false
        }

        // Verify signature and destination hash
        if (!announce.verify(packet.destHash)) {
            Log.w(TAG, "Announce verification failed from $sourceInterface: ${packet.destHash.toHex()}")
            return false
        }

        // Dedup check
        val dedupKey = announce.announceHash(packet.destHash).toHex()
        val now = System.currentTimeMillis()
        lock.withLock {
            if (seen.containsKey(dedupKey)) {
                Log.d(TAG, "Duplicate announce: ${packet.destHash.toHex()}")
                return false
            }
            seen[dedupKey] = now
        }

        val isLocal = localHashes.containsKey(packet.destHash.toHex())

        // Notify callback
        announceCallback?.onAnnounce(
            destHash = packet.destHash,
            encryptionPub = announce.encryptionPub,
            signingPub = announce.signingPub,
            appData = announce.appData,
            hops = packet.hops,
            sourceInterface = sourceInterface,
        )

        // Don't relay our own announces
        if (isLocal) {
            Log.d(TAG, "Local identity, not relaying: ${packet.destHash.toHex()}")
            return true
        }

        // Hop count check
        if (packet.hops >= maxHops) {
            Log.d(TAG, "Max hops exceeded: ${packet.destHash.toHex()} hops=${packet.hops}")
            return true
        }

        // Schedule relay with random delay
        if (relayCallback != null) {
            scheduleRelay(packet)
        }

        return true
    }

    /** Launch the background dedup cache pruner. */
    fun startPruner() {
        scope.launch {
            while (isActive) {
                delay(2.minutes)
                prune()
            }
        }
    }

    /** Number of entries in the dedup cache. */
    fun seenCount(): Int = lock.withLock { seen.size }

    /** Register an additional local destination hash (never relayed). */
    fun registerLocal(destHash: ByteArray) {
        localHashes[destHash.toHex()] = true
    }

    /** Check if a destination hash is registered as local. */
    fun isLocal(destHash: ByteArray): Boolean {
        return localHashes.containsKey(destHash.toHex())
    }

    private fun scheduleRelay(packet: RnsPacket) {
        val delayRange = maxRelayDelay - minRelayDelay
        val delayMs = minRelayDelay.inWholeMilliseconds +
            Random.nextLong(delayRange.inWholeMilliseconds)

        scope.launch {
            delay(delayMs)

            // Increment hop count and re-marshal
            val relayed = packet.copy(hops = packet.hops + 1)
            val relayData = relayed.marshal()

            relayCallback?.onRelay(relayData, packet.destHash)
            Log.d(TAG, "Announce relayed: ${packet.destHash.toHex()} hops=${relayed.hops}")
        }
    }

    private fun prune() {
        lock.withLock {
            val now = System.currentTimeMillis()
            val ttlMs = dedupTtl.inWholeMilliseconds
            seen.entries.removeAll { now - it.value > ttlMs }

            if (seen.size > maxDedupEntries) {
                val excess = seen.size - maxDedupEntries
                val iter = seen.keys.iterator()
                repeat(excess) {
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "RnsAnnounceHandler"
    }
}
