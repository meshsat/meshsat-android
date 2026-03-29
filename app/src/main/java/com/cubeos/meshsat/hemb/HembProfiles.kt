package com.cubeos.meshsat.hemb

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Per-bearer RLNC redundancy factors.
 * Wire-compatible with Go bridge's internal/hemb/profiles.go.
 */
object HembProfiles {

    private val defaultRedundancy = mapOf(
        "mesh" to 1.30,
        "iridium_sbd" to 1.00,
        "iridium_imt" to 1.00,
        "astrocast" to 1.40,
        "cellular" to 1.10,
        "sms" to 1.10,
        "zigbee" to 1.30,
        "aprs" to 1.30,
        "ipougrs" to 2.00,
        "tcp" to 1.00,
        "mqtt" to 1.00,
        "webhook" to 1.00,
        "ble" to 1.20,
    )

    /**
     * Compute global RLNC redundancy factor for a bearer set.
     * Returns R >= 1.0 where N = ceil(K * R).
     *
     * @param priority 0 = critical (P0), 1 = normal (P1), 2+ = best-effort
     */
    fun selectRedundancy(bearers: List<HembBearerProfile>, priority: Int = 2): Double {
        if (bearers.isEmpty()) return 1.0

        // Weighted average loss rate across bearers (weight by MTU).
        var totalWeight = 0.0
        var weightedLoss = 0.0
        for (b in bearers) {
            val w = b.mtu.toDouble()
            totalWeight += w
            weightedLoss += w * b.lossRate
        }
        val avgLoss = if (totalWeight > 0) weightedLoss / totalWeight else 0.0

        // Base redundancy from average loss.
        var baseR = when {
            avgLoss < 0.05 -> 1.10
            avgLoss < 0.15 -> 1.25
            avgLoss < 0.30 -> 1.40
            else -> 1.60
        }

        // Priority boost.
        when (priority) {
            0 -> baseR *= 1.30
            1 -> baseR *= 1.10
        }

        // Cost dampening: if >50% bearers are paid, reduce R.
        val paidCount = bearers.count { !it.isFree }
        val paidFrac = paidCount.toDouble() / bearers.size
        if (paidFrac > 0.5) {
            baseR = max(baseR * 0.85, 1.05)
        }

        return min(baseR, 2.0)
    }

    /** Per-bearer redundancy factor. Paid bearers capped at 1.10. */
    fun bearerRedundancy(b: HembBearerProfile): Double {
        val r = defaultRedundancy[b.channelType] ?: if (b.isFree) 1.30 else 1.05
        return if (!b.isFree) min(r, 1.10) else r
    }

    /**
     * Compute repair symbols for a bearer given its loss rate and source count.
     * Paid bearers capped at 1 repair symbol.
     */
    fun repairSymbols(b: HembBearerProfile, sourceCount: Int): Int {
        if (sourceCount == 0) return 0
        var repair = ceil(sourceCount * b.lossRate * 1.5).toInt()
        if (!b.isFree && repair > 1) repair = 1
        return repair
    }
}
