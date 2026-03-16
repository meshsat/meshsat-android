package com.cubeos.meshsat.engine

import android.util.Log
import com.cubeos.meshsat.data.NodePositionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Dead Man's Switch — triggers an SOS callback if no user activity is detected
 * within the configured timeout. Checks every 60 seconds.
 * Port of meshsat/internal/engine/deadman.go.
 */
class DeadManSwitch(
    private val positionDao: NodePositionDao,
    timeout: Duration,
) {
    @Volatile
    private var _timeout: Duration = timeout

    private val lastActive = AtomicLong(System.currentTimeMillis() / 1000)
    private val enabled = AtomicBoolean(false)
    private val triggered = AtomicBoolean(false)

    @Volatile
    var sosCallback: ((lat: Double, lon: Double, lastSeenEpoch: Long) -> Unit)? = null

    private var job: Job? = null

    /**
     * Start the background check loop. Runs every 60 seconds.
     */
    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            Log.i(TAG, "dead man's switch started (timeout=${this@DeadManSwitch._timeout})")
            while (isActive) {
                delay(CHECK_INTERVAL)
                check()
            }
        }
    }

    /** Stop the background check loop. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Reset the activity timer. Call on any user activity (message sent, button press, etc.).
     * Also clears the triggered flag so SOS can fire again after a subsequent timeout.
     */
    fun touch() {
        lastActive.set(System.currentTimeMillis() / 1000)
        triggered.set(false)
    }

    fun setEnabled(value: Boolean) { enabled.set(value) }
    fun isEnabled(): Boolean = enabled.get()
    fun isTriggered(): Boolean = triggered.get()
    fun lastActivity(): Long = lastActive.get()

    fun setTimeout(t: Duration) { _timeout = t }
    fun getTimeout(): Duration = _timeout

    private suspend fun check() {
        if (!enabled.get()) return
        if (triggered.get()) return

        val lastActiveEpoch = lastActive.get()
        val elapsed = (System.currentTimeMillis() / 1000) - lastActiveEpoch
        if (elapsed <= _timeout.inWholeSeconds) return

        triggered.set(true)
        Log.w(TAG, "dead man's switch triggered (last_active=${lastActiveEpoch})")

        var lat = 0.0
        var lon = 0.0
        try {
            val pos = positionDao.getLatest()
            if (pos != null) {
                lat = pos.latitude
                lon = pos.longitude
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to get latest position: ${e.message}")
        }

        sosCallback?.invoke(lat, lon, lastActiveEpoch)
    }

    companion object {
        private const val TAG = "DeadManSwitch"
        private val CHECK_INTERVAL = 60.seconds
    }
}
