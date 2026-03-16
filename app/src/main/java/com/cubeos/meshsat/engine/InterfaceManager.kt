package com.cubeos.meshsat.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Interface lifecycle state — port of Go's engine.InterfaceState.
 *
 * State transitions:
 *   Offline ──connect()──→ Connecting ──success──→ Online
 *                                      ──fail────→ Error ──backoff──→ Offline (auto-retry)
 *   Online  ──disconnect/error──→ Offline (auto-retry if enabled)
 *   Any     ──disable()──→ Disabled
 *   Disabled──enable()──→ Offline
 */
enum class InterfaceState {
    /** Interface registered but not attempting connection. */
    Offline,
    /** Transport initializing / connecting. */
    Connecting,
    /** Transport connected, ready to send/receive. */
    Online,
    /** Transport error, awaiting retry backoff. */
    Error,
    /** Interface administratively disabled (no auto-reconnect). */
    Disabled;

    val isAvailable: Boolean get() = this == Online
}

/**
 * Per-interface runtime status snapshot (immutable, safe to share).
 */
data class InterfaceStatus(
    val id: String,
    val channelType: String,
    val state: InterfaceState,
    val error: String = "",
    val lastOnline: Long = 0L,
    val lastActivity: Long = 0L,
    val reconnectAttempts: Int = 0,
)

/**
 * Configuration for one managed interface.
 */
data class InterfaceConfig(
    val id: String,
    val channelType: String,
    val autoReconnect: Boolean = true,
    val initialBackoff: Duration = 5.seconds,
    val maxBackoff: Duration = 120.seconds,
    /** Whether this interface is always considered online (e.g. SMS). */
    val alwaysOnline: Boolean = false,
)

/**
 * Callback to attempt a transport connection. Returns null on success, error message on failure.
 */
fun interface ConnectCallback {
    suspend fun connect(interfaceId: String): String?
}

/**
 * Callback to disconnect a transport.
 */
fun interface DisconnectCallback {
    fun disconnect(interfaceId: String)
}

/**
 * InterfaceManager manages the lifecycle and reconnection of all transport interfaces.
 * Port of Go's engine.InterfaceManager adapted for Android (BLE/SPP/SMS instead of USB serial).
 *
 * Integrates with Dispatcher: state changes trigger hold/unhold of deliveries.
 */
class InterfaceManager(
    private val scope: CoroutineScope,
) {
    private val runtimes = ConcurrentHashMap<String, InterfaceRuntime>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    private var connectCallback: ConnectCallback? = null
    private var disconnectCallback: DisconnectCallback? = null
    private var onStateChange: ((id: String, channelType: String, old: InterfaceState, new: InterfaceState) -> Unit)? = null

    // Aggregated state flow for UI observation
    private val _states = MutableStateFlow<Map<String, InterfaceStatus>>(emptyMap())
    val states: StateFlow<Map<String, InterfaceStatus>> = _states

    fun setConnectCallback(cb: ConnectCallback) { connectCallback = cb }
    fun setDisconnectCallback(cb: DisconnectCallback) { disconnectCallback = cb }

    /**
     * Register a callback that fires on every state transition.
     * Used by Dispatcher to hold/unhold deliveries.
     */
    fun setStateChangeCallback(fn: (id: String, channelType: String, old: InterfaceState, new: InterfaceState) -> Unit) {
        onStateChange = fn
    }

    /** Register an interface for management. */
    fun register(config: InterfaceConfig) {
        val initialState = if (config.alwaysOnline) InterfaceState.Online else InterfaceState.Offline
        val rt = InterfaceRuntime(
            config = config,
            state = initialState,
            lastOnline = if (config.alwaysOnline) System.currentTimeMillis() else 0L,
            lastActivity = if (config.alwaysOnline) System.currentTimeMillis() else 0L,
        )
        runtimes[config.id] = rt
        publishStates()
        Log.i(TAG, "Registered interface ${config.id} (${config.channelType}) state=$initialState")
    }

    /** Unregister an interface. */
    fun unregister(id: String) {
        cancelReconnect(id)
        runtimes.remove(id)
        publishStates()
    }

    /** Get current state for one interface. */
    fun getState(id: String): InterfaceState {
        return runtimes[id]?.state ?: InterfaceState.Offline
    }

    /** Check if an interface is online (implements InterfaceStatusProvider contract). */
    fun isOnline(id: String): Boolean {
        return runtimes[id]?.state == InterfaceState.Online
    }

    /** Get snapshot status for all interfaces. */
    fun getAllStatus(): List<InterfaceStatus> {
        return runtimes.values.map { it.toStatus() }
    }

    /**
     * Mark interface as ONLINE. Called by transport layer when connection succeeds.
     */
    fun setOnline(id: String) {
        val rt = runtimes[id] ?: return
        val old = rt.state
        if (old == InterfaceState.Online) return

        cancelReconnect(id)
        val now = System.currentTimeMillis()
        rt.state = InterfaceState.Online
        rt.lastOnline = now
        rt.lastActivity = now
        rt.errorMsg = ""
        rt.reconnectAttempts = 0

        publishStates()
        Log.i(TAG, "$id → Online (was $old)")
        onStateChange?.invoke(id, rt.config.channelType, old, InterfaceState.Online)
    }

    /**
     * Mark interface as OFFLINE. Called when transport disconnects gracefully.
     * Schedules auto-reconnect if enabled.
     */
    fun setOffline(id: String) {
        val rt = runtimes[id] ?: return
        val old = rt.state
        if (old == InterfaceState.Offline || old == InterfaceState.Disabled) return

        rt.state = InterfaceState.Offline
        rt.errorMsg = ""

        publishStates()
        Log.i(TAG, "$id → Offline (was $old)")
        onStateChange?.invoke(id, rt.config.channelType, old, InterfaceState.Offline)

        if (rt.config.autoReconnect) {
            scheduleReconnect(id, rt)
        }
    }

    /**
     * Mark interface as ERROR with a reason. Schedules auto-reconnect with backoff.
     */
    fun setError(id: String, error: String) {
        val rt = runtimes[id] ?: return
        val old = rt.state

        rt.state = InterfaceState.Error
        rt.errorMsg = error

        publishStates()
        Log.w(TAG, "$id → Error: $error (was $old)")

        if (old != InterfaceState.Error) {
            onStateChange?.invoke(id, rt.config.channelType, old, InterfaceState.Error)
        }

        if (rt.config.autoReconnect) {
            scheduleReconnect(id, rt)
        }
    }

    /**
     * Mark interface as CONNECTING. Called when a connect attempt starts.
     */
    fun setConnecting(id: String) {
        val rt = runtimes[id] ?: return
        val old = rt.state
        if (old == InterfaceState.Connecting) return

        rt.state = InterfaceState.Connecting
        publishStates()
        Log.d(TAG, "$id → Connecting (was $old)")
    }

    /**
     * Administratively disable an interface (stop auto-reconnect).
     */
    fun disable(id: String) {
        val rt = runtimes[id] ?: return
        val old = rt.state
        cancelReconnect(id)

        if (old == InterfaceState.Online) {
            disconnectCallback?.disconnect(id)
        }
        rt.state = InterfaceState.Disabled
        rt.reconnectAttempts = 0

        publishStates()
        Log.i(TAG, "$id → Disabled (was $old)")
        onStateChange?.invoke(id, rt.config.channelType, old, InterfaceState.Disabled)
    }

    /**
     * Re-enable a disabled interface (will attempt connection).
     */
    fun enable(id: String) {
        val rt = runtimes[id] ?: return
        if (rt.state != InterfaceState.Disabled) return

        rt.state = InterfaceState.Offline
        rt.reconnectAttempts = 0
        rt.errorMsg = ""

        publishStates()
        Log.i(TAG, "$id → Offline (re-enabled)")
        onStateChange?.invoke(id, rt.config.channelType, InterfaceState.Disabled, InterfaceState.Offline)

        if (rt.config.autoReconnect) {
            scheduleReconnect(id, rt)
        }
    }

    /** Record activity timestamp (e.g. message received/sent). */
    fun recordActivity(id: String) {
        runtimes[id]?.let { it.lastActivity = System.currentTimeMillis() }
    }

    /** Trigger an immediate reconnect attempt for an interface. */
    fun reconnectNow(id: String) {
        val rt = runtimes[id] ?: return
        if (rt.state == InterfaceState.Disabled || rt.state == InterfaceState.Online) return

        cancelReconnect(id)
        rt.reconnectAttempts = 0
        scheduleReconnect(id, rt, immediate = true)
    }

    /** Stop all reconnect jobs and set all interfaces offline. */
    fun stopAll() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()

        for ((id, rt) in runtimes) {
            val old = rt.state
            if (old == InterfaceState.Online || old == InterfaceState.Connecting) {
                rt.state = InterfaceState.Offline
                onStateChange?.invoke(id, rt.config.channelType, old, InterfaceState.Offline)
            }
        }
        publishStates()
    }

    // --- Reconnect logic ---

    private fun scheduleReconnect(id: String, rt: InterfaceRuntime, immediate: Boolean = false) {
        cancelReconnect(id)

        val backoff = if (immediate) {
            Duration.ZERO
        } else {
            calculateBackoff(rt.reconnectAttempts, rt.config.initialBackoff, rt.config.maxBackoff)
        }

        val job = scope.launch {
            if (backoff > Duration.ZERO) {
                Log.d(TAG, "$id reconnect in ${backoff.inWholeSeconds}s (attempt ${rt.reconnectAttempts + 1})")
                delay(backoff)
            }

            if (!isActive) return@launch
            if (rt.state == InterfaceState.Disabled) return@launch

            rt.reconnectAttempts++
            rt.state = InterfaceState.Connecting
            publishStates()

            val cb = connectCallback
            if (cb == null) {
                rt.state = InterfaceState.Error
                rt.errorMsg = "no connect callback"
                publishStates()
                return@launch
            }

            val error = cb.connect(id)
            if (error == null) {
                // Success — setOnline() will be called by the transport layer
                // (we don't set Online here because the transport confirms it asynchronously)
                Log.d(TAG, "$id reconnect attempt succeeded")
            } else {
                rt.state = InterfaceState.Error
                rt.errorMsg = error
                publishStates()
                Log.w(TAG, "$id reconnect failed: $error")

                // Schedule next attempt
                if (rt.config.autoReconnect && rt.state != InterfaceState.Disabled) {
                    scheduleReconnect(id, rt)
                }
            }
        }
        reconnectJobs[id] = job
    }

    private fun cancelReconnect(id: String) {
        reconnectJobs.remove(id)?.cancel()
    }

    private fun publishStates() {
        _states.value = runtimes.mapValues { (_, rt) -> rt.toStatus() }
    }

    companion object {
        private const val TAG = "InterfaceMgr"

        /** Exponential backoff capped at maxBackoff. */
        fun calculateBackoff(attempt: Int, initial: Duration, max: Duration): Duration {
            if (attempt <= 0) return initial
            var wait = initial
            repeat(attempt.coerceAtMost(10)) { wait *= 2 }
            return if (wait > max) max else wait
        }
    }
}

/**
 * Mutable runtime state for one interface (internal to InterfaceManager).
 */
private class InterfaceRuntime(
    val config: InterfaceConfig,
    @Volatile var state: InterfaceState,
    @Volatile var errorMsg: String = "",
    @Volatile var lastOnline: Long = 0L,
    @Volatile var lastActivity: Long = 0L,
    @Volatile var reconnectAttempts: Int = 0,
) {
    fun toStatus() = InterfaceStatus(
        id = config.id,
        channelType = config.channelType,
        state = state,
        error = errorMsg,
        lastOnline = lastOnline,
        lastActivity = lastActivity,
        reconnectAttempts = reconnectAttempts,
    )
}
