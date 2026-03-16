package com.cubeos.meshsat.routing

import android.util.Log
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
 * Configuration for announce relay behavior.
 */
data class RelayConfig(
    val maxHops: Int = Announce.MAX_HOPS,
    val dedupTtl: Duration = 30.minutes,
    val maxDedupEntries: Int = 10000,
    val minRelayDelay: Duration = 100.milliseconds,
    val maxRelayDelay: Duration = 2.seconds,
)

/**
 * Callback invoked when an announce should be forwarded to interfaces.
 */
fun interface RelayCallback {
    fun onRelay(data: ByteArray, announce: Announce)
}

/**
 * Announce relay: deduplication, hop count enforcement, and delayed
 * retransmission of announce packets across interfaces.
 *
 * Port of Go's routing/relay.go.
 */
class AnnounceRelay(
    private val config: RelayConfig = RelayConfig(),
    private val table: DestinationTable?,
    private val callback: RelayCallback?,
    private val scope: CoroutineScope,
) {
    private val lock = ReentrantLock()
    private val seen = LinkedHashMap<String, Long>(256, 0.75f, true)  // dedupKey -> epochMs
    private val local = ConcurrentHashMap<String, Boolean>()  // destHashHex -> true

    /** Mark a destination hash as local (our own identity). Never relayed. */
    fun registerLocal(destHash: ByteArray) {
        local[destHash.toHex()] = true
    }

    /**
     * Process an incoming announce packet. Deduplicates, validates,
     * updates the destination table, and schedules relay if appropriate.
     * Returns true if the announce was new and valid.
     */
    fun handleAnnounce(data: ByteArray, sourceInterface: String): Boolean {
        val announce = try {
            Announce.unmarshal(data)
        } catch (e: Exception) {
            Log.d(TAG, "Announce unmarshal failed from $sourceInterface: ${e.message}")
            return false
        }

        if (!announce.verify()) {
            Log.w(TAG, "Announce verification failed from $sourceInterface: ${announce.destHash.toHex()}")
            return false
        }

        // Dedup check
        val dedupKey = dedupKey(announce)
        val now = System.currentTimeMillis()
        lock.withLock {
            if (seen.containsKey(dedupKey)) {
                Log.d(TAG, "Duplicate announce: ${announce.destHash.toHex()}")
                return false
            }
            seen[dedupKey] = now
        }

        val isLocal = local.containsKey(announce.destHash.toHex())

        // Update destination table
        table?.update(announce, sourceInterface)

        // Don't relay our own announces
        if (isLocal) {
            Log.d(TAG, "Local identity, not relaying: ${announce.destHash.toHex()}")
            return true
        }

        // Hop count check
        if (announce.hopCount.toInt() and 0xFF >= config.maxHops) {
            Log.d(TAG, "Max hops exceeded: ${announce.destHash.toHex()} hops=${announce.hopCount}")
            return true  // valid but not relayed
        }

        // Schedule relay with random delay
        if (callback != null) {
            scheduleRelay(announce)
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

    /** Number of entries in the dedup cache (for metrics). */
    fun seenCount(): Int = lock.withLock { seen.size }

    private fun scheduleRelay(announce: Announce) {
        val delayRange = config.maxRelayDelay - config.minRelayDelay
        val delayMs = config.minRelayDelay.inWholeMilliseconds +
            Random.nextLong(delayRange.inWholeMilliseconds)

        scope.launch {
            delay(delayMs)

            if (!announce.incrementHop()) return@launch

            val relayData = announce.marshal()
            callback?.onRelay(relayData, announce)
            Log.d(TAG, "Announce relayed: ${announce.destHash.toHex()} hops=${announce.hopCount}")
        }
    }

    private fun prune() {
        lock.withLock {
            val now = System.currentTimeMillis()
            val ttlMs = config.dedupTtl.inWholeMilliseconds
            seen.entries.removeAll { now - it.value > ttlMs }

            // Cap size
            if (seen.size > config.maxDedupEntries) {
                val excess = seen.size - config.maxDedupEntries
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

    private fun dedupKey(announce: Announce): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(announce.destHash)
        digest.update(announce.random)
        return digest.digest().toHex()
    }

    companion object {
        private const val TAG = "AnnounceRelay"
    }
}
