package net.meshsat.android.hub.relay

import android.util.Log
import net.meshsat.android.reticulum.RnsConstants
import net.meshsat.android.reticulum.RnsInterface
import net.meshsat.android.reticulum.RnsReceiveCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reticulum interface over a Hub relay tunnel to one bridge (MESHSAT-1157).
 *
 * The fallback below LAN and RNS TCP: when the kit cannot be reached directly, the
 * phone opens a relay tunnel to it through the Hub and carries Reticulum packets as
 * bare frames. Each packet is one frame ([RelayTunnel] frames mode), so nothing has
 * to be re-framed on the far side.
 *
 * Reconnects by itself. The Hub's budget is 100 frames per calendar minute per client
 * id, counting connect attempts, so a `1008` (budget) or an HTTP 429 waits out the
 * minute; a 401/403 is a configuration problem and is retried slowly.
 */
class RelayBridgeTransport(
    private val scope: CoroutineScope,
    private val config: Config,
    private val tunnelFactory: ((ByteArray) -> Unit) -> RelayTunnel = { onFrame ->
        RelayTunnel(
            hubBaseUrl = config.hubApiBase,
            targetBridgeId = config.targetBridgeId,
            ownBridgeId = config.ownBridgeId,
            password = config.password,
            frameListener = onFrame,
            log = { Log.d(TAG, it) },
        )
    },
    override val interfaceId: String = INTERFACE_ID,
) : RnsInterface {

    companion object {
        private const val TAG = "RelayBridgeTransport"
        const val INTERFACE_ID = "hub_relay"

        const val RETRY_MIN_MS = 5_000L
        const val RETRY_MAX_MS = 60_000L
        /** A spent budget resets on the calendar minute; wait a whole one plus slack. */
        const val RETRY_BUDGET_MS = 65_000L
        /** Bad credentials or a bridge that is not ours: someone has to fix settings. */
        const val RETRY_REFUSED_MS = 300_000L
    }

    data class Config(
        val hubApiBase: String,
        val targetBridgeId: String,
        val ownBridgeId: String,
        val password: String,
    )

    override val name: String = "Hub relay"
    override val mtu: Int = RnsConstants.MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 300
    override val isBidirectional: Boolean = true

    private val _state = MutableStateFlow<RelayTunnel.RelayState>(
        RelayTunnel.RelayState.Closed(RelayTunnel.CloseReason.Local, "not started"),
    )

    /** The current tunnel's state; drives the InterfaceManager entry for [interfaceId]. */
    val state: StateFlow<RelayTunnel.RelayState> = _state

    override val isOnline: Boolean get() = _state.value is RelayTunnel.RelayState.Open

    @Volatile private var receiveCallback: RnsReceiveCallback? = null
    @Volatile private var tunnel: RelayTunnel? = null
    @Volatile private var running = false
    private var loop: Job? = null

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    override suspend fun start() {
        if (running) return
        running = true
        // Connecting before the loop is scheduled, so nobody reads the pre-start Closed.
        _state.value = RelayTunnel.RelayState.Connecting
        loop = scope.launch(Dispatchers.IO) { connectionLoop() }
    }

    override suspend fun stop() = shutdown()

    /** Non-suspending stop for service teardown. */
    fun shutdown() {
        running = false
        loop?.cancel()
        loop = null
        tunnel?.close()
        tunnel = null
        _state.value = RelayTunnel.RelayState.Closed(RelayTunnel.CloseReason.Local, "stopped")
    }

    override suspend fun send(packet: ByteArray): String? {
        if (packet.size > RelayTunnel.MAX_FRAME) return "hub relay: packet larger than a frame"
        val t = tunnel ?: return "hub relay offline"
        if (!t.isOpen) return "hub relay offline"
        return if (t.sendFrame(packet)) null else "hub relay send failed"
    }

    private suspend fun connectionLoop() {
        var backoff = RETRY_MIN_MS
        while (running && scope.isActive) {
            val t = tunnelFactory { frame ->
                receiveCallback?.onReceive(interfaceId, frame)
            }
            tunnel = t
            _state.value = RelayTunnel.RelayState.Connecting
            t.onState = { s -> _state.value = s }
            t.open()

            // Wait for the tunnel to end, whichever way.
            val end = t.state.first { it is RelayTunnel.RelayState.Closed || it is RelayTunnel.RelayState.Refused }
            _state.value = end
            tunnel = null
            if (!running) break

            val wait = when (end) {
                is RelayTunnel.RelayState.Refused -> when (end.httpCode) {
                    429 -> RETRY_BUDGET_MS
                    401, 403, 400 -> RETRY_REFUSED_MS
                    else -> backoff
                }
                is RelayTunnel.RelayState.Closed -> when (end.reason) {
                    RelayTunnel.CloseReason.Budget -> RETRY_BUDGET_MS
                    RelayTunnel.CloseReason.Superseded -> RETRY_MAX_MS
                    else -> backoff
                }
                else -> backoff
            }
            val normal = end is RelayTunnel.RelayState.Closed && end.reason == RelayTunnel.CloseReason.Normal
            backoff = if (normal) RETRY_MIN_MS else minOf(backoff * 2, RETRY_MAX_MS)
            Log.i(TAG, "relay to ${config.targetBridgeId} ended ($end); retry in ${wait / 1000}s")
            delay(wait)
        }
    }
}
