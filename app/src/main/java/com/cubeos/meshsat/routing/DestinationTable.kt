package com.cubeos.meshsat.routing

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A known remote identity discovered via announce.
 */
data class Destination(
    val destHash: ByteArray,           // 16 bytes
    val signingPub: ByteArray,         // 32 bytes (raw Ed25519)
    val encryptionPub: ByteArray,      // 32 bytes (raw X25519)
    var appData: ByteArray?,
    var hopCount: Int,
    var sourceIface: String,
    val firstSeen: Long,               // epoch millis
    var lastSeen: Long,                // epoch millis
    var announceCount: Int,
) {
    val destHashHex: String get() = destHash.toHex()

    override fun equals(other: Any?): Boolean =
        other is Destination && destHash.contentEquals(other.destHash)

    override fun hashCode(): Int = destHash.contentHashCode()
}

/**
 * In-memory routing table of known destinations discovered via announces.
 * Thread-safe with read-write lock.
 *
 * Port of Go's routing/table.go (without DB persistence).
 */
class DestinationTable {
    private val lock = ReentrantReadWriteLock()
    private val dests = HashMap<String, Destination>()  // destHashHex -> Destination

    /**
     * Record or update a destination from an announce packet.
     * Returns true if this is a new destination.
     */
    fun update(announce: Announce, sourceIface: String): Boolean {
        val key = announce.destHash.toHex()
        val now = System.currentTimeMillis()

        lock.write {
            val existing = dests[key]
            if (existing != null) {
                // Update existing: prefer lower hop count (closer path)
                val hops = announce.hopCount.toInt() and 0xFF
                if (hops <= existing.hopCount) {
                    existing.hopCount = hops
                    existing.sourceIface = sourceIface
                }
                existing.lastSeen = now
                existing.announceCount++
                if (announce.appData != null && announce.appData.isNotEmpty()) {
                    existing.appData = announce.appData
                }
                return false
            }

            // New destination
            dests[key] = Destination(
                destHash = announce.destHash.copyOf(),
                signingPub = announce.signingPub.copyOf(),
                encryptionPub = announce.encryptionPub.copyOf(),
                appData = announce.appData?.copyOf(),
                hopCount = announce.hopCount.toInt() and 0xFF,
                sourceIface = sourceIface,
                firstSeen = now,
                lastSeen = now,
                announceCount = 1,
            )

            Log.i(TAG, "New destination: $key hops=${announce.hopCount} source=$sourceIface")
            return true
        }
    }

    /** Look up a destination by its 16-byte hash. */
    fun lookup(destHash: ByteArray): Destination? = lock.read {
        dests[destHash.toHex()]
    }

    /** Look up a destination by hex hash string. */
    fun lookup(destHashHex: String): Destination? = lock.read {
        dests[destHashHex]
    }

    /** Snapshot of all known destinations. */
    fun all(): List<Destination> = lock.read {
        dests.values.toList()
    }

    /** Number of known destinations. */
    fun count(): Int = lock.read {
        dests.size
    }

    /** Remove a destination by hash. */
    fun remove(destHash: ByteArray) = lock.write {
        dests.remove(destHash.toHex())
    }

    companion object {
        private const val TAG = "DestinationTable"
    }
}
