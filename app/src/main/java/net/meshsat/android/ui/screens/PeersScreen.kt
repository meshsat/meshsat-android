package net.meshsat.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.MeshtasticProtocol
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.components.NodeDetailSheet
import net.meshsat.android.ui.components.NodeSignal
import net.meshsat.android.ui.components.nodeSignal
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono
import net.meshsat.android.ui.components.collectOrNull

private enum class PeerSortMode(val label: String) {
    LastSeen("Last heard"),
    Name("Name"),
    Signal("Signal"),
    Battery("Battery"),
}

private const val ACTIVE_MS = 15 * 60 * 1000L

/**
 * The People tab: every node the MeshSat node has heard. Tap one for its details, to message it or
 * to find it on the map. [onMessage] gets the node id as messages store mesh senders ("!xxxxxxxx");
 * [onShowOnMap] gets the node number.
 */
@Composable
fun PeersScreen(
    onConnect: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    onShowOnMap: (Long) -> Unit = {},
) {
    val ble = GatewayService.meshtasticBle
    val nodes = ble?.nodes.collectOrNull()?.value ?: emptyList()
    val signals = ble?.linkSignals.collectOrNull()?.value ?: emptyMap()
    val myNodeNum = ble?.myInfo.collectOrNull()?.value?.myNodeNum ?: 0L
    val meshUp = ble?.state.collectOrNull()?.value == MeshtasticBle.State.Connected

    var sortMode by remember { mutableStateOf(PeerSortMode.LastSeen) }
    var selected by remember { mutableStateOf<Long?>(null) }

    // "Last heard" and the active count move with the clock, not only with new packets.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val activeCount = nodes.count { it.nodeNum != myNodeNum && it.lastHeard > 0 && now - it.lastHeard < ACTIVE_MS }

    val rows = remember(nodes, signals, sortMode) {
        val withSignal = nodes.map { it to nodeSignal(it, signals[it.nodeNum]) }
        when (sortMode) {
            PeerSortMode.Name -> withSignal.sortedBy { (n, _) ->
                n.longName.ifBlank { n.shortName.ifBlank { MeshtasticProtocol.formatNodeId(n.nodeNum) } }.lowercase()
            }
            PeerSortMode.LastSeen -> withSignal.sortedByDescending { (n, _) -> n.lastHeard }
            PeerSortMode.Battery -> withSignal.sortedByDescending { (n, _) -> if (n.batteryLevel < 0) -1 else n.batteryLevel }
            // Heard directly first, strongest first; then fewest hops; not measured last.
            PeerSortMode.Signal -> withSignal.sortedWith(
                compareBy<Pair<MeshtasticProtocol.MeshNodeInfo, NodeSignal>>(
                    { (_, s) -> if (s.direct) 0 else if (s.hops > 0) 1 else 2 },
                    { (_, s) -> -(s.snr ?: 0f) },
                    { (_, s) -> s.hops },
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "People",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Text(
            // Your own node is in the list but is not someone you heard.
            text = "${Words.count(nodes.count { it.nodeNum != myNodeNum }, "node")} heard, " +
                "$activeCount in the last 15 min",
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Cards handed over face to face, above the nodes: a person you swapped cards with is
        // someone you know, a node you heard is not (MESHSAT-566, 575).
        ContactCardsSection()

        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Silence is not evidence of an empty mesh: a node only shows up once it transmits.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = if (meshUp) "Your node is listening." else "Nobody heard yet.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (meshUp) {
                            "People appear here as soon as they transmit on the mesh."
                        } else {
                            "People appear here when your MeshSat node hears them on the mesh."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (!meshUp) {
                        Button(onClick = onConnect) { Text("Connect your node") }
                    }
                }
            }
        } else {
            // Sort order, as 48 dp chips.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sort by",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                PeerSortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sortMode == mode,
                        onClick = { sortMode = mode },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MeshSatSurfaceLight,
                            selectedLabelColor = MeshSatTextPrimary,
                            labelColor = MeshSatTextSecondary,
                        ),
                    )
                }
            }

            // Column names
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColumnName("Node", Modifier.weight(0.44f))
                ColumnName("Signal", Modifier.weight(0.18f))
                ColumnName("Battery", Modifier.weight(0.16f))
                ColumnName("Last heard", Modifier.weight(0.22f))
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(rows, key = { it.first.nodeNum }) { (node, signal) ->
                    PeerRow(
                        node = node,
                        signal = signal,
                        isMe = node.nodeNum == myNodeNum,
                        now = now,
                        onClick = { selected = node.nodeNum },
                    )
                }
            }
        }
    }

    val selectedNode = selected?.let { num -> nodes.find { it.nodeNum == num } }
    if (selectedNode != null) {
        val isMe = selectedNode.nodeNum == myNodeNum
        val messageNode: () -> Unit = {
            selected = null
            onMessage(MeshtasticProtocol.formatNodeId(selectedNode.nodeNum))
        }
        NodeDetailSheet(
            node = selectedNode,
            live = signals[selectedNode.nodeNum],
            isMe = isMe,
            onMessage = if (isMe) null else messageNode,
            onShowOnMap = {
                selected = null
                onShowOnMap(selectedNode.nodeNum)
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun ColumnName(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MeshSatTextMuted,
        modifier = modifier,
    )
}

@Composable
private fun PeerRow(
    node: MeshtasticProtocol.MeshNodeInfo,
    signal: NodeSignal,
    isMe: Boolean,
    now: Long,
    onClick: () -> Unit,
) {
    val elapsed = if (node.lastHeard > 0) now - node.lastHeard else Long.MAX_VALUE
    val statusColor = when {
        isMe -> MeshSatTextPrimary
        elapsed < ACTIVE_MS -> MeshSatGreen
        else -> MeshSatTextMuted
    }

    val name = node.longName.ifBlank { node.shortName }
    val nodeId = MeshtasticProtocol.formatNodeId(node.nodeNum)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MeshSatSurface)
            .border(0.5f.dp, MeshSatBorder)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot + name/ID
        Row(
            modifier = Modifier.weight(0.44f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Column {
                if (name.isNotBlank() || isMe) {
                    Text(
                        text = if (isMe) "${name.ifBlank { "Your node" }} (you)" else name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = nodeId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = PlexMono,
                    color = MeshSatTextMuted,
                )
            }
        }

        // Signal: SNR when heard directly, the hop count when relayed.
        Text(
            text = if (isMe) "-" else signal.short,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (signal.direct && !isMe) PlexMono else null,
            color = if (signal.direct && !isMe) MeshSatTextPrimary else MeshSatTextMuted,
            modifier = Modifier.weight(0.18f),
        )

        // Battery
        Text(
            text = net.meshsat.android.ble.NodeBattery.cell(node.batteryLevel),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = PlexMono,
            color = when {
                node.batteryLevel < 0 -> MeshSatTextMuted
                node.batteryLevel > 100 -> MeshSatGreen // on USB power (MESHSAT-1315)
                node.batteryLevel <= 20 -> MeshSatAmber
                else -> MeshSatGreen
            },
            modifier = Modifier.weight(0.16f),
        )

        // Last heard
        Text(
            text = if (isMe) "-" else Words.ago(node.lastHeard, now),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            modifier = Modifier.weight(0.22f),
        )
    }
}
