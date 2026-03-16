package com.cubeos.meshsat.dedup

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * O(1) in-memory duplicate detection using composite keys with TTL expiry.
 * Prevents forwarded messages from looping between transports.
 *
 * Port of meshsat/internal/dedup/dedup.go.
 */
class Deduplicator(
    private val ttl: Duration = 10.minutes,
    private val maxSize: Int = 10_000,
) {
    private val lock = ReentrantLock()
    private val seen = LinkedHashMap<String, TimeSource.Monotonic.ValueTimeMark>()
    private val timeSource = TimeSource.Monotonic
    private var prunerJob: Job? = null

    /**
     * Check if a message with the given from+packetID has been seen recently.
     * Returns true if duplicate (should be skipped), false if new (first time seen).
     */
    fun isDuplicate(from: Long, packetId: Long): Boolean {
        val key = "$from:$packetId"
        return isDuplicateKey(key)
    }

    /**
     * Check if an arbitrary string key has been seen recently.
     * Useful for SMS or other transports where the dedup key isn't from+packetID.
     */
    fun isDuplicateKey(key: String): Boolean = lock.withLock {
        if (key in seen) return true

        // Evict oldest if at capacity
        if (seen.size >= maxSize) {
            evictOldest()
        }

        seen[key] = timeSource.markNow()
        false
    }

    /** Number of entries in the cache. */
    val size: Int get() = lock.withLock { seen.size }

    /**
     * Start a background pruner coroutine that removes expired entries.
     * Call [stopPruner] or cancel the scope to stop.
     */
    fun startPruner(scope: CoroutineScope) {
        prunerJob?.cancel()
        prunerJob = scope.launch {
            while (isActive) {
                delay(60.seconds)
                val pruned = prune()
                if (pruned > 0) {
                    Log.d(TAG, "Dedup cache pruned: removed=$pruned remaining=$size")
                }
            }
        }
    }

    /** Stop the background pruner. */
    fun stopPruner() {
        prunerJob?.cancel()
        prunerJob = null
    }

    private fun prune(): Int = lock.withLock {
        val now = timeSource.markNow()
        var pruned = 0
        val iter = seen.iterator()
        while (iter.hasNext()) {
            val (_, mark) = iter.next()
            if (now - mark > ttl) {
                iter.remove()
                pruned++
            }
        }
        pruned
    }

    private fun evictOldest() {
        // LinkedHashMap keeps insertion order — first entry is oldest
        val firstKey = seen.keys.firstOrNull() ?: return
        seen.remove(firstKey)
    }

    companion object {
        private const val TAG = "Deduplicator"
    }
}
