package net.meshsat.android.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.provider.Settings
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

    val down = !meshUp || modemUnreachable

    // When this phone first saw the link go, kept here rather than read from InterfaceManager:
    // its lastOnline is the moment a link came *up*, and using it made the banner say "since
    // 23:16" about a node that had dropped twenty seconds earlier. Caught by taking the node
    // away and reading the screen, which is what this issue asked for (MESHSAT-615).
    var downSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(down) {
        downSince = if (down) System.currentTimeMillis() else null
    }

    if (!down) return

    val since = downSince
    val bluetoothOff = ble?.bluetoothOn.collectOrNull()?.value == false
    val text = nodeLinkBannerText(
        bluetoothOff = bluetoothOff,
        meshUp = meshUp,
        since = since?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) },
        minutes = since?.let { ((now - it) / 60_000L).coerceAtLeast(0) } ?: 0,
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatAmber)
            .clickable {
                if (bluetoothOff) askForBluetooth(context) else onOpen()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SpaceBlack)
    }
}

/**
 * What the banner says. The cause comes first when the phone knows it: the first version said
 * "Cannot reach your MeshSat node" while the phone's own Bluetooth was switched off, which sends a
 * person to look at a radio in a case when the answer is in their hand (owner, 21 Sep 2026).
 */
internal fun nodeLinkBannerText(bluetoothOff: Boolean, meshUp: Boolean, since: String?, minutes: Long): String =
    buildString {
        val how = buildString {
            if (since != null) {
                append(" since ")
                append(since)
                if (minutes >= 1) append(" ($minutes min)")
            }
        }
        when {
            bluetoothOff -> {
                append("Bluetooth is off")
                append(how)
                append(", so the phone cannot reach your MeshSat node. Nothing goes out by mesh or satellite. Tap to switch it on.")
            }
            else -> {
                append("Cannot reach ")
                append(if (meshUp) "the node's modem" else "your MeshSat node")
                append(how)
                append(". Nothing goes out by mesh or satellite. Tap to see.")
            }
        }
    }

/** The system's own "turn on Bluetooth?" dialog, or its Bluetooth settings if that is refused. */
private fun askForBluetooth(context: Context) {
    try {
        context.startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e2: Exception) {
            // No way to open either; the banner still says what to do.
        }
    }
}
