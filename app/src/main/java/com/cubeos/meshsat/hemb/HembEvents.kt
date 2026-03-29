package com.cubeos.meshsat.hemb

/**
 * HeMB observability event types and payloads.
 * Wire-compatible with Go bridge's internal/hemb/events.go.
 */

enum class HembEventType {
    SYMBOL_SENT,
    SYMBOL_RECEIVED,
    GENERATION_DECODED,
    GENERATION_FAILED,
    BEARER_DEGRADED,
    BEARER_RECOVERED,
    STREAM_OPENED,
    STREAM_CLOSED,
    BOND_STATS,
}

data class HembEvent(
    val type: HembEventType,
    val timestampMs: Long = System.currentTimeMillis(),
    val payload: Any,
)

data class BearerRef(
    val bearerIndex: Int,
    val bearerType: String,
)

data class SymbolRef(
    val streamId: Int,
    val generationId: Int,
    val symbolIndex: Int,
)

data class BearerContribution(
    val bearer: BearerRef,
    val symbolCount: Int,
    val firstMs: Long,
    val lastMs: Long,
    val cost: Double,
)

data class SymbolSentPayload(
    val symbol: SymbolRef = SymbolRef(0, 0, 0),
    val bearer: BearerRef,
    val payloadBytes: Int,
    val isRepair: Boolean = false,
    val costEstimate: Double = 0.0,
)

data class SymbolReceivedPayload(
    val symbol: SymbolRef = SymbolRef(0, 0, 0),
    val bearer: BearerRef,
    val payloadBytes: Int,
    val received: Int = 0,
    val required: Int = 0,
)

data class GenerationDecodedPayload(
    val streamId: Int,
    val generationId: Int,
    val k: Int,
    val n: Int,
    val received: Int,
    val decodeTimeUs: Long,
    val payloadBytes: Int,
    val bearers: List<BearerContribution>,
    val costTotal: Double,
)

data class GenerationFailedPayload(
    val streamId: Int,
    val generationId: Int,
    val k: Int,
    val received: Int,
    val reason: String,
    val bearers: List<BearerContribution>,
    val costWasted: Double,
)

data class StreamOpenedPayload(
    val streamId: Int,
    val bearerCount: Int,
    val payloadBytes: Int,
    val generations: Int,
    val k: Int,
    val n: Int,
)

data class BondStatsPayload(
    val activeStreams: Int,
    val symbolsSent: Long,
    val symbolsReceived: Long,
    val generationsDecoded: Long,
    val generationsFailed: Long,
    val bytesFree: Long,
    val bytesPaid: Long,
    val costTotal: Double,
)

/**
 * Non-blocking event emitter. Drops events if listener is null or slow.
 */
typealias HembEventListener = (HembEvent) -> Unit

internal fun emitEvent(listener: HembEventListener?, type: HembEventType, payload: Any) {
    if (listener == null) return
    try {
        listener(HembEvent(type, System.currentTimeMillis(), payload))
    } catch (_: Exception) {
        // Never block data path on observability.
    }
}
