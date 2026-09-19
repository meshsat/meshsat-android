package net.meshsat.android.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.DeliveryStatRow
import net.meshsat.android.data.Message
import net.meshsat.android.data.SignalRecord
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.theme.ColorCellular
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.OffWhite
import net.meshsat.android.ui.theme.SignalExcellent
import net.meshsat.android.ui.theme.SignalFair
import net.meshsat.android.ui.theme.SignalGood
import net.meshsat.android.ui.theme.SignalPoor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.meshsat.android.ui.components.collectOrNull

@Composable
fun DashboardScreen(navigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    // --- Transport states ---
    val meshState = GatewayService.meshtasticBle?.state.collectOrNull()
    val iridiumState = GatewayService.iridiumSpp?.state.collectOrNull()
    val iridiumSignal = GatewayService.iridiumSpp?.signal.collectOrNull()
    val modemInfo = GatewayService.iridiumSpp?.modemInfo.collectOrNull()
    val meshRssi = GatewayService.meshtasticBle?.rssi.collectOrNull()
    val meshNodes = GatewayService.meshtasticBle?.nodes.collectOrNull()

    val meshConnected = meshState?.value == MeshtasticBle.State.Connected
    val meshConnecting = meshState?.value == MeshtasticBle.State.Connecting
            || meshState?.value == MeshtasticBle.State.Scanning
    val iridiumConnected = iridiumState?.value == IridiumSpp.State.Connected
    val iridiumConnecting = iridiumState?.value == IridiumSpp.State.Connecting

    // --- Signal history (6h) ---
    val sixHoursAgo = System.currentTimeMillis() - 6 * 3600_000
    val iridiumHistory by db.signalDao().getSince("iridium", sixHoursAgo).collectAsState(initial = emptyList())
    val meshHistory by db.signalDao().getSince("mesh", sixHoursAgo).collectAsState(initial = emptyList())
    val cellularHistory by db.signalDao().getSince("cellular", sixHoursAgo).collectAsState(initial = emptyList())

    // --- Phone GPS ---
    val phoneLocation by GatewayService.phoneLocation.collectAsState()

    // --- Message stats (reactive via Flow) ---
    val recentMessages by db.messageDao().getRecent(20).collectAsState(initial = emptyList())
    val totalMessages by db.messageDao().getRecent(9999).collectAsState(initial = emptyList())
    val smsToday = totalMessages.count {
        it.transport == "sms" && it.timestamp > System.currentTimeMillis() - 86_400_000
    }

    // --- Delivery queue stats (polled every 5s) ---
    var deliveryStats by remember { mutableStateOf<List<DeliveryStatRow>>(emptyList()) }
    var meshQueueDepth by remember { mutableIntStateOf(0) }
    var iridiumQueueDepth by remember { mutableIntStateOf(0) }
    var smsQueueDepth by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                deliveryStats = db.messageDeliveryDao().stats()
                meshQueueDepth = db.messageDeliveryDao().queueDepth("mesh_0")
                iridiumQueueDepth = db.messageDeliveryDao().queueDepth("iridium_0")
                smsQueueDepth = db.messageDeliveryDao().queueDepth("sms_0")
            }
            delay(5_000)
        }
    }

    // --- Fix age tracking ---
    var fixAgeText by remember { mutableStateOf("--") }
    var lastFixMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phoneLocation) {
        phoneLocation?.let { loc ->
            lastFixMs = loc.time
        }
    }
    LaunchedEffect(lastFixMs) {
        while (lastFixMs > 0) {
            val ageS = (System.currentTimeMillis() - lastFixMs) / 1000
            fixAgeText = when {
                ageS < 5 -> "just now"
                ageS < 60 -> "$ageS s ago"
                else -> "${ageS / 60} min ago"
            }
            delay(5_000)
        }
    }

    // --- Delivery stat helpers ---
    fun statCount(status: String): Int =
        deliveryStats.filter { it.status == status }.sumOf { it.cnt }
    val queuedCount = statCount("queued") + statCount("retry") + statCount("held")
    val pendingCount = statCount("sending")
    val failedCount = statCount("failed")
    val deadCount = statCount("dead")

    // --- Dashboard card order (MESHSAT-401) ---
    val scope = rememberCoroutineScope()
    val settings = remember { net.meshsat.android.data.SettingsRepository(context) }
    var cardOrder by remember { mutableStateOf(HOME_CARDS) }
    var showReorderDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val saved = settings.dashboardOrder.first()
        cardOrder = homeOrder(saved)
    }

    if (showReorderDialog) {
        ReorderDialog(
            cards = cardOrder,
            onDismiss = { showReorderDialog = false },
            onConfirm = { newOrder ->
                cardOrder = newOrder
                scope.launch { settings.setDashboardOrder(newOrder.joinToString(",")) }
                showReorderDialog = false
            },
        )
    }

    // --- Layout ---
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HomeHeader(onArrange = { showReorderDialog = true }) }
        item { HomeLanes(navigate) }

        // The cards below follow the order set with Arrange (MESHSAT-401), which the old layout
        // saved but never applied (MESHSAT-1249).
        for (card in cardOrder) {
            when (card) {
                "mailbox" -> {
                // ====== Iridium mailbox, on request only: each check is billed (MESHSAT-400) ======
                if (iridiumConnected) {
                    item {
                        DashboardCard(title = "Satellite mailbox") {
                            net.meshsat.android.ui.components.CheckMailboxButton()
                        }
                    }
                }
                }
                "signals" -> {
                // ====== 2. Signal History Charts ======
                if (iridiumHistory.isNotEmpty()) {
                    item {
                        SignalChart(
                            title = "Satellite signal, last 6 hours",
                            records = iridiumHistory,
                            maxValue = 5f,
                            minValue = 0f,
                            color = ColorIridium,
                            formatValue = { "${it.toInt()} of 5" },
                        )
                    }
                }

                if (meshHistory.isNotEmpty()) {
                    item {
                        SignalChart(
                            title = "Mesh signal strength, last 6 hours",
                            records = meshHistory,
                            maxValue = -30f,
                            minValue = -100f,
                            color = ColorMesh,
                            formatValue = { "${it.toInt()} dBm" },
                        )
                    }
                }

                if (cellularHistory.isNotEmpty()) {
                    item {
                        SignalChart(
                            title = "Mobile signal, last 6 hours",
                            records = cellularHistory,
                            maxValue = -50f,
                            minValue = -120f,
                            color = ColorCellular,
                            formatValue = { "${it.toInt()} dBm" },
                        )
                    }
                }
                }
                "sos" -> {
                // Hold to send, or where the SOS in progress stands (MESHSAT-1249).
                item { SosCard(navigate) }
                }
                "location" -> {
                // Plain words, and a decimal point whatever the phone's language: the card showed
                // "52,162067, 4,509740" on a Dutch phone (MESHSAT-1249).
                item {
                    DashboardCard(title = "Your position") {
                        val loc = phoneLocation
                        if (loc != null) {
                            Text(
                                text = String.format(Locale.ROOT, "%.5f, %.5f", loc.latitude, loc.longitude),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val within = if (loc.hasAccuracy()) "within ${loc.accuracy.toInt()} m, " else ""
                            Text(
                                text = "$within$fixAgeText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshSatTextSecondary,
                            )
                            val extra = buildList {
                                if (loc.hasAltitude()) add("Height ${loc.altitude.toInt()} m")
                                if (loc.hasSpeed() && loc.speed >= 0.5f) {
                                    add(String.format(Locale.ROOT, "moving %.1f km/h", loc.speed * 3.6f) +
                                        if (loc.hasBearing()) ", heading ${loc.bearing.toInt()}\u00B0" else "")
                                }
                            }
                            if (extra.isNotEmpty()) {
                                Text(
                                    text = extra.joinToString(". ") + ".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MeshSatTextMuted,
                                )
                            }
                        } else {
                            Text(
                                text = "Waiting for a position. Location must be allowed, and the phone needs a view of the sky.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MeshSatTextMuted,
                            )
                        }
                    }
                }
                }
                "queue" -> {
                // The same words as the queue screen (Words.deliveryState), and a way to it.
                item {
                    DashboardCard(title = "Message queue") {
                        QueueBar("Satellite", iridiumQueueDepth, ColorIridium)
                        QueueBar("Mesh", meshQueueDepth, ColorMesh)
                        QueueBar("SMS", smsQueueDepth, ColorCellular)

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatBadge("Waiting", queuedCount, MeshSatAmber)
                            StatBadge("Sending", pendingCount, MeshSatAmber)
                            StatBadge("Failed", failedCount, MeshSatRed)
                            StatBadge("Gave up", deadCount, MeshSatTextMuted)
                        }
                        TextButton(onClick = { navigate("deliveries") }) {
                            Text("Open the queue", color = OffWhite)
                        }
                    }
                }
                }
                "activity" -> {
                // ====== 7. Activity Log ======
                item {
                    Text(
                        text = "Recent messages",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (recentMessages.isEmpty()) {
                    item {
                        Text(
                            text = "No messages yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatTextMuted,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(
                        items = recentMessages,
                        key = { it.id },
                    ) { msg ->
                        ActivityLogEntry(msg)
                    }
                }
                }
            }
        }
    }

}

// ============================================================================
// Composable components
// ============================================================================

/** Reusable card container. */
@Composable
private fun DashboardCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** Signal sparkline chart with 30-min bucket averaging and area fill. */
@Composable
private fun SignalChart(
    title: String,
    records: List<SignalRecord>,
    maxValue: Float,
    minValue: Float,
    color: Color,
    formatValue: (Float) -> String,
) {
    // Bucket into 30-min intervals
    val bucketMs = 30 * 60_000L
    val buckets = records
        .groupBy { it.timestamp / bucketMs }
        .toSortedMap()
        .map { (_, recs) -> recs.map { it.value.toFloat() }.average().toFloat() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val latest = records.lastOrNull()?.value?.toFloat() ?: 0f
            Text(formatValue(latest), style = MaterialTheme.typography.bodyMedium, color = color)
        }

        // Time axis labels
        if (records.size >= 2) {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = fmt.format(Date(records.first().timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
                Text(
                    text = fmt.format(Date(records.last().timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        val range = maxValue - minValue
        val points = if (buckets.size >= 2) buckets else records.map { it.value.toFloat() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(top = 4.dp),
        ) {
            if (points.size < 2) return@Canvas

            val w = size.width
            val h = size.height
            val stepX = w / (points.size - 1).coerceAtLeast(1)

            fun yFor(v: Float): Float = h - ((v - minValue) / range).coerceIn(0f, 1f) * h

            // Grid lines
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(
                    MeshSatBorder,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 0.5f,
                )
            }

            // Area fill
            val areaPath = Path().apply {
                moveTo(0f, h)
                points.forEachIndexed { i, v ->
                    lineTo(i * stepX, yFor(v))
                }
                lineTo((points.size - 1) * stepX, h)
                close()
            }
            drawPath(areaPath, color.copy(alpha = 0.12f))

            // Line
            val linePath = Path().apply {
                points.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = yFor(v)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(linePath, color, style = Stroke(width = 2.dp.toPx()))

            // Dots (only if not too many)
            if (points.size < 30) {
                points.forEachIndexed { i, v ->
                    drawCircle(color, radius = 2.5f.dp.toPx(), center = Offset(i * stepX, yFor(v)))
                }
            }
        }

        // Min/avg/max labels
        if (points.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val min = points.minOrNull() ?: 0f
                val max = points.maxOrNull() ?: 0f
                Text(
                    text = "min: ${formatValue(min)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
                Text(
                    text = "avg: ${formatValue(points.average().toFloat())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
                Text(
                    text = "max: ${formatValue(max)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }
        }
    }
}

/** Small queue depth bar for a single interface. */
@Composable
private fun QueueBar(label: String, depth: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.width(64.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(MeshSatBorder, RoundedCornerShape(3.dp)),
        ) {
            if (depth > 0) {
                val maxBar = 20 // scale: 20 = full bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (depth.toFloat() / maxBar).coerceIn(0.05f, 1f))
                        .height(6.dp)
                        .background(color, RoundedCornerShape(3.dp))
                )
            }
        }
        Text(
            text = "$depth",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
        )
    }
}

/** Stat with number and label. */
@Composable
private fun StatBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MeshSatTextMuted,
        )
    }
}

/** Single activity log entry. */
@Composable
private fun ActivityLogEntry(msg: Message) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val transportColor = when (msg.transport) {
        "mesh" -> ColorMesh
        "iridium" -> ColorIridium
        "sms" -> ColorCellular
        else -> MeshSatTextMuted
    }
    val directionArrow = if (msg.direction == "tx") "\u2191" else "\u2193"
    val directionColor = when {
        msg.direction == "tx" -> MeshSatGreen
        msg.direction == "rx" -> MeshSatTeal
        else -> MeshSatTextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Timestamp
        Text(
            text = timeFmt.format(Date(msg.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MeshSatTextMuted,
        )

        // Transport badge
        Text(
            text = net.meshsat.android.ui.Words.transport(msg.transport),
            style = MaterialTheme.typography.labelSmall,
            color = transportColor,
            maxLines = 1,
            modifier = Modifier.width(64.dp),
        )

        // Direction arrow
        Text(
            text = directionArrow,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = directionColor,
        )

        // Text preview
        Text(
            text = msg.text.take(80).replace('\n', ' '),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ============================================================================
// Reorder Dialog (MESHSAT-401)
// ============================================================================

/** Home's cards, in their default order, and what Arrange calls them. */
// No "burst" card (MESHSAT-1249): nothing in the app, the Bridge or the Hub fills a burst queue, the
// Hub cannot decode its frame, and its Flush dropped whatever it held. A saved order that still names it
// simply loses it (homeOrder keeps only known cards).
private val HOME_CARDS = listOf("sos", "queue", "location", "signals", "mailbox", "activity")

private val CARD_LABELS = mapOf(
    "sos" to "SOS",
    "queue" to "Message queue",
    "location" to "Location",
    "signals" to "Signal history",
    "mailbox" to "Satellite mailbox",
    "activity" to "Recent messages",
)

/**
 * The saved order, cleaned: cards that no longer exist ("transports" became the lanes, "reticulum"
 * never existed) are dropped, and cards added since are appended.
 */
private fun homeOrder(saved: String): List<String> {
    // Nobody arranged Home yet (empty, or the old built-in default): use the current default.
    if (saved.isBlank() || saved == "transports,signals,sos,location,queue,burst,reticulum,activity") return HOME_CARDS
    val kept = saved.split(",").map { it.trim() }.filter { it in HOME_CARDS }.distinct()
    return kept + HOME_CARDS.filter { it !in kept }
}

@Composable
private fun ReorderDialog(
    cards: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var order by remember { mutableStateOf(cards.toMutableList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arrange Home") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Move a card up or down. The lanes stay at the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                Spacer(modifier = Modifier.height(8.dp))
                order.forEachIndexed { index, cardId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MeshSatSurface, RoundedCornerShape(6.dp))
                            .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = CARD_LABELS[cardId] ?: cardId,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val newOrder = order.toMutableList()
                                        newOrder[index] = newOrder[index - 1].also {
                                            newOrder[index - 1] = newOrder[index]
                                        }
                                        order = newOrder
                                    }
                                },
                                enabled = index > 0,
                            ) {
                                Text(
                                    "\u25B2",
                                    color = if (index > 0) MeshSatTeal else MeshSatBorder,
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (index < order.size - 1) {
                                        val newOrder = order.toMutableList()
                                        newOrder[index] = newOrder[index + 1].also {
                                            newOrder[index + 1] = newOrder[index]
                                        }
                                        order = newOrder
                                    }
                                },
                                enabled = index < order.size - 1,
                            ) {
                                Text(
                                    "\u25BC",
                                    color = if (index < order.size - 1) MeshSatTeal else MeshSatBorder,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(order) },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
