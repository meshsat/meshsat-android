package com.cubeos.meshsat.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-interface monotonic sequence counter.
 * Port of Go's database.IncrementEgressSeq / IncrementIngressSeq.
 *
 * Each interface gets independent ingress and egress counters.
 * Counters are in-memory (reset on app restart) — sufficient for
 * session-level ordering and ACK correlation. Persistent counters
 * would require a DB table; this matches the Go pattern where seq_num
 * is tracked in the interfaces table but the Android app has no
 * equivalent interfaces table yet.
 */
class SequenceTracker {

    private val egressCounters = ConcurrentHashMap<String, AtomicLong>()
    private val ingressCounters = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Get the next egress sequence number for an interface.
     * Monotonically increasing, starts at 1.
     */
    fun nextEgressSeq(interfaceId: String): Long {
        return egressCounters
            .getOrPut(interfaceId) { AtomicLong(0) }
            .incrementAndGet()
    }

    /**
     * Get the next ingress sequence number for an interface.
     * Monotonically increasing, starts at 1.
     */
    fun nextIngressSeq(interfaceId: String): Long {
        return ingressCounters
            .getOrPut(interfaceId) { AtomicLong(0) }
            .incrementAndGet()
    }

    /** Current egress sequence (last assigned, 0 if none). */
    fun currentEgressSeq(interfaceId: String): Long {
        return egressCounters[interfaceId]?.get() ?: 0
    }

    /** Current ingress sequence (last assigned, 0 if none). */
    fun currentIngressSeq(interfaceId: String): Long {
        return ingressCounters[interfaceId]?.get() ?: 0
    }

    /** Reset counters for an interface (e.g. on reconnect). */
    fun reset(interfaceId: String) {
        egressCounters.remove(interfaceId)
        ingressCounters.remove(interfaceId)
    }

    /** Reset all counters. */
    fun resetAll() {
        egressCounters.clear()
        ingressCounters.clear()
    }
}
