package net.meshsat.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.StateFlow

/**
 * `flow?.collectAsState()` for a service object that may not exist yet, as a properly grouped
 * composable call (MESHSAT-1249). Written as `GatewayService.iridiumSpp?.modemInfo?.collectAsState()`
 * the collection only happens once the service has made the object, so it appears in the middle of
 * a composition that already ran; on the emulator Compose then read a neighbouring String slot as
 * ModemInfo and the app crashed. Here the call sits in its own group keyed by the flow, behind an
 * explicit `if`, so it comes and goes cleanly and a new object starts from its own current value.
 * Like the old chain it answers null while the flow does not exist.
 */
@Composable
fun <T> StateFlow<T>?.collectOrNull(): State<T>? {
    val flow = this
    return key(flow) {
        if (flow != null) flow.collectAsState() else null
    }
}
