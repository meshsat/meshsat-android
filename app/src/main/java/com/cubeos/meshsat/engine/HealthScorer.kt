package com.cubeos.meshsat.engine

import android.util.Log
import com.cubeos.meshsat.channel.ChannelRegistry
import com.cubeos.meshsat.data.MessageDeliveryDao
import com.cubeos.meshsat.data.SignalDao

/**
 * Composite health score for a transport interface.
 * Port of meshsat/internal/engine.HealthScore.
 */
data class HealthScore(
    val interfaceId: String,
    val score: Int = 0,         // 0-100
    val signal: Int = 0,        // 0-100 normalized
    val successRate: Double = 0.0,
    val latencyMs: Int = 0,
    val costScore: Int = 0,     // 100 = free, 0 = expensive
    val available: Boolean = false,
)

/**
 * Computes composite health scores for transport interfaces.
 * Port of meshsat/internal/engine/health_score.go.
 *
 * Composite formula: Signal(0.3) + SuccessRate(0.3) + LatencyScore(0.2) + CostScore(0.2)
 */
class HealthScorer(
    private val interfaceManager: InterfaceManager,
    private val channelRegistry: ChannelRegistry,
    private val signalDao: SignalDao,
    private val deliveryDao: MessageDeliveryDao,
) {
    /**
     * Compute health score for a single interface.
     */
    suspend fun score(interfaceId: String): HealthScore {
        val status = interfaceManager.getAllStatus().find { it.id == interfaceId }
        val available = status?.state == InterfaceState.Online
        val channelType = status?.channelType ?: interfaceId

        val signal = getSignalScore(channelType)
        val successRate = getSuccessRate(interfaceId)
        val latencyMs = getAvgLatency(interfaceId)
        val costScore = channelCostScore(channelType)

        val composite = if (!available) {
            0
        } else {
            val latencyScore = (100 - (latencyMs / 1000).coerceAtMost(100))
            (signal * 0.3 + successRate * 100 * 0.3 + latencyScore * 0.2 + costScore * 0.2).toInt()
        }

        return HealthScore(
            interfaceId = interfaceId,
            score = composite,
            signal = signal,
            successRate = successRate,
            latencyMs = latencyMs,
            costScore = costScore,
            available = available,
        )
    }

    /**
     * Compute health scores for all registered interfaces.
     */
    suspend fun scoreAll(): List<HealthScore> {
        return interfaceManager.getAllStatus().map { score(it.id) }
    }

    private suspend fun getSignalScore(channelType: String): Int {
        return try {
            val record = signalDao.getLatestForSource(channelType) ?: return 0
            // signal_history value is 0-5 bars; normalize to 0-100
            (record.value * 20).coerceIn(0, 100)
        } catch (e: Exception) {
            Log.d(TAG, "signal score lookup failed: ${e.message}")
            0
        }
    }

    private suspend fun getSuccessRate(interfaceId: String): Double {
        return try {
            val since = System.currentTimeMillis() - WINDOW_MS
            val sent = deliveryDao.countSentSince(interfaceId, since)
            val failed = deliveryDao.countFailedSince(interfaceId, since)
            val total = sent + failed
            if (total == 0) 1.0 else sent.toDouble() / total
        } catch (e: Exception) {
            Log.d(TAG, "success rate lookup failed: ${e.message}")
            1.0
        }
    }

    private suspend fun getAvgLatency(interfaceId: String): Int {
        return try {
            val since = System.currentTimeMillis() - WINDOW_MS
            deliveryDao.avgLatencyMsSince(interfaceId, since).toInt()
        } catch (e: Exception) {
            Log.d(TAG, "avg latency lookup failed: ${e.message}")
            0
        }
    }

    companion object {
        private const val TAG = "HealthScorer"
        private const val WINDOW_MS = 24L * 60 * 60 * 1000  // 24 hours

        /**
         * Cost score by channel type. 100 = free, 0 = expensive.
         */
        fun channelCostScore(channelType: String): Int = when (channelType) {
            "mesh", "mqtt", "webhook" -> 100
            "cellular", "sms" -> 60
            "iridium" -> 30
            else -> 50
        }
    }
}
