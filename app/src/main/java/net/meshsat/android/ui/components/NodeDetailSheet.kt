package net.meshsat.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.meshsat.android.ble.MeshtasticProtocol
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.NodePosition
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono

/**
 * How our radio hears a node (MESHSAT-1249). SNR is only quoted for a node heard directly: on a
 * relayed packet it is the relay's signal, not the node's, so a relayed node shows its hop count.
 */
data class NodeSignal(
    /** For a narrow column: "6.5 dB", "2 hops" or "-". */
    val short: String,
    /** A sentence for the detail sheet. */
    val long: String,
    val direct: Boolean,
    val snr: Float?,
    /** 0 heard directly, more is relayed, -1 not known. */
    val hops: Int,
)

/** [live] is the last over-the-air packet (MeshtasticBle.linkSignals); [node] is the radio's node list. */
fun nodeSignal(
    node: MeshtasticProtocol.MeshNodeInfo,
    live: MeshtasticProtocol.MeshLinkSignal?,
): NodeSignal {
    fun direct(snr: Float, rssi: Int, measured: String) = NodeSignal(
        short = "%.1f dB".format(snr),
        long = buildString {
            append("Heard directly. SNR %.1f dB".format(snr))
            if (rssi != 0) append(", RSSI $rssi dBm")
            append(measured)
        },
        direct = true,
        snr = snr,
        hops = 0,
    )
    fun relayed(hops: Int) = NodeSignal(
        short = Words.count(hops, "hop"),
        long = "Heard through other nodes, ${Words.count(hops, "hop")} away.",
        direct = false,
        snr = null,
        hops = hops,
    )
    return when {
        live != null && live.hopsAway == 0 -> direct(live.snr, live.rssi, ".")
        live != null && live.hopsAway > 0 -> relayed(live.hopsAway)
        node.hopsAway == 0 && node.snr != 0f -> direct(node.snr, 0, ", as your node last measured it.")
        node.hopsAway > 0 -> relayed(node.hopsAway)
        else -> NodeSignal(
            short = "-",
            long = "Not measured yet. Your node measures it when it hears this node transmit.",
            direct = false,
            snr = null,
            hops = -1,
        )
    }
}

/**
 * A node's details and what can be done with it: message it, or find it on the map. Pass null for
 * an action that does not apply (there is no messaging your own node).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailSheet(
    node: MeshtasticProtocol.MeshNodeInfo,
    live: MeshtasticProtocol.MeshLinkSignal?,
    isMe: Boolean,
    onMessage: (() -> Unit)?,
    onShowOnMap: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val positions by remember(node.nodeNum) {
        AppDatabase.getInstance(context).nodePositionDao().getByNode(node.nodeNum, 1)
    }.collectAsState(initial = emptyList<NodePosition>())
    val position = positions.firstOrNull()
    val id = MeshtasticProtocol.formatNodeId(node.nodeNum)
    val signal = nodeSignal(node, live)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = node.longName.ifBlank { id } + (if (isMe) " (your node)" else ""),
                    style = MaterialTheme.typography.titleLarge,
                    color = MeshSatTextPrimary,
                )
                Text(
                    text = listOf(node.shortName, id).filter { it.isNotBlank() }.joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = PlexMono,
                    color = MeshSatTextMuted,
                )
            }

            if (!isMe) DetailRow("Last heard", Words.ago(node.lastHeard))
            DetailRow(
                "Battery",
                net.meshsat.android.ble.NodeBattery.describe(node.batteryLevel, 0f, null) ?: "Not reported",
                mono = node.batteryLevel in 0..100,
            )
            if (!isMe) DetailRow("Signal", signal.long)
            if (node.hwModel != 0) DetailRow("Hardware", MeshtasticProtocol.hardwareName(node.hwModel))
            DetailRow(
                "Position",
                position?.let { "%.5f, %.5f, %s".format(it.latitude, it.longitude, Words.ago(it.timestamp)) }
                    ?: "Not shared yet. It appears once the node sends its position.",
                mono = position != null,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onMessage != null) {
                    Button(
                        onClick = onMessage,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text("Message")
                    }
                }
                if (onShowOnMap != null) {
                    OutlinedButton(
                        onClick = onShowOnMap,
                        enabled = position != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text("Show on map")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextMuted,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) PlexMono else null,
            color = MeshSatTextSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}
