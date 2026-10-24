package net.meshsat.android.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A MeshSat node's firmware update from the phone over Bluetooth (MESHSAT-1672):
 * 1. ask the node, over its Meshtastic link, to reboot into its OTA loader for this image's SHA-256
 *    (AdminMessage.ota_request); the node answers "Rebooting to BLE OTA" or says why it will not;
 * 2. let go of the node (no reconnect while the update holds it), give it time to restart;
 * 3. send the image to the loader ([NodeOtaTransport]), which checks the SHA-256, installs it and
 *    restarts into it;
 * 4. connect to the node again.
 * After a failure once the loader has erased the old firmware, the node stays in its loader until
 * the same image is sent again: the image is kept and [retry] sends it without step 1.
 */
object NodeFirmwareUpdater {
    private const val TAG = "NodeFirmwareUpdater"
    private const val HANDOVER_TIMEOUT_MS = 8_000L
    private const val RESTART_INTO_LOADER_MS = 6_000L
    private const val RESTART_INTO_FIRMWARE_MS = 8_000L

    sealed class State {
        object Idle : State()
        object AskingNode : State()
        data class Working(val text: String, val fraction: Float?) : State()
        data class Done(val version: String) : State()
        data class Failed(val reason: String, val canRetry: Boolean) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var pending: NodeFirmwareImage? = null
    private var pendingAddress: String? = null

    val running: Boolean get() = job?.isActive == true

    /** Start an update of the connected node with [image], already checked against the node's env. */
    fun start(context: Context, ble: MeshtasticBle, image: NodeFirmwareImage) {
        if (running) return
        val address = ble.lastAddress
        val me = ble.myInfo.value?.myNodeNum ?: 0L
        if (address == null || me == 0L || ble.state.value != MeshtasticBle.State.Connected) {
            _state.value = State.Failed("Connect to the node first.", canRetry = false)
            return
        }
        job = scope.launch {
            _state.value = State.AskingNode
            val answer = async {
                withTimeoutOrNull(HANDOVER_TIMEOUT_MS) {
                    ble.clientNotifications.first { isHandoverAnswer(it) }
                }
            }
            delay(200)
            ble.sendToRadio(MeshtasticProtoAdapter.buildAdminOtaRequest(me, image.sha256))
            val said = answer.await()
            when {
                said != null && said.startsWith("Rebooting to") && said.contains("BLE") -> Log.i(TAG, "Node: $said")
                said != null -> {
                    _state.value = State.Failed("The node will not update: $said", canRetry = false)
                    return@launch
                }
                ble.state.value == MeshtasticBle.State.Connected -> {
                    _state.value = State.Failed(
                        "The node did not answer the update request. Its firmware may be too old to update this way.", canRetry = false,
                    )
                    return@launch
                }
                else -> Log.w(TAG, "No handover answer, but the node dropped the link: going on")
            }
            transfer(context, ble, address, image)
        }
    }

    /** Send the kept image to a node that stayed in its loader after a failed transfer. */
    fun retry(context: Context, ble: MeshtasticBle) {
        if (running) return
        val image = pending ?: return
        val address = pendingAddress ?: ble.lastAddress ?: return
        job = scope.launch { transfer(context, ble, address, image) }
    }

    fun reset() {
        if (!running) _state.value = State.Idle
    }

    private suspend fun transfer(context: Context, ble: MeshtasticBle, address: String, image: NodeFirmwareImage) {
        ble.heldForFirmwareUpdate = true
        try {
            ble.disconnect()
            _state.value = State.Working("The node is restarting into its updater...", null)
            delay(RESTART_INTO_LOADER_MS)
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                _state.value = State.Failed("Bluetooth is off.", canRetry = true)
                pending = image
                pendingAddress = address
                return
            }
            val outcome = NodeOtaTransport(context, adapter).update(address, image) { step ->
                _state.value = when (step) {
                    NodeOtaTransport.Step.Connecting -> State.Working("Connecting to the node's updater...", null)
                    NodeOtaTransport.Step.Erasing -> State.Working("The node is making room for the new firmware...", null)
                    is NodeOtaTransport.Step.Sending ->
                        State.Working("Sending the firmware: ${step.sent * 100L / step.total} %", step.sent.toFloat() / step.total)
                    NodeOtaTransport.Step.Verifying -> State.Working("The node is checking and installing the firmware...", 1f)
                }
            }
            when (outcome) {
                NodeOtaTransport.Outcome.Done -> {
                    pending = null
                    pendingAddress = null
                    _state.value = State.Working("Installed. The node is restarting...", 1f)
                    delay(RESTART_INTO_FIRMWARE_MS)
                    _state.value = State.Done(image.version.ifBlank { "the new firmware" })
                }
                is NodeOtaTransport.Outcome.Failed -> {
                    if (outcome.loaderHasImage) {
                        pending = image
                        pendingAddress = address
                    }
                    _state.value = State.Failed(
                        outcome.reason + if (outcome.loaderHasImage) " The node waits in its updater: retry with the same file." else "",
                        canRetry = outcome.loaderHasImage,
                    )
                }
            }
        } finally {
            ble.heldForFirmwareUpdate = false
            ble.connect(address)
        }
    }

    /** The node's answers to ota_request (AdminModule.cpp, firmware 2.8.0). */
    internal fun isHandoverAnswer(text: String): Boolean =
        text.startsWith("Rebooting to") || text.contains("OTA", ignoreCase = true) || text.startsWith("Unable to switch")
}
