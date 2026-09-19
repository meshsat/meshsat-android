package net.meshsat.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.MeshtasticProtocol
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// A node or a link counts as recent when heard within this window; older ones are drawn muted.
private const val FRESH_MS = 15 * 60 * 1000L

/**
 * One measured hearing: [hearer] received [heard] at [snr] dB, known at [at]. [byOurRadio] is a
 * measurement our own radio made; otherwise [hearer] reported it in a NeighborInfo packet.
 */
private data class Hearing(
    val hearer: Long,
    val heard: Long,
    val snr: Float,
    val at: Long,
    val fresh: Boolean,
    val byOurRadio: Boolean,
)

/** A link between two nodes: every hearing that shows it, in either direction. */
private data class Link(val a: Long, val b: Long, val hearings: List<Hearing>) {
    val fresh: Boolean get() = hearings.any { it.fresh }
}

private data class TopoNode(
    val num: Long,
    val label: String,
    val name: String,
    val isMe: Boolean,
    val lastSeen: Long,
    val info: MeshtasticProtocol.MeshNodeInfo?,
)

private data class Topology(
    val nodes: List<TopoNode>,
    val links: List<Link>,
    val hearings: List<Hearing>,
)

/**
 * The mesh as it was actually heard (MESHSAT-1249): a link is drawn only where a node reported the
 * other in a NeighborInfo packet, or where our own radio heard a node directly (0 hops). Nothing is
 * inferred from silence, and a node with no link is shown on its own.
 */
private fun buildTopology(
    nodes: List<MeshtasticProtocol.MeshNodeInfo>,
    myNodeNum: Long,
    reports: Map<Long, MeshtasticProtocol.NeighborReport>,
    signals: Map<Long, MeshtasticProtocol.MeshLinkSignal>,
    now: Long,
): Topology {
    val hearings = mutableListOf<Hearing>()

    // What the nodes themselves reported. The window follows the sender's broadcast interval,
    // which is hours, so a report is not called old just because it is from this morning.
    for (report in reports.values) {
        val window = max(FRESH_MS, 2L * report.broadcastIntervalSecs * 1000L)
        val fresh = now - report.receivedAt < window
        for (n in report.neighbors) {
            if (n.nodeId == report.nodeId) continue
            hearings += Hearing(report.nodeId, n.nodeId, n.snr, report.receivedAt, fresh, byOurRadio = false)
        }
    }

    // What our radio heard directly: the latest over-the-air packet with 0 hops, else the radio's
    // own node list when the packets did not say.
    val byNum = nodes.associateBy { it.nodeNum }
    if (myNodeNum != 0L) {
        for (id in (byNum.keys + signals.keys)) {
            if (id == myNodeNum) continue
            val sig = signals[id]
            val node = byNum[id]
            if (sig != null && sig.hopsAway == 0) {
                hearings += Hearing(myNodeNum, id, sig.snr, sig.heardAt, now - sig.heardAt < FRESH_MS, byOurRadio = true)
            } else if ((sig == null || sig.hopsAway < 0) && node != null && node.hopsAway == 0 && node.lastHeard > 0) {
                hearings += Hearing(myNodeNum, id, node.snr, node.lastHeard, now - node.lastHeard < FRESH_MS, byOurRadio = true)
            }
        }
    }

    // One row per hearer and heard node, so the list below has unique keys.
    val unique = hearings.distinctBy { Triple(it.hearer, it.heard, it.byOurRadio) }
    hearings.clear()
    hearings += unique

    val links = hearings
        .groupBy { if (it.hearer < it.heard) it.hearer to it.heard else it.heard to it.hearer }
        .map { (pair, hs) -> Link(pair.first, pair.second, hs) }

    // Every node we know of: the node list, anything that transmitted, anything a report names.
    val ids = LinkedHashSet<Long>()
    if (myNodeNum != 0L) ids += myNodeNum
    nodes.forEach { ids += it.nodeNum }
    ids += signals.keys
    hearings.forEach { ids += it.hearer; ids += it.heard }

    // A stable order (your node, then by number), so the layout does not restart every time the
    // node list reorders itself on a NodeInfo update.
    val topoNodes = ids.sortedWith(compareBy<Long>({ it != myNodeNum }, { it })).map { id ->
        val info = byNum[id]
        val lastSeen = maxOf(
            info?.lastHeard ?: 0L,
            signals[id]?.heardAt ?: 0L,
            reports[id]?.receivedAt ?: 0L,
        )
        TopoNode(
            num = id,
            label = info?.shortName?.ifBlank { null } ?: MeshtasticProtocol.formatNodeId(id).takeLast(4),
            name = info?.longName?.ifBlank { null } ?: MeshtasticProtocol.formatNodeId(id),
            isMe = id == myNodeNum,
            lastSeen = lastSeen,
            info = info,
        )
    }
    return Topology(topoNodes, links, hearings.sortedByDescending { it.at })
}

@Composable
fun TopologyScreen() {
    val ble = GatewayService.meshtasticBle
    val connected = ble?.state?.collectAsState()?.value == MeshtasticBle.State.Connected
    val nodes = ble?.nodes?.collectAsState()?.value ?: emptyList()
    val myNodeNum = ble?.myInfo?.collectAsState()?.value?.myNodeNum ?: 0L
    val reports = ble?.neighborReports?.collectAsState()?.value ?: emptyMap()
    val signals = ble?.linkSignals?.collectAsState()?.value ?: emptyMap()
    val bluetoothRssi = ble?.rssi?.collectAsState()?.value ?: 0

    // Freshness moves with the clock, not only with new packets.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val topology = remember(nodes, myNodeNum, reports, signals, nowMs) {
        buildTopology(nodes, myNodeNum, reports, signals, nowMs)
    }

    // Silence is not an empty mesh: until another node transmits, say that it has not yet.
    if (topology.nodes.none { !it.isMe }) {
        EmptyTopology(connected)
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The graph sits above the list, never inside a scroll, so pinch and drag reach it.
        val canvasHeight = (maxHeight * 0.45f).coerceIn(200.dp, 360.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            TopologyCanvas(
                topology = topology,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .fillMaxWidth()
                    .height(canvasHeight),
            )
            TopologyDetails(
                topology = topology,
                reportsReceived = reports.isNotEmpty(),
                signals = signals,
                connected = connected,
                bluetoothRssi = bluetoothRssi,
                now = nowMs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmptyTopology(connected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (connected) "Your node is listening." else "Your node is not connected.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (connected) {
                    "Nodes appear here once they transmit on the mesh."
                } else {
                    "Connect your MeshSat node in Setup to see how the mesh is linked."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Force-directed layout: nodes push each other apart, real links pull their ends together, and a
 * weak pull to the centre keeps unlinked nodes on screen. Pinch to zoom, drag to move.
 */
@Composable
private fun TopologyCanvas(topology: Topology, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val ids = topology.nodes.map { it.num }
    val indexOf = remember(ids) { ids.withIndex().associate { (i, id) -> id to i } }
    val edges = remember(indexOf, topology.links) {
        topology.links.mapNotNull { l ->
            val i = indexOf[l.a] ?: return@mapNotNull null
            val j = indexOf[l.b] ?: return@mapNotNull null
            Triple(i, j, l.fresh)
        }
    }

    val positions = remember(ids) {
        val n = ids.size
        Array(n) { i ->
            val angle = 2.0 * PI * i / max(n, 1)
            floatArrayOf((120.0 * cos(angle)).toFloat(), (120.0 * sin(angle)).toFloat())
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ids, edges) {
        for (step in 0 until 90) {
            val temperature = 5f * (1f - step / 90f)
            simulateForces(positions, edges, temperature)
            tick++
            delay(16)
        }
    }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val labelColor = MeshSatTextSecondary.toArgb()
    val meLabelColor = MeshSatTextPrimary.toArgb()

    Box(
        modifier = modifier
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION")
            tick // redraw on every simulation step

            val n = positions.size
            if (n == 0) return@Canvas
            // Fit the layout to the canvas, then apply the user's zoom and pan.
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in positions) {
                minX = min(minX, p[0]); maxX = max(maxX, p[0])
                minY = min(minY, p[1]); maxY = max(maxY, p[1])
            }
            val pad = 36.dp.toPx()
            val spanX = max(maxX - minX, 1f)
            val spanY = max(maxY - minY, 1f)
            val fit = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).coerceIn(0.2f, 3f)
            val midX = (minX + maxX) / 2f
            val midY = (minY + maxY) / 2f
            fun screen(i: Int) = Offset(
                size.width / 2f + offsetX + (positions[i][0] - midX) * fit * scale,
                size.height / 2f + offsetY + (positions[i][1] - midY) * fit * scale,
            )

            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
            for ((i, j, fresh) in edges) {
                drawLine(
                    color = if (fresh) ColorMesh else MeshSatTextMuted.copy(alpha = 0.6f),
                    start = screen(i),
                    end = screen(j),
                    strokeWidth = (if (fresh) 2.dp else 1.5.dp).toPx(),
                    pathEffect = if (fresh) null else dash,
                )
            }

            val now = System.currentTimeMillis()
            labelPaint.textSize = 12.sp.toPx() // sp: follows the user's font size setting
            for (i in 0 until n) {
                val node = topology.nodes[i]
                val c = screen(i)
                val fresh = node.lastSeen > 0 && now - node.lastSeen < FRESH_MS
                val color = when {
                    node.isMe -> MeshSatTextPrimary
                    fresh -> MeshSatGreen
                    else -> MeshSatTextMuted
                }
                val r = (if (node.isMe) 9.dp else 6.dp).toPx()
                if (node.isMe) drawCircle(color = ColorMesh, radius = r + 3.dp.toPx(), center = c)
                drawCircle(color = color, radius = r, center = c)
                labelPaint.color = if (node.isMe) meLabelColor else labelColor
                drawContext.canvas.nativeCanvas.drawText(
                    node.label,
                    c.x,
                    c.y + r + 4.dp.toPx() + labelPaint.textSize,
                    labelPaint,
                )
            }
        }

        if (scale != 1f || offsetX != 0f || offsetY != 0f) {
            TextButton(
                onClick = { scale = 1f; offsetX = 0f; offsetY = 0f },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .heightIn(min = 48.dp),
            ) {
                Text("Reset view")
            }
        }
    }
}

private fun simulateForces(
    positions: Array<FloatArray>,
    edges: List<Triple<Int, Int, Boolean>>,
    temperature: Float,
) {
    val n = positions.size
    if (n < 2) return

    val forces = Array(n) { floatArrayOf(0f, 0f) }
    val repulsionK = 8000f
    val springK = 0.02f
    val gravityK = 0.01f

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

    // Springs along the links that were actually heard.
    for ((i, j, _) in edges) {
        if (i !in 0 until n || j !in 0 until n || i == j) continue
        val dx = positions[j][0] - positions[i][0]
        val dy = positions[j][1] - positions[i][1]
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val force = springK * dist
        val fx = force * dx / dist
        val fy = force * dy / dist
        forces[i][0] += fx
        forces[i][1] += fy
        forces[j][0] -= fx
        forces[j][1] -= fy
    }

    for (i in 0 until n) {
        forces[i][0] -= gravityK * positions[i][0]
        forces[i][1] -= gravityK * positions[i][1]
        val fx = forces[i][0]
        val fy = forces[i][1]
        val mag = sqrt(fx * fx + fy * fy).coerceAtLeast(0.001f)
        val cap = min(mag, temperature * 10f)
        positions[i][0] = (positions[i][0] + cap * fx / mag).coerceIn(-300f, 300f)
        positions[i][1] = (positions[i][1] + cap * fy / mag).coerceIn(-300f, 300f)
    }
}

@Composable
private fun TopologyDetails(
    topology: Topology,
    reportsReceived: Boolean,
    signals: Map<Long, MeshtasticProtocol.MeshLinkSignal>,
    connected: Boolean,
    bluetoothRssi: Int,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val names = remember(topology) { topology.nodes.associate { it.num to (if (it.isMe) "Your node" else it.name) } }
    val others = topology.nodes.filter { !it.isMe }
    val recent = others.count { it.lastSeen > 0 && now - it.lastSeen < FRESH_MS }
    val directRecent = topology.hearings.filter { it.byOurRadio && it.fresh }
    val avgSnr = if (directRecent.isEmpty()) null else directRecent.map { it.snr }.average()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Pinch to zoom, drag to move.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatItem("Heard in 15 min", "$recent of ${others.size}", Modifier.weight(1f))
                StatItem("Links", "${topology.links.size}", Modifier.weight(1f))
                StatItem(
                    "Average SNR, heard directly",
                    avgSnr?.let { "%.1f dB".format(it) } ?: "-",
                    Modifier.weight(1f),
                )
            }
        }

        if (!reportsReceived) {
            item {
                Notice(
                    "Links appear when nodes share who they hear. This needs Neighbor Info turned on " +
                        "in the nodes' module settings.",
                )
            }
        }
        if (!connected) {
            item { Notice("Your node is not connected, so this is what it heard last.") }
        }

        if (topology.hearings.isNotEmpty()) {
            item { SectionTitle("Who hears whom") }
            items(topology.hearings, key = { "${it.hearer}>${it.heard}:${it.byOurRadio}" }) { h ->
                HearingRow(
                    hearer = names[h.hearer] ?: MeshtasticProtocol.formatNodeId(h.hearer),
                    heard = names[h.heard] ?: MeshtasticProtocol.formatNodeId(h.heard),
                    hearing = h,
                )
            }
        }

        item { SectionTitle("Nodes") }
        items(
            topology.nodes.sortedWith(compareBy<TopoNode>({ !it.isMe }, { -it.lastSeen })),
            key = { it.num },
        ) { node ->
            NodeRow(
                node = node,
                signal = signals[node.num],
                directSnr = topology.hearings.firstOrNull { it.byOurRadio && it.heard == node.num }?.snr,
                bluetoothRssi = bluetoothRssi,
                now = now,
            )
        }

        item { Legend() }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            maxLines = 2,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = PlexMono,
            color = MeshSatTextPrimary,
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MeshSatTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MeshSatTextSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun HearingRow(hearer: String, heard: String, hearing: Hearing) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkSample(fresh = hearing.fresh)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$hearer hears $heard",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hearing.fresh) MeshSatTextPrimary else MeshSatTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("SNR %.1f dB".format(hearing.snr))
                    append(", ")
                    append(Words.ago(hearing.at))
                    if (!hearing.byOurRadio) append(", as it reported")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: TopoNode,
    signal: MeshtasticProtocol.MeshLinkSignal?,
    directSnr: Float?,
    bluetoothRssi: Int,
    now: Long,
) {
    val fresh = node.lastSeen > 0 && now - node.lastSeen < FRESH_MS
    val dot = when {
        node.isMe -> MeshSatTextPrimary
        fresh -> MeshSatGreen
        else -> MeshSatTextMuted
    }
    val info = node.info

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .background(dot, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (node.isMe) "${node.name} (your node)" else node.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(MeshtasticProtocol.formatNodeId(node.num))
                    if (info != null && info.hwModel != 0) {
                        append("  ")
                        append(MeshtasticProtocol.hardwareName(info.hwModel))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = PlexMono,
                color = MeshSatTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildList {
                if (node.isMe) {
                    if (bluetoothRssi != 0) add("Bluetooth to phone $bluetoothRssi dBm")
                } else {
                    when {
                        directSnr != null -> {
                            add("Heard directly, SNR %.1f dB".format(directSnr))
                            if (signal != null && signal.hopsAway == 0 && signal.rssi != 0) add("RSSI ${signal.rssi} dBm")
                        }
                        signal != null && signal.hopsAway > 0 ->
                            add("${Words.count(signal.hopsAway, "hop")} away")
                        info != null && info.hopsAway > 0 ->
                            add("${Words.count(info.hopsAway, "hop")} away")
                    }
                }
                if (info != null && info.batteryLevel in 0..100) add("Battery ${info.batteryLevel}%")
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextSecondary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (!node.isMe) {
            Text(
                text = Words.ago(node.lastSeen, now),
                style = MaterialTheme.typography.bodySmall,
                color = if (fresh) MeshSatGreen else MeshSatTextMuted,
            )
        }
    }
}

@Composable
private fun LinkSample(fresh: Boolean) {
    Canvas(modifier = Modifier.size(width = 24.dp, height = 12.dp)) {
        drawLine(
            color = if (fresh) ColorMesh else MeshSatTextMuted.copy(alpha = 0.6f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 2.dp.toPx(),
            pathEffect = if (fresh) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f),
        )
    }
}

@Composable
private fun Legend() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LegendDot("Your node", MeshSatTextPrimary)
        LegendDot("Heard in the last 15 min", MeshSatGreen)
        LegendDot("Not heard for 15 min or more", MeshSatTextMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkSample(fresh = true)
            Spacer(Modifier.width(8.dp))
            Text("Recent link", style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkSample(fresh = false)
            Spacer(Modifier.width(8.dp))
            Text("Older link", style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
        }
        Text(
            text = "A line is drawn only where a node said it hears the other, or where your node heard it " +
                "directly. A node shows up once it transmits.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(horizontal = 7.dp)
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
    }
}
