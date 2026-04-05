package com.cubeos.meshsat.satellite

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pass-aware satellite scheduling — adapts transport polling behavior based
 * on predicted Iridium satellite pass windows.
 *
 * 4 modes:
 * - Idle: No pass expected soon. Conservative polling (2min).
 * - PreWake: Pass starts within 3 minutes. Ramp up polling (15s).
 * - Active: Satellite overhead (AOS ≤ now ≤ LOS). Aggressive polling (5s), burst flush.
 * - PostPass: Grace period after LOS (2 min). Still elevated polling (30s).
 */
class PassScheduler(
    private val passProvider: () -> List<PassPrediction>,
    private val signalPoller: (suspend () -> Unit)? = null,
    private val burstFlusher: (suspend () -> Unit)? = null,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "PassScheduler"
        const val MODE_CHECK_INTERVAL_MS = 30_000L // re-evaluate mode every 30s (coroutine delay — ms)
        // Window constants are in SECONDS to match PassPrediction.aosUnix/losUnix unit (MESHSAT-498)
        const val PRE_WAKE_WINDOW_SEC = 3 * 60L // 3 minutes before AOS
        const val POST_PASS_WINDOW_SEC = 2 * 60L // 2 minutes after LOS
    }

    enum class PassMode {
        Idle,     // no pass expected soon
        PreWake,  // pass starts within PRE_WAKE_WINDOW_MS
        Active,   // satellite overhead
        PostPass, // grace period after LOS
    }

    data class TimingParams(
        val signalPollIntervalMs: Long,
        val burstFlushOnEntry: Boolean,
    )

    private val _mode = MutableStateFlow(PassMode.Idle)
    val mode: StateFlow<PassMode> = _mode

    private val _nextPassAos = MutableStateFlow<Long?>(null)
    val nextPassAos: StateFlow<Long?> = _nextPassAos

    private val _nextPassLos = MutableStateFlow<Long?>(null)
    val nextPassLos: StateFlow<Long?> = _nextPassLos

    private var modeJob: Job? = null
    private var pollJob: Job? = null

    fun timingForMode(mode: PassMode): TimingParams = when (mode) {
        PassMode.Idle -> TimingParams(signalPollIntervalMs = 120_000, burstFlushOnEntry = false)
        PassMode.PreWake -> TimingParams(signalPollIntervalMs = 15_000, burstFlushOnEntry = false)
        PassMode.Active -> TimingParams(signalPollIntervalMs = 5_000, burstFlushOnEntry = true)
        PassMode.PostPass -> TimingParams(signalPollIntervalMs = 30_000, burstFlushOnEntry = true)
    }

    fun start() {
        modeJob = scope.launch {
            Log.i(TAG, "Pass scheduler started")
            while (isActive) {
                updateMode()
                delay(MODE_CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        modeJob?.cancel()
        pollJob?.cancel()
        _mode.value = PassMode.Idle
        Log.i(TAG, "Pass scheduler stopped")
    }

    private suspend fun updateMode() {
        // PassPrediction.aosUnix/losUnix are unix SECONDS (MESHSAT-498).
        val now = System.currentTimeMillis() / 1000
        val passes = try { passProvider() } catch (e: Exception) {
            Log.w(TAG, "Failed to get passes: ${e.message}")
            emptyList()
        }

        // Find the current or next upcoming pass
        val activePasses = passes.filter { now in it.aosUnix..it.losUnix }
        val nextPass = passes.filter { it.aosUnix > now }.minByOrNull { it.aosUnix }

        val oldMode = _mode.value
        val newMode = when {
            activePasses.isNotEmpty() -> {
                _nextPassAos.value = activePasses.first().aosUnix
                _nextPassLos.value = activePasses.first().losUnix
                PassMode.Active
            }
            // Check if we're in post-pass window (just ended)
            passes.any { now > it.losUnix && now - it.losUnix < POST_PASS_WINDOW_SEC } -> {
                PassMode.PostPass
            }
            // Check if next pass is within pre-wake window
            nextPass != null && (nextPass.aosUnix - now) < PRE_WAKE_WINDOW_SEC -> {
                _nextPassAos.value = nextPass.aosUnix
                _nextPassLos.value = nextPass.losUnix
                PassMode.PreWake
            }
            else -> {
                _nextPassAos.value = nextPass?.aosUnix
                _nextPassLos.value = nextPass?.losUnix
                PassMode.Idle
            }
        }

        if (newMode != oldMode) {
            _mode.value = newMode
            Log.i(TAG, "Mode transition: $oldMode -> $newMode")
            onModeChange(oldMode, newMode)
        }
    }

    private suspend fun onModeChange(old: PassMode, new: PassMode) {
        val timing = timingForMode(new)

        // Flush burst queue on entering Active or PostPass
        if (timing.burstFlushOnEntry && old != PassMode.Active && old != PassMode.PostPass) {
            try {
                burstFlusher?.invoke()
                Log.i(TAG, "Burst queue flushed on $new entry")
            } catch (e: Exception) {
                Log.w(TAG, "Burst flush failed: ${e.message}")
            }
        }

        // Restart poll loop with new interval
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    signalPoller?.invoke()
                } catch (e: Exception) {
                    Log.w(TAG, "Signal poll failed: ${e.message}")
                }
                delay(timing.signalPollIntervalMs)
            }
        }
    }
}
