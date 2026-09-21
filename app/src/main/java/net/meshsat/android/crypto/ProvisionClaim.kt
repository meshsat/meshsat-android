package net.meshsat.android.crypto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.meshsat.android.service.GatewayService

/**
 * The provisioning claim in progress, held by the app rather than by a screen (MESHSAT-1306).
 *
 * The Hub hands the credentials out only once its brokers accept them, which took 63 s in the
 * owner's test on 21 Sep 2026. The claim used to run in the Setup screen, so leaving the
 * screen dropped it, and the wait showed as a three-second toast. Here it runs to the end
 * wherever the person goes, and [state] says where it stands for as long as it takes.
 */
object ProvisionClaim {
    private const val TAG = "ProvisionClaim"

    sealed class State {
        data object Idle : State()

        /** Asking the Hub; [attempts] answers so far were "not ready yet". */
        data class Waiting(val bridgeId: String, val hubHost: String, val startedMs: Long, val attempts: Int) : State()

        /** A scanned code's credentials, for the person to confirm. */
        data class Ready(val bundle: ProvisionImporter.ProvisionBundle) : State()

        data class Applied(val bridgeId: String) : State()

        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * A QR code the person scanned: claim, then ask them to confirm with what came back,
     * since scanning was their choice (MESHSAT-1235).
     */
    fun fromQr(url: String) {
        val request = runCatching { ProvisionImporter.parseLink(url) }.getOrNull()
        run(request?.bridgeId.orEmpty(), request?.hubHost.orEmpty(), confirm = null) { onWaiting ->
            ProvisionImporter.processQr(url, onWaiting)
        }
    }

    /** A deep link the person already confirmed: claim and apply. */
    fun fromLink(request: ProvisionImporter.ProvisionRequest, context: Context) {
        run(request.bridgeId, request.hubHost, confirm = context.applicationContext) { onWaiting ->
            ProvisionImporter.claimBundle(request, onWaiting)
        }
    }

    /** The person confirmed a [State.Ready] bundle. */
    fun apply(context: Context) {
        val ready = _state.value as? State.Ready ?: return
        val app = context.applicationContext
        _state.value = State.Idle // the dialog goes at once: a second tap applies nothing
        job = scope.launch { applyNow(ready.bundle, app) }
    }

    /** Back to [State.Idle], abandoning a claim still waiting. */
    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    private fun run(
        bridgeId: String,
        hubHost: String,
        confirm: Context?,
        claim: suspend ((Int) -> Unit) -> ProvisionImporter.ProvisionBundle,
    ) {
        job?.cancel()
        val started = System.currentTimeMillis()
        _state.value = State.Waiting(bridgeId, hubHost, started, 0)
        job = scope.launch {
            try {
                val bundle = claim { attempt -> _state.value = State.Waiting(bridgeId, hubHost, started, attempt) }
                if (confirm == null) _state.value = State.Ready(bundle) else applyNow(bundle, confirm)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ProvisionImporter.ProvisionException) {
                _state.value = State.Failed(e.message ?: "The Hub did not hand out the settings")
            } catch (e: Exception) {
                Log.e(TAG, "Provision failed", e)
                _state.value = State.Failed("Provisioning failed: ${e.message}")
            }
        }
    }

    private suspend fun applyNow(bundle: ProvisionImporter.ProvisionBundle, app: Context) {
        try {
            ProvisionImporter.apply(bundle, app)
            // The gateway reads the Hub settings when it starts (MESHSAT-749).
            GatewayService.scheduleRestart(app)
            _state.value = State.Applied(bundle.bridgeId)
        } catch (e: Exception) {
            Log.e(TAG, "Applying the Hub settings failed", e)
            _state.value = State.Failed("Could not save the Hub settings: ${e.message}")
        }
    }
}
