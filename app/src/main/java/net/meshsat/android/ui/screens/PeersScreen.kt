package net.meshsat.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.meshsat.android.ble.MeshtasticProtocol
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono
import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextAlign
import net.meshsat.android.ble.MeshtasticBle

private enum class PeerSortMode(val label: String) {
    Name("Name"),
    LastSeen("Last Seen"),
    Battery("Battery"),
}

@Composable
fun PeersScreen(onConnect: () -> Unit = {}) {
    val nodes by GatewayService.meshtasticBle?.nodes?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }

    var sortMode by remember { mutableStateOf(PeerSortMode.LastSeen) }

    val now = System.currentTimeMillis()
    val activeThreshold = 15 * 60 * 1000L // 15 minutes
    val activeCount = nodes.count { it.lastHeard > 0 && (now - it.lastHeard) < activeThreshold }

    val sortedNodes = remember(nodes, sortMode) {
        when (sortMode) {
            PeerSortMode.Name -> nodes.sortedBy {
                it.longName.ifBlank { it.shortName.ifBlank { MeshtasticProtocol.formatNodeId(it.nodeNum) } }.lowercase()
            }
            PeerSortMode.LastSeen -> nodes.sortedByDescending { it.lastHeard }
            PeerSortMode.Battery -> nodes.sortedByDescending { if (it.batteryLevel < 0) -1 else it.batteryLevel }
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

        // Stats header
        Text(
            text = "${nodes.size} nodes ($activeCount active)",
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Silence is not evidence of an empty mesh: a node only shows up once it transmits.
                val meshUp = GatewayService.meshtasticBle?.state?.collectAsState()?.value == MeshtasticBle.State.Connected
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
            // Sort header row (tap to cycle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status + ID
                Text(
                    text = "Node",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sortMode == PeerSortMode.Name) MeshSatTeal else MeshSatTextMuted,
                    fontWeight = if (sortMode == PeerSortMode.Name) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .weight(0.4f)
                        .clickable { sortMode = PeerSortMode.Name },
                )
                Text(
                    text = "SNR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                    modifier = Modifier.weight(0.12f),
                )
                Text(
                    text = "Batt",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sortMode == PeerSortMode.Battery) MeshSatTeal else MeshSatTextMuted,
                    fontWeight = if (sortMode == PeerSortMode.Battery) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .weight(0.12f)
                        .clickable { sortMode = PeerSortMode.Battery },
                )
                Text(
                    text = "Last Seen",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sortMode == PeerSortMode.LastSeen) MeshSatTeal else MeshSatTextMuted,
                    fontWeight = if (sortMode == PeerSortMode.LastSeen) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .weight(0.26f)
                        .clickable { sortMode = PeerSortMode.LastSeen },
                )
            }

            // Node list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(sortedNodes, key = { it.nodeNum }) { node ->
                    PeerRow(node = node, now = now)
                }
            }
        }
    }
}

@Composable
private fun PeerRow(node: MeshtasticProtocol.MeshNodeInfo, now: Long) {
    val elapsed = if (node.lastHeard > 0) now - node.lastHeard else Long.MAX_VALUE
    val statusColor = when {
        elapsed < 15 * 60 * 1000L -> MeshSatGreen       // online < 15min
        elapsed < 60 * 60 * 1000L -> MeshSatAmber        // stale < 1h
        else -> MeshSatTextMuted                          // offline
    }

    val name = node.longName.ifBlank { node.shortName.ifBlank { "" } }
    val nodeId = MeshtasticProtocol.formatNodeId(node.nodeNum)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface)
            .border(0.5f.dp, MeshSatBorder)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot + name/ID
        Row(
            modifier = Modifier.weight(0.4f),
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
                if (name.isNotBlank()) {
                    Text(
                        text = name,
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

        // SNR (not available from node info — show dash)
        Text(
            text = "-",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.weight(0.12f),
        )

        // Battery
        Text(
            text = if (node.batteryLevel in 0..100) "${node.batteryLevel}%" else "-",
            style = MaterialTheme.typography.bodySmall,
            color = when {
                node.batteryLevel < 0 -> MeshSatTextMuted
                node.batteryLevel <= 20 -> MeshSatAmber
                else -> MeshSatGreen
            },
            modifier = Modifier.weight(0.12f),
        )

        // Last Seen
        Text(
            text = formatRelativeTime(elapsed),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            modifier = Modifier.weight(0.26f),
        )
    }
}

private fun formatRelativeTime(elapsedMs: Long): String {
    if (elapsedMs == Long.MAX_VALUE) return "never"
    val seconds = elapsedMs / 1000
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}
