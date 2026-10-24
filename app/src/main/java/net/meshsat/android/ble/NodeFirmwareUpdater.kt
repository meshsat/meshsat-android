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
 * Once the node has gone to its loader (step 1 answered, or the link dropped after the request) it
 * boots only the loader until an update with this image's SHA-256 completes, and from the update
 * command on its old firmware may be erased (MESHSAT-1684). So the image and the node's address are
 * kept from then on, and every failure of steps 2 to 4 offers [retry] with the same image, without
 * step 1. A node left in its loader when the kept image is gone (the app restarted) gets the same
 * file again through [sendToUpdater]: the scan finds the loader by its service.
 *
 * What the node is left with is the recovery session's, not one attempt's (MESHSAT-1684, R63-F1):
 * every attempt starts its own [NodeOtaTransport.Stage] at the beginning, so an attempt that fails
 * before its command says nothing about what an earlier attempt erased. [known] sits beside the kept
 * image and address, every attempt's result is merged into it and it never goes down; a recovery
 * with no history behind it ([sendToUpdater]) starts at [NodeFirmwareKnown.Unknown]; a finished
 * update clears it.
 */
object NodeFirmwareUpdater {
    private const val TAG = "NodeFirmwareUpdater"
    private const val HANDOVER_TIMEOUT_MS = 8_000L
    // The loader is found by scanning for its service, which simply waits while the node restarts.
    private const val RESTART_INTO_LOADER_MS = 2_000L
    private const val RESTART_INTO_FIRMWARE_MS = 8_000L

    sealed class State {
        object Idle : State()
        object AskingNode : State()
        data class Working(val text: String, val fraction: Float?) : State()
        data class Done(val version: String) : State()
        /** [inUpdater]: the node waits in its loader and needs this update finished. */
        data class Failed(val reason: String, val canRetry: Boolean, val inUpdater: Boolean = false) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var pending: NodeFirmwareImage? = null
    private var pendingAddress: String? = null
    @Volatile private var known: NodeFirmwareKnown? = null

    /** What this recovery session knows about the node's old firmware; null with no session. */
    val knownState: NodeFirmwareKnown? get() = known

    /**
     * One attempt against the loader: the Bluetooth one in the app, a scripted one in tests. Null
     * means Bluetooth is off, so nothing reached the node.
     */
    internal var attempt: suspend (Context, String, NodeFirmwareImage, (NodeOtaTransport.Step) -> Unit) -> NodeOtaTransport.Outcome? =
        { context, address, image, onStep ->
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) null else NodeOtaTransport(context, adapter).update(address, image, onStep)
        }
    internal var restartIntoLoaderMs = RESTART_INTO_LOADER_MS
    internal var restartIntoFirmwareMs = RESTART_INTO_FIRMWARE_MS

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
            when (handover(said, ble.state.value == MeshtasticBle.State.Connected)) {
                Handover.Accepted -> Log.i(TAG, "Node: $said")
                Handover.Refused -> {
                    _state.value = State.Failed("The node will not update: $said", canRetry = false)
                    return@launch
                }
                Handover.NoAnswer -> {
                    _state.value = State.Failed(
                        "The node did not answer the update request. Its firmware may be too old to update this way.", canRetry = false,
                    )
                    return@launch
                }
                Handover.LinkDropped -> Log.w(TAG, "No handover answer, but the node dropped the link: going on")
            }
            afterHandover(context, ble, address, image)
        }
    }

    /**
     * The node accepted the handover (or dropped the link after the request) and is going to its
     * loader: from here only a finished update brings it back. It was running its firmware a moment
     * ago, so a new session starts at [NodeFirmwareKnown.Intact]; what an older session knew was
     * about a firmware the node has since replaced.
     */
    internal suspend fun afterHandover(context: Context, ble: MeshtasticBle?, address: String, image: NodeFirmwareImage) {
        known = null
        keep(image, address, NodeFirmwareKnown.Intact)
        transfer(context, ble, address, image)
    }

    /**
     * Send [image] to a node already waiting in its loader (an update that did not finish and whose
     * image this app no longer holds): no handover, straight to the scan. The loader takes only the
     * image whose SHA-256 the node was handed, so it must be the same file. [address] may be null
     * when the node's is not known; the scan then takes any loader.
     */
    fun sendToUpdater(context: Context, ble: MeshtasticBle?, address: String?, image: NodeFirmwareImage) {
        if (running) return
        val a = address ?: ble?.lastAddress ?: ""
        // Nothing says what happened to this node before: never "its firmware is still on it".
        keep(image, a, NodeFirmwareKnown.Unknown)
        job = scope.launch { transfer(context, ble, a, image) }
    }

    private fun keep(image: NodeFirmwareImage, address: String, atLeast: NodeFirmwareKnown) {
        pending = image
        pendingAddress = address
        known = NodeFirmwareKnown.merge(known, atLeast)
    }

    /** Send the kept image to a node that stayed in its loader after a failed transfer. */
    fun retry(context: Context, ble: MeshtasticBle?) {
        if (running) return
        val image = pending ?: return
        val address = pendingAddress ?: ble?.lastAddress ?: ""
        job = scope.launch { transfer(context, ble, address, image) }
    }

    /** True while an image is kept for a node in its loader. */
    val hasPending: Boolean get() = pending != null

    fun reset() {
        if (!running) _state.value = State.Idle
    }

    private suspend fun transfer(context: Context, ble: MeshtasticBle?, address: String, image: NodeFirmwareImage) {
        ble?.heldForFirmwareUpdate = true
        try {
            ble?.disconnect()
            _state.value = State.Working("The node is restarting into its updater...", null)
            delay(restartIntoLoaderMs)
            val outcome = attempt(context, address, image) { step ->
                _state.value = when (step) {
                    NodeOtaTransport.Step.Connecting -> State.Working("Connecting to the node's updater...", null)
                    NodeOtaTransport.Step.Erasing -> State.Working("The node is making room for the new firmware...", null)
                    is NodeOtaTransport.Step.Sending ->
                        State.Working("Sending the firmware: ${step.sent * 100L / step.total} %", step.sent.toFloat() / step.total)
                    NodeOtaTransport.Step.Verifying -> State.Working("The node is checking and installing the firmware...", 1f)
                }
            }
            when (outcome) {
                null -> _state.value = State.Failed(
                    "Bluetooth is off." + whatTheNodeHas(known ?: NodeFirmwareKnown.Unknown, refused = false, bluetoothOff = true),
                    canRetry = true, inUpdater = true,
                )
                NodeOtaTransport.Outcome.Done -> {
                    pending = null
                    pendingAddress = null
                    known = null
                    _state.value = State.Working("Installed. The node is restarting...", 1f)
                    delay(restartIntoFirmwareMs)
                    _state.value = State.Done(image.version.ifBlank { "the new firmware" })
                }
                is NodeOtaTransport.Outcome.Failed -> {
                    val now = NodeFirmwareKnown.merge(known, NodeFirmwareKnown.of(outcome.stage))
                    known = now
                    _state.value = failedInUpdater(outcome, now)
                }
            }
        } finally {
            ble?.heldForFirmwareUpdate = false
            if (ble != null && address.isNotEmpty()) ble.connect(address)
        }
    }

    /**
     * What the screen says after a failed transfer (MESHSAT-1684). The node went to its loader
     * before the transfer started, so every failure leaves it there and offers a retry with the
     * kept image. The attempt's own reason comes first; what the node is left with comes from the
     * session's [known] state, never from this attempt alone.
     */
    internal fun failedInUpdater(outcome: NodeOtaTransport.Outcome.Failed, known: NodeFirmwareKnown): State.Failed =
        State.Failed(
            outcome.reason + whatTheNodeHas(known, refused = outcome.stage == NodeOtaTransport.Stage.Refused),
            canRetry = true, inUpdater = true,
        )

    /** The sentence about the node's old firmware and what to do, shared with MeshSat iOS word for word. */
    internal fun whatTheNodeHas(known: NodeFirmwareKnown, refused: Boolean, bluetoothOff: Boolean = false): String {
        val state = when (known) {
            NodeFirmwareKnown.Intact ->
                if (refused) "The node waits in its updater with its firmware still on it"
                else "The node waits in its updater with its firmware still on it and starts it only after an update"
            NodeFirmwareKnown.Unknown -> "The node waits in its updater, and whether its old firmware is still on it is not known"
            NodeFirmwareKnown.MayBeErased -> "The node may have erased its firmware and waits in its updater"
            NodeFirmwareKnown.Erased -> "The node erased its firmware and waits in its updater"
        }
        val file = when {
            known == NodeFirmwareKnown.Unknown -> "the file the update started with"
            refused -> "the file this update started with"
            else -> "the same file"
        }
        val retry = if (bluetoothOff) "switch Bluetooth on and retry" else "retry"
        return " $state: $retry with $file."
    }

    /** Test seam: forget the session and the state between tests. */
    internal fun clearForTest() {
        job?.cancel()
        pending = null
        pendingAddress = null
        known = null
        _state.value = State.Idle
    }

    /** What the node did with the update request (MESHSAT-1684). */
    internal enum class Handover {
        /** "Rebooting to BLE OTA": it goes to its loader. */
        Accepted,

        /** It answered why not; it keeps running its firmware. */
        Refused,

        /** No answer and the link is still up: it keeps running its firmware. */
        NoAnswer,

        /** No answer, but the link dropped: it most likely went to its loader. */
        LinkDropped,
    }

    internal fun handover(said: String?, linkUp: Boolean): Handover = when {
        said != null && said.startsWith("Rebooting to") && said.contains("BLE") -> Handover.Accepted
        said != null -> Handover.Refused
        linkUp -> Handover.NoAnswer
        else -> Handover.LinkDropped
    }

    /** True when the node may now be in its loader, so the image and its address must be kept for a retry. */
    internal fun keepsImage(h: Handover): Boolean = h == Handover.Accepted || h == Handover.LinkDropped

    /** The node's answers to ota_request (AdminModule.cpp, firmware 2.8.0). */
    internal fun isHandoverAnswer(text: String): Boolean =
        text.startsWith("Rebooting to") || text.contains("OTA", ignoreCase = true) || text.startsWith("Unable to switch")
}

/**
 * What a recovery session knows about the node's old firmware (MESHSAT-1684, R63-F1), from weakest
 * to strongest claim that it is gone. It only moves up: a later attempt that fails before its
 * command does not bring an erased firmware back.
 */
enum class NodeFirmwareKnown(internal val rank: Int) {
    /** The session began at a handover from a running node and no attempt wrote its command. */
    Intact(0),

    /** The session began with no history (a recovery after the app lost it). */
    Unknown(1),

    /** An attempt wrote its command and nothing settled whether the loader erased. */
    MayBeErased(2),

    /** An attempt saw the loader finish erasing. */
    Erased(3);

    companion object {
        fun of(stage: NodeOtaTransport.Stage): NodeFirmwareKnown = when (stage) {
            NodeOtaTransport.Stage.NotCommanded, NodeOtaTransport.Stage.Refused -> Intact
            NodeOtaTransport.Stage.EraseUncertain -> MayBeErased
            NodeOtaTransport.Stage.Erased -> Erased
        }

        fun merge(known: NodeFirmwareKnown?, next: NodeFirmwareKnown): NodeFirmwareKnown =
            if (known == null || next.rank > known.rank) next else known
    }
}
