package com.cubeos.meshsat.hemb

/**
 * Describes a single physical transport bearer for HeMB bonding.
 * Wire-compatible with Go bridge's internal/hemb/bearer.go BearerProfile.
 *
 * Cost is a PRIMARY INPUT to allocation — free bearers are always exhausted
 * before paid bearers receive any symbols.
 */
data class HembBearerProfile(
    val index: Int,
    val interfaceId: String,
    val channelType: String,
    val mtu: Int,
    val costPerMsg: Double = 0.0,
    val lossRate: Double = 0.0,
    val latencyMs: Int = 0,
    val healthScore: Int = 100,
    val sendFn: suspend (ByteArray) -> Unit,
    val relayCapable: Boolean = true,
    val headerMode: String = HembFrame.HEADER_MODE_EXTENDED,
) {
    val isFree: Boolean get() = costPerMsg == 0.0
}

/**
 * Bond group configuration — pushed from Hub or configured locally via QR.
 */
data class HembBondConfig(
    val costBudget: Double = 0.0,
    val minReliability: Double = 0.0,
    val preferFree: Boolean = true,
)

/**
 * Aggregate bonding metrics.
 */
data class HembBondStats(
    val activeStreams: Int = 0,
    val symbolsSent: Long = 0,
    val symbolsReceived: Long = 0,
    val generationsDecoded: Long = 0,
    val generationsFailed: Long = 0,
    val bytesFree: Long = 0,
    val bytesPaid: Long = 0,
    val costIncurred: Double = 0.0,
)
