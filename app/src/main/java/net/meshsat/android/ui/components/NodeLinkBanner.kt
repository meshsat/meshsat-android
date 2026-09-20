package net.meshsat.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.engine.InterfaceState
import net.meshsat.android.service.GatewayService
import net.meshsat.android.sos.SosController
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.SpaceBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A line across every screen while the phone cannot reach its node (MESHSAT-615).
 *
 * On 20 September the pipe to the node stopped taking writes and the app said "Connected" for
 * thirteen minutes, with Home quoting the satellite pass overhead, while nothing could leave
 * the phone (MESHSAT-1270). The state and the recovery were fixed the same day, and the
 * Satellite lane on Home now tells the truth - but only Home. Someone writing a message or
 * watching the queue still saw nothing.
 *
 * So this says which link is down and since when, on whatever screen the person is on, and
 * takes itself away when the link returns. An SOS outranks it: one banner at a time, and a
 * person holding an SOS does not need to read about Bluetooth.
 */
@Composable
fun NodeLinkBanner(onOpen: () -> Unit) {
    val sos by SosController.run.collectAsState()
    if (sos?.active == true) return

    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var savedNode by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            savedNode = settings.meshtasticBleAddress.first()
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    // Nothing to be unreachable until a node has been paired at least once.
    if (savedNode.isBlank()) return

    val ble = GatewayService.meshtasticBle
    val spp = GatewayService.iridiumSpp
    val meshUp = ble?.state.collectOrNull()?.value == MeshtasticBle.State.Connected
    val modemUnreachable = spp?.linkBroken.collectOrNull()?.value == true

    if (meshUp && !modemUnreachable) return

    val since = lastGood(if (meshUp) "iridium_0" else "mesh_0")
    val what = if (meshUp) "the node's modem" else "your MeshSat node"
    val text = buildString {
        append("No ")
        append(what)
        if (since != null) {
            append(" since ")
            append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(since)))
            val mins = ((now - since) / 60_000L).coerceAtLeast(0)
            if (mins >= 1) append(" (${mins} min)")
        }
        append(". Nothing goes out by mesh or satellite. Tap to see.")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatAmber)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SpaceBlack)
    }
}

/**
 * When [interfaceId] was last up, from the manager that tracks it. Null while it has never been
 * up in this run, in which case the banner simply does not name a time: "since" a moment that
 * never happened would be a lie, and an invented one at that.
 */
private fun lastGood(interfaceId: String): Long? {
    val status = GatewayService.ifaceManager?.getAllStatus()?.firstOrNull { it.id == interfaceId }
        ?: return null
    if (status.state == InterfaceState.Disabled) return null
    return status.lastOnline.takeIf { it > 0 }
}
