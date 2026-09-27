package net.meshsat.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.MeshProtos
import kotlinx.coroutines.launch
import net.meshsat.android.ble.GattOpQueue
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.NodeLog
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.components.collectOrNull
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary

/**
 * Settings > Advanced > Node log (MESHSAT-1374): the node's live log over Bluetooth, the same
 * lines its serial console prints, so a bench session needs no USB cable. The switch sets
 * security.debug_log_api_enabled on the node (a setting the node keeps; a security set makes
 * the node restart once, upstream AdminModule behaviour, and the app reconnects); the screen
 * follows LogRadio only while it is open. Lines newest at the bottom, paused lines held and appended on
 * resume, at most 2000 kept. Same screen on iOS.
 */
@Composable
fun NodeLogScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ble = GatewayService.meshtasticBle
    val connected = (ble?.state.collectOrNull()?.value ?: MeshtasticBle.State.Disconnected) == MeshtasticBle.State.Connected
    val security = ble?.securityConfig.collectOrNull()?.value
    val available = ble?.logRadioAvailable.collectOrNull()?.value ?: false
    val streaming = security?.debugLogApiEnabled == true
    val lines by (ble?.nodeLog?.lines ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
    val paused by (ble?.nodeLog?.paused ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    var setting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Follow LogRadio while the screen is open and the node streams; stop when it is left.
    DisposableEffect(ble, connected, streaming, available) {
        if (ble != null && connected && streaming && available) {
            scope.launch {
                val status = ble.followNodeLog(true)
                if (status != GattOpQueue.STATUS_SUCCESS) {
                    Toast.makeText(context, "The node's log could not be followed: $status.", Toast.LENGTH_LONG).show()
                }
            }
        }
        onDispose {
            if (ble != null && connected) scope.launch { ble.followNodeLog(false) }
        }
    }

    LaunchedEffect(lines.size, paused) {
        if (!paused && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Stream the node's log", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = streaming,
                enabled = connected && !setting,
                onCheckedChange = { on ->
                    val why = ble?.setNodeDebugLog(on) ?: "The node is not connected."
                    if (why != null) {
                        Toast.makeText(context, why, Toast.LENGTH_LONG).show()
                    } else {
                        setting = true
                        scope.launch {
                            kotlinx.coroutines.delay(1_500)
                            setting = false
                        }
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
            )
        }
        Text(
            text = when {
                !connected -> "Connect your MeshSat node first."
                streaming -> "The node sends every log line while this switch is on; it may drop lines in a burst. Newest at the bottom."
                else -> "Sets the node's debug log over Bluetooth (security.debug_log_api_enabled); a setting the node keeps. The node restarts once to apply it, and the link comes back by itself."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            itemsIndexed(lines, key = { i, l -> "${l.receivedMs}-$i" }) { _, line ->
                Text(
                    text = NodeLog.format(line),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = when (line.level) {
                        MeshProtos.LogRecord.Level.ERROR, MeshProtos.LogRecord.Level.CRITICAL -> MeshSatRed
                        MeshProtos.LogRecord.Level.WARNING -> MeshSatAmber
                        MeshProtos.LogRecord.Level.DEBUG, MeshProtos.LogRecord.Level.TRACE -> MeshSatTextMuted
                        else -> MeshSatTextPrimary
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { ble?.nodeLog?.let { if (paused) it.resume() else it.pause() } }, modifier = Modifier.weight(1f)) {
                Text(if (paused) "Resume" else "Pause", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { ble?.nodeLog?.clear() }, modifier = Modifier.weight(1f)) {
                Text("Clear", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = {
                    val text = ble?.nodeLog?.text().orEmpty()
                    if (text.isBlank()) {
                        Toast.makeText(context, "Nothing to share yet.", Toast.LENGTH_SHORT).show()
                    } else {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("MeshSat node log", text))
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "MeshSat node log")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Share the node log"))
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Share", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
