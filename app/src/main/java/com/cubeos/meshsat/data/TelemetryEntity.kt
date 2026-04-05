package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Release telemetry ring buffer (MESHSAT-494).
 *
 * A single table stores all telemetry types (crashes, heap samples, health
 * heartbeats, and explicit events) discriminated by `type`. Retention is
 * bounded per-type via the DAO's trim queries to prevent unbounded growth.
 *
 * Contents are queryable via the Local REST API (endpoints under
 * `/api/telemetry`) on `localhost:6051` so a user can pull diagnostics
 * without USB/logcat access, and cleared via `DELETE /api/telemetry` for
 * privacy.
 *
 * The master switch `telemetryEnabled` in [SettingsRepository] gates all
 * writes; when disabled no rows are inserted regardless of the call site.
 */
@Entity(
    tableName = "telemetry",
    indices = [
        Index("timestamp"),
        Index("type"),
    ],
)
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Unix milliseconds when the event occurred. */
    val timestamp: Long,

    /** Discriminator: `crash`, `heap`, `health`, or `event`. */
    val type: String,

    /** Source tag for grouping (e.g. "GatewayService", "KeyBundleImporter", "PassScheduler"). */
    val tag: String,

    /**
     * Severity: `fatal` (crash), `warn` (degraded state), `info` (mode transitions, key events),
     * `sample` (periodic health/heap snapshots).
     */
    val severity: String,

    /** Short human-readable one-line summary. */
    val message: String,

    /**
     * JSON payload with type-specific details:
     * - crash: `{thread, exception, stack, versionCode, versionName, deviceModel, osVersion}`
     * - heap: `{dalvikUsed, dalvikFree, nativeHeap, pssTotal, runtimeMax}`
     * - health: `{transports, passMode, lastPassElapsedMs, foregroundServiceAlive, ...}`
     * - event: whatever the caller attaches
     */
    val detail: String,
)
