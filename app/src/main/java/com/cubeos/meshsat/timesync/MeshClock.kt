package com.cubeos.meshsat.timesync

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton synchronized clock with time source hierarchy.
 *
 * Maintains a clock offset from System.currentTimeMillis() based on the
 * best available time source. All engine features (delivery ledger, pass
 * scheduler, dead man's switch, audit log) should use MeshClock.now()
 * instead of raw system time.
 *
 * Source priority: GPS > IridiumMSSTM > HubNTP > MeshConsensus > LocalRTC
 */
object MeshClock {
    private const val TAG = "MeshClock"

    /** Current offset from system clock in milliseconds. */
    @Volatile
    var offsetMs: Long = 0L
        private set

    /** Current best time source. */
    private val _source = AtomicReference<TimeSource>(TimeSource.LocalRTC)
    val source: TimeSource get() = _source.get()

    /** Per-peer clock offsets for mesh consensus (interfaceId → offset). */
    private val peerOffsets = ConcurrentHashMap<String, PeerClockInfo>()

    data class PeerClockInfo(
        val offsetMs: Long,
        val stratum: Int,
        val lastUpdated: Long = System.currentTimeMillis(),
    )

    /** Get corrected current time in epoch milliseconds. */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    /**
     * Update clock from an authoritative source.
     * Only accepts updates from sources with equal or better stratum.
     */
    fun updateFromSource(source: TimeSource, epochMs: Long) {
        val currentSource = _source.get()
        if (source.stratum > currentSource.stratum) {
            // Ignore lower-quality source
            return
        }
        val systemNow = System.currentTimeMillis()
        val newOffset = epochMs - systemNow
        offsetMs = newOffset
        _source.set(source)
        Log.i(TAG, "Clock updated from $source: offset=${newOffset}ms")
    }

    /** Update from GPS fix timestamp. */
    fun updateFromGps(gpsTimeMs: Long) {
        updateFromSource(TimeSource.GPS, gpsTimeMs)
    }

    /** Update from Iridium MSSTM. */
    fun updateFromIridium(msstmEpochMs: Long) {
        updateFromSource(TimeSource.IridiumMSSTM, msstmEpochMs)
    }

    /** Update from Hub NTP via MQTT. */
    fun updateFromHub(ntpEpochMs: Long) {
        updateFromSource(TimeSource.HubNTP, ntpEpochMs)
    }

    /**
     * Record a peer's clock offset (from NTP-style exchange).
     * Recalculates mesh consensus if no better source is available.
     */
    fun updateFromPeer(peerId: String, peerOffsetMs: Long, peerStratum: Int) {
        peerOffsets[peerId] = PeerClockInfo(peerOffsetMs, peerStratum)

        // Only use mesh consensus if current source is worse
        val currentSource = _source.get()
        val bestPeerStratum = peerStratum + 1 // our stratum = peer's + 1
        if (bestPeerStratum < currentSource.stratum) {
            // Weighted average of peer offsets (weight = 1/stratum)
            val validPeers = peerOffsets.values.filter {
                System.currentTimeMillis() - it.lastUpdated < 300_000 // 5 min freshness
            }
            if (validPeers.isNotEmpty()) {
                val weightedSum = validPeers.sumOf { it.offsetMs.toDouble() / it.stratum }
                val weightSum = validPeers.sumOf { 1.0 / it.stratum }
                val consensusOffset = (weightedSum / weightSum).toLong()
                val bestStratum = validPeers.minOf { it.stratum }
                offsetMs = consensusOffset
                _source.set(TimeSource.MeshConsensus(bestStratum))
                Log.d(TAG, "Mesh consensus from ${validPeers.size} peers: offset=${consensusOffset}ms, stratum=${bestStratum + 1}")
            }
        }
    }

    /** Reset to local RTC (no correction). */
    fun reset() {
        offsetMs = 0L
        _source.set(TimeSource.LocalRTC)
        peerOffsets.clear()
    }

    /** Current stratum level. */
    val stratum: Int get() = source.stratum

    /** True if clock is synchronized to an external source (not just local RTC). */
    val isSynced: Boolean get() = source !is TimeSource.LocalRTC
}
