package com.cubeos.meshsat.reticulum

import com.cubeos.meshsat.routing.toHex
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Forwarding table for Reticulum Transport Node functionality.
 *
 * Stores forwarding entries learned from announces and path responses,
 * enabling cross-interface relay of packets destined for other nodes.
 *
 * Each destination may have multiple forwarding entries (one per
 * ingress interface). The best entry is selected by score (cost-aware).
 *
 * [MESHSAT-199]
 */
class RnsForwardingTable(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    companion object {
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L  // 30 minutes
        const val MAX_ENTRIES = 10_000
    }

    data class Entry(
        val destHash: ByteArray,        // 16B target destination
        val nextHop: ByteArray?,        // 16B next hop (null = direct)
        val egressInterface: String,    // interface to forward on
        val hops: Int,                  // hop count from announce
        val costCents: Int,             // interface cost
        val createdAt: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
        var expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    ) {
        val destHashHex: String get() = destHash.toHex()
        val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt

        /** Lower is better: cost × 1000 + hops × 100. */
        val score: Int get() = costCents * 1000 + hops * 100

        override fun equals(other: Any?): Boolean =
            other is Entry && destHash.contentEquals(other.destHash) && egressInterface == other.egressInterface

        override fun hashCode(): Int = destHash.contentHashCode() xor egressInterface.hashCode()
    }

    private val lock = ReentrantReadWriteLock()
    // destHashHex → list of entries (one per egress interface)
    private val entries = HashMap<String, MutableList<Entry>>()

    /**
     * Learn a forwarding path from an announce or path response.
     *
     * @param destHash 16-byte destination hash
     * @param nextHop 16-byte next hop (null = direct)
     * @param egressInterface ID of the interface this path was learned from
     * @param hops hop count from announce
     * @param costCents cost of the egress interface
     * @return true if this is a new destination (first path learned)
     */
    fun learn(
        destHash: ByteArray,
        nextHop: ByteArray?,
        egressInterface: String,
        hops: Int,
        costCents: Int = 0,
    ): Boolean {
        val key = destHash.toHex()
        return lock.write {
            val list = entries.getOrPut(key) { mutableListOf() }
            val existing = list.find { it.egressInterface == egressInterface }
            val isNew = list.isEmpty()

            if (existing != null) {
                // Update: prefer lower hop count
                if (hops <= existing.hops) {
                    list.remove(existing)
                    list.add(Entry(
                        destHash = destHash.copyOf(),
                        nextHop = nextHop?.copyOf(),
                        egressInterface = egressInterface,
                        hops = hops,
                        costCents = costCents,
                        expiresAt = System.currentTimeMillis() + ttlMs,
                    ))
                } else {
                    existing.lastSeen = System.currentTimeMillis()
                    existing.expiresAt = System.currentTimeMillis() + ttlMs
                }
            } else {
                list.add(Entry(
                    destHash = destHash.copyOf(),
                    nextHop = nextHop?.copyOf(),
                    egressInterface = egressInterface,
                    hops = hops,
                    costCents = costCents,
                    expiresAt = System.currentTimeMillis() + ttlMs,
                ))
            }

            // Cap total entries
            if (entries.size > MAX_ENTRIES) {
                pruneOldest()
            }

            isNew
        }
    }

    /**
     * Look up the best forwarding entry for a destination.
     * Returns null if no path is known.
     */
    fun lookup(destHash: ByteArray): Entry? {
        val key = destHash.toHex()
        return lock.read {
            entries[key]
                ?.filter { !it.isExpired }
                ?.minByOrNull { it.score }
        }
    }

    /**
     * Get all non-expired entries for a destination, sorted by score.
     */
    fun allEntries(destHash: ByteArray): List<Entry> {
        val key = destHash.toHex()
        return lock.read {
            entries[key]
                ?.filter { !it.isExpired }
                ?.sortedBy { it.score }
                ?: emptyList()
        }
    }

    /** Check if a destination has any forwarding entries. */
    fun hasEntry(destHash: ByteArray): Boolean {
        val key = destHash.toHex()
        return lock.read {
            entries[key]?.any { !it.isExpired } == true
        }
    }

    /** Remove all entries for a destination. */
    fun remove(destHash: ByteArray) {
        val key = destHash.toHex()
        lock.write { entries.remove(key) }
    }

    /** Remove all entries for an interface (e.g., when interface goes down permanently). */
    fun removeInterface(interfaceId: String) {
        lock.write {
            entries.values.forEach { list ->
                list.removeAll { it.egressInterface == interfaceId }
            }
            entries.entries.removeAll { it.value.isEmpty() }
        }
    }

    /** Remove expired entries. Call periodically. */
    fun prune(): Int {
        return lock.write {
            var removed = 0
            entries.values.forEach { list ->
                val before = list.size
                list.removeAll { it.isExpired }
                removed += before - list.size
            }
            entries.entries.removeAll { it.value.isEmpty() }
            removed
        }
    }

    /** Total number of destinations with forwarding entries. */
    fun size(): Int = lock.read { entries.size }

    /** Total number of forwarding entries across all destinations. */
    fun totalEntries(): Int = lock.read { entries.values.sumOf { it.size } }

    /** Snapshot of all destinations → best entry. */
    fun snapshot(): Map<String, Entry> {
        return lock.read {
            entries.mapNotNull { (key, list) ->
                val best = list.filter { !it.isExpired }.minByOrNull { it.score }
                if (best != null) key to best else null
            }.toMap()
        }
    }

    private fun pruneOldest() {
        // Remove entries with oldest lastSeen
        val all = entries.values.flatten().sortedBy { it.lastSeen }
        val toRemove = all.take(entries.size / 4) // remove 25%
        toRemove.forEach { entry ->
            val key = entry.destHashHex
            entries[key]?.remove(entry)
            if (entries[key]?.isEmpty() == true) entries.remove(key)
        }
    }
}
