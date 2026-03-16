package com.cubeos.meshsat.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.ble.MeshtasticProtocol
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Topology View — force-directed graph visualization of mesh nodes
 * with link lines and hardware model display.
 */
@Composable
fun TopologyScreen() {
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val meshNodes = GatewayService.meshtasticBle?.nodes?.collectAsState()
    val myInfo = GatewayService.meshtasticBle?.myInfo?.collectAsState()
    val meshRssi = GatewayService.meshtasticBle?.rssi?.collectAsState()

    val connected = meshState?.value == MeshtasticBle.State.Connected
    val nodes = meshNodes?.value ?: emptyList()
    val myNodeNum = myInfo?.value?.myNodeNum ?: 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Mesh Topology",
            style = MaterialTheme.typography.headlineMedium,
        )

        if (!connected || nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (!connected) "Connect to a Meshtastic radio\nto view mesh topology."
                    else "No mesh nodes discovered yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            // Connection info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${nodes.size} nodes discovered",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorMesh,
                )
                val rssi = meshRssi?.value ?: 0
                if (rssi != 0) {
                    Text(
                        text = "RSSI: ${rssi}dBm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatTextSecondary,
                    )
                }
            }

            // Force-directed graph canvas
            TopologyCanvas(
                nodes = nodes,
                myNodeNum = myNodeNum,
            )

            // Node list below graph
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Node Details",
                    style = MaterialTheme.typography.titleMedium,
                )

                nodes.forEach { node ->
                    NodeInfoRow(
                        node = node,
                        isMyNode = node.nodeNum == myNodeNum,
                    )
                }
            }

            // Legend
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LegendItem("This device", MeshSatTeal)
                LegendItem("Mesh node", MeshSatGreen)
            }
        }
    }
}

/**
 * Force-directed graph layout using a simple spring-electric model.
 * Nodes repel each other; edges (links to this device) attract.
 */
@Composable
private fun TopologyCanvas(
    nodes: List<MeshtasticProtocol.MeshNodeInfo>,
    myNodeNum: Long,
) {
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Force-directed positions — computed once and then simulated
    val positions = remember(nodes.map { it.nodeNum }) {
        val n = nodes.size
        Array(n) { i ->
            val angle = 2.0 * PI * i / n
            val radius = 120.0
            floatArrayOf(
                (radius * cos(angle)).toFloat(),
                (radius * sin(angle)).toFloat(),
            )
        }
    }

    // Run force simulation
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(nodes.map { it.nodeNum }) {
        for (step in 0 until 80) {
            val temp = 5f * (1f - step / 80f)
            simulateForces(positions, nodes, myNodeNum, temp)
            tick++
            delay(16)
        }
    }

    // Trigger recomposition on tick
    @Suppress("UNUSED_EXPRESSION")
    tick

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 3f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2 + offsetX
            val cy = size.height / 2 + offsetY

            // Draw edges from this device to all other nodes
            val myIdx = nodes.indexOfFirst { it.nodeNum == myNodeNum }

            for (i in nodes.indices) {
                if (i == myIdx) continue
                val fromIdx = if (myIdx >= 0) myIdx else 0
                val x1 = cx + positions[fromIdx][0] * scale
                val y1 = cy + positions[fromIdx][1] * scale
                val x2 = cx + positions[i][0] * scale
                val y2 = cy + positions[i][1] * scale

                drawLine(
                    color = MeshSatBorder,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 1.5f * density.density,
                )
            }

            // Draw nodes
            for (i in nodes.indices) {
                val node = nodes[i]
                val x = cx + positions[i][0] * scale
                val y = cy + positions[i][1] * scale
                val isMe = node.nodeNum == myNodeNum
                val color = if (isMe) MeshSatTeal else MeshSatGreen
                val radius = if (isMe) 14f else 10f

                // Glow
                drawCircle(
                    color = color.copy(alpha = 0.3f),
                    radius = (radius + 4f) * density.density,
                    center = Offset(x, y),
                )
                // Node circle
                drawCircle(
                    color = color,
                    radius = radius * density.density,
                    center = Offset(x, y),
                )

                // Label
                val label = node.shortName.ifBlank {
                    MeshtasticProtocol.formatNodeId(node.nodeNum).takeLast(4)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x,
                    y + (radius + 16f) * density.density,
                    android.graphics.Paint().apply {
                        this.color = android.graphics.Color.parseColor("#9CA3AF")
                        textSize = 10f * density.density
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    },
                )
            }
        }
    }
}

private fun simulateForces(
    positions: Array<FloatArray>,
    nodes: List<MeshtasticProtocol.MeshNodeInfo>,
    myNodeNum: Long,
    temperature: Float,
) {
    val n = positions.size
    if (n < 2) return

    val forces = Array(n) { floatArrayOf(0f, 0f) }
    val repulsionK = 8000f
    val attractionK = 0.01f

    // Repulsion between all pairs
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val dx = positions[i][0] - positions[j][0]
            val dy = positions[i][1] - positions[j][1]
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val force = repulsionK / (dist * dist)
            val fx = force * dx / dist
            val fy = force * dy / dist
            forces[i][0] += fx
            forces[i][1] += fy
            forces[j][0] -= fx
            forces[j][1] -= fy
        }
    }

    // Attraction: edges from myNode to all others
    val myIdx = nodes.indexOfFirst { it.nodeNum == myNodeNum }
    if (myIdx >= 0) {
        for (i in 0 until n) {
            if (i == myIdx) continue
            val dx = positions[i][0] - positions[myIdx][0]
            val dy = positions[i][1] - positions[myIdx][1]
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val force = attractionK * dist
            val fx = force * dx / dist
            val fy = force * dy / dist
            forces[myIdx][0] += fx
            forces[myIdx][1] += fy
            forces[i][0] -= fx
            forces[i][1] -= fy
        }
    }

    // Apply forces with temperature
    for (i in 0 until n) {
        val fx = forces[i][0]
        val fy = forces[i][1]
        val mag = sqrt(fx * fx + fy * fy).coerceAtLeast(0.001f)
        val cap = min(mag, temperature * 10f)
        positions[i][0] += cap * fx / mag
        positions[i][1] += cap * fy / mag
        positions[i][0] = positions[i][0].coerceIn(-200f, 200f)
        positions[i][1] = positions[i][1].coerceIn(-200f, 200f)
    }
}

@Composable
private fun NodeInfoRow(
    node: MeshtasticProtocol.MeshNodeInfo,
    isMyNode: Boolean,
) {
    val name = node.longName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeNum) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isMyNode) MeshSatTeal.copy(alpha = 0.1f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isMyNode) MeshSatTeal else MeshSatGreen, CircleShape),
            )
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isMyNode) MeshSatTeal else Color.Unspecified,
                    )
                    if (isMyNode) {
                        Text(
                            text = "(you)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTeal,
                        )
                    }
                }
                Text(
                    text = MeshtasticProtocol.formatNodeId(node.nodeNum),
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            if (node.shortName.isNotBlank()) {
                Text(
                    text = node.shortName,
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorMesh,
                )
            }
            if (node.hwModel != 0) {
                Text(
                    text = "HW: ${node.hwModel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MeshSatTextSecondary,
        )
    }
}
