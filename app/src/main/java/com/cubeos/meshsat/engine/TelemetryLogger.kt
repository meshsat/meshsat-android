package com.cubeos.meshsat.engine

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import com.cubeos.meshsat.BuildConfig
import com.cubeos.meshsat.data.TelemetryDao
import com.cubeos.meshsat.data.TelemetryEntity
import com.cubeos.meshsat.hub.BirthSigner
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Release telemetry pipeline for MeshSat Android (MESHSAT-494).
 *
 * Writes crash records, heap samples, health heartbeats, and explicit events
 * into the [com.cubeos.meshsat.data.TelemetryEntity] ring buffer. Everything
 * stays on-device — contents are retrievable via the Local REST API on
 * `localhost:6051` and cleared via `DELETE /api/telemetry`.
 *
 * ## Crash handling
 *
 * Writing to Room during an uncaught exception is not safe — the process may
 * die before the write completes, the thread running the write may be the one
 * that's crashing, and coroutines may be in an inconsistent state. Instead we
 * follow the ACRA pattern:
 *
 * 1. The uncaught exception handler writes a single JSON file to `filesDir`
 *    ([crashDumpFile]) synchronously, then chains to the system's default
 *    handler so Android still shows its crash dialog / restarts the service.
 * 2. On the next startup, [recoverPendingCrashes] reads the file, inserts a
 *    row into the telemetry table, and deletes the file. This happens BEFORE
 *    any other telemetry is written, so the crash is always captured even if
 *    the migration path itself has issues.
 *
 * ## Retention
 *
 * Each type has its own cap enforced by [maybeTrim] after N writes. The caps
 * are chosen so that even on a worst-case boot loop the DB stays under ~1 MB.
 *
 * ## Opt-out
 *
 * All write methods honor the `telemetryEnabled` preference via
 * [TelemetryLogger.enabledProvider]. When the provider returns false, writes
 * are no-ops. Crash recovery (file → DB) also checks the preference; if the
 * user has disabled telemetry, a pending crash file is deleted without being
 * persisted to the DB.
 */
class TelemetryLogger(
    private val dao: TelemetryDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    /** Returns `true` if telemetry writes should be persisted. */
    private val enabledProvider: suspend () -> Boolean = { true },
) {

    companion object {
        private const val TAG = "TelemetryLogger"

        // Retention caps — enforced by trimType() after inserts
        const val MAX_CRASHES = 50
        const val MAX_HEAP_SAMPLES = 288   // 24 hours at 5-min interval
        const val MAX_HEALTH_SAMPLES = 1440 // 24 hours at 1-min interval
        const val MAX_EVENTS = 1000

        const val TYPE_CRASH = "crash"
        const val TYPE_HEAP = "heap"
        const val TYPE_HEALTH = "health"
        const val TYPE_EVENT = "event"

        const val SEV_FATAL = "fatal"
        const val SEV_WARN = "warn"
        const val SEV_INFO = "info"
        const val SEV_SAMPLE = "sample"

        /** Relative path (under `filesDir`) where the crash handler writes its JSON dump. */
        const val CRASH_DUMP_RELATIVE = "pending_crash.json"

        /** Resolve the crash dump file for the given context. */
        fun crashDumpFile(context: Context): File =
            File(context.filesDir, CRASH_DUMP_RELATIVE)

        /**
         * Install a JVM-level uncaught exception handler that writes a single
         * JSON file describing the crash to `filesDir/pending_crash.json`, then
         * chains to the previous handler. Call once from `Application.onCreate`.
         *
         * This intentionally does NOT touch Room, DataStore, or coroutines —
         * the process may be in a terminal state and any of those could block
         * or throw. File I/O is the only operation guaranteed to complete.
         */
        fun installCrashHandler(context: Context) {
            val dumpFile = crashDumpFile(context.applicationContext)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val stack = StringWriter().also {
                        throwable.printStackTrace(PrintWriter(it))
                    }.toString()
                    val payload = BirthSigner.toCanonicalJson(mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "thread" to thread.name,
                        "exception" to throwable.javaClass.name,
                        "message" to (throwable.message ?: ""),
                        "stack" to stack,
                        "versionCode" to BuildConfig.VERSION_CODE,
                        "versionName" to BuildConfig.VERSION_NAME,
                        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "osVersion" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    ))
                    dumpFile.writeText(payload)
                    Log.e(TAG, "Uncaught exception captured to ${dumpFile.name}: ${throwable.message}")
                } catch (_: Throwable) {
                    // Best effort — do not let a telemetry failure mask the original crash
                }
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Recover a pending crash file written by the uncaught exception handler
     * on a previous launch, insert it into the telemetry table, and delete
     * the file. Idempotent and safe to call on every startup.
     */
    suspend fun recoverPendingCrashes(context: Context) {
        val file = crashDumpFile(context)
        if (!file.exists()) return
        try {
            val text = file.readText()
            // Parse minimal fields we need for the summary message without using JSONObject
            // (which returns null in JVM tests). BirthSigner's canonical JSON format is
            // deterministic, so simple regex extraction works reliably.
            val exception = extractJsonString(text, "exception")
            val message = extractJsonString(text, "message")
            val ts = extractJsonLong(text, "timestamp") ?: System.currentTimeMillis()

            val enabled = try { enabledProvider() } catch (_: Throwable) { true }
            if (enabled) {
                dao.insert(
                    TelemetryEntity(
                        timestamp = ts,
                        type = TYPE_CRASH,
                        tag = "UncaughtExceptionHandler",
                        severity = SEV_FATAL,
                        message = "$exception: ${message.take(120)}",
                        detail = text,
                    ),
                )
                dao.trimType(TYPE_CRASH, MAX_CRASHES)
                Log.i(TAG, "Recovered pending crash: $exception")
            } else {
                Log.d(TAG, "Telemetry disabled — discarding pending crash file without persisting")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to recover pending crash: ${e.message}")
        } finally {
            try { file.delete() } catch (_: Throwable) {}
        }
    }

    private fun extractJsonString(json: String, key: String): String {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\n", "\n")
            ?: ""
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    // --- Heap sampling ---

    /** Capture a heap snapshot. Call periodically (e.g. every 5 min) from a background scope. */
    fun recordHeap() = fireAndForget(TYPE_HEAP) {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()

        // Android Debug APIs return mock defaults in JVM unit tests — wrap in try/catch
        // so tests still exercise the code path even without Robolectric.
        val nativeAllocated = try { Debug.getNativeHeapAllocatedSize() } catch (_: Throwable) { 0L }
        val nativeSize = try { Debug.getNativeHeapSize() } catch (_: Throwable) { 0L }
        val nativeFree = try { Debug.getNativeHeapFreeSize() } catch (_: Throwable) { 0L }
        val pssBytes = try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            mi.totalPss.toLong() * 1024
        } catch (_: Throwable) { 0L }

        val detail = BirthSigner.toCanonicalJson(mapOf(
            "dalvikUsed" to used,
            "dalvikTotal" to runtime.totalMemory(),
            "dalvikMax" to runtime.maxMemory(),
            "dalvikFree" to runtime.freeMemory(),
            "nativeHeapSize" to nativeSize,
            "nativeHeapAllocated" to nativeAllocated,
            "nativeHeapFree" to nativeFree,
            "totalPss" to pssBytes,
        ))
        val usedMb = used / 1_048_576
        val maxMb = runtime.maxMemory() / 1_048_576
        TelemetryEntity(
            timestamp = System.currentTimeMillis(),
            type = TYPE_HEAP,
            tag = "HeapSampler",
            severity = SEV_SAMPLE,
            message = "Dalvik ${usedMb}/${maxMb} MB, native ${nativeAllocated / 1_048_576} MB",
            detail = detail,
        )
    }

    // --- Health heartbeats ---

    /** Record a health snapshot. Caller supplies key/value pairs that are JSON-encoded. */
    fun recordHealth(message: String, detail: Map<String, Any?>) = fireAndForget(TYPE_HEALTH) {
        TelemetryEntity(
            timestamp = System.currentTimeMillis(),
            type = TYPE_HEALTH,
            tag = "HealthSampler",
            severity = SEV_SAMPLE,
            message = message,
            detail = BirthSigner.toCanonicalJson(detail),
        )
    }

    // --- Explicit events ---

    /**
     * Record a notable event (mode transition, key import, signature verification,
     * burst flush, etc.). Severity defaults to [SEV_INFO]; use [SEV_WARN] for
     * recoverable problems that would be valuable to see in the release log.
     */
    fun recordEvent(
        tag: String,
        message: String,
        detail: Map<String, Any?> = emptyMap(),
        severity: String = SEV_INFO,
    ) = fireAndForget(TYPE_EVENT) {
        TelemetryEntity(
            timestamp = System.currentTimeMillis(),
            type = TYPE_EVENT,
            tag = tag,
            severity = severity,
            message = message,
            detail = BirthSigner.toCanonicalJson(detail),
        )
    }

    // --- Internal ---

    private fun fireAndForget(type: String, build: () -> TelemetryEntity) {
        scope.launch {
            try {
                val enabled = try { enabledProvider() } catch (_: Throwable) { true }
                if (!enabled) return@launch
                dao.insert(build())
                // Trim after every insert — cheap (indexed), bounded size
                val keep = when (type) {
                    TYPE_CRASH -> MAX_CRASHES
                    TYPE_HEAP -> MAX_HEAP_SAMPLES
                    TYPE_HEALTH -> MAX_HEALTH_SAMPLES
                    TYPE_EVENT -> MAX_EVENTS
                    else -> 1000
                }
                dao.trimType(type, keep)
            } catch (e: Throwable) {
                Log.w(TAG, "Telemetry write failed ($type): ${e.message}")
            }
        }
    }

}
