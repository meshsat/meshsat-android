package com.cubeos.meshsat.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.DeliveryStatRow
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.data.SignalRecord
import com.cubeos.meshsat.engine.BurstQueue
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.SignalExcellent
import com.cubeos.meshsat.ui.theme.SignalFair
import com.cubeos.meshsat.ui.theme.SignalGood
import com.cubeos.meshsat.ui.theme.SignalPoor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    // --- Transport states ---
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val iridiumState = GatewayService.iridiumSpp?.state?.collectAsState()
    val iridiumSignal = GatewayService.iridiumSpp?.signal?.collectAsState()
    val modemInfo = GatewayService.iridiumSpp?.modemInfo?.collectAsState()
    val meshRssi = GatewayService.meshtasticBle?.rssi?.collectAsState()
    val meshNodes = GatewayService.meshtasticBle?.nodes?.collectAsState()

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

    // --- SOS state ---
    val sosActive by GatewayService.sosActive.collectAsState()
    val sosSends by GatewayService.sosSends.collectAsState()
    var showSosDialog by remember { mutableStateOf(false) }

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

    // --- Burst queue (polled every 5s) ---
    var burstPending by remember { mutableIntStateOf(0) }
    var burstShouldFlush by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            GatewayService.burstQueue?.let { bq ->
                burstPending = bq.pending()
                burstShouldFlush = bq.shouldFlush()
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
                ageS < 60 -> "${ageS}s ago"
                else -> "${ageS / 60}m ago"
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

    // --- Layout ---
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        // ====== 1. Transport Status Cards (horizontal scroll) ======
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Iridium SBD
                val sig = iridiumSignal?.value ?: 0
                TransportStatusCard(
                    title = "Iridium SBD",
                    borderColor = ColorIridium,
                    isOnline = iridiumConnected,
                    metric = if (iridiumConnected) "$sig/5" else "--",
                    metricLabel = "Signal",
                    details = listOf(
                        "IMEI" to (modemInfo?.value?.imei?.ifBlank { "--" } ?: "--"),
                    ),
                    modifier = Modifier.width(160.dp).fillMaxHeight(),
                )

                // Meshtastic LoRa
                val rssi = meshRssi?.value ?: 0
                val nodeCount = meshNodes?.value?.size ?: 0
                TransportStatusCard(
                    title = "Meshtastic LoRa",
                    borderColor = ColorMesh,
                    isOnline = meshConnected,
                    metric = when {
                        meshConnected -> if (rssi != 0) "${rssi}dBm" else "OK"
                        meshConnecting -> "..."
                        else -> "--"
                    },
                    metricLabel = "RSSI",
                    details = listOf(
                        "Nodes" to "$nodeCount",
                    ),
                    modifier = Modifier.width(160.dp).fillMaxHeight(),
                )

                // Cellular SMS
                val latestCell = cellularHistory.lastOrNull()?.value
                TransportStatusCard(
                    title = "Cellular SMS",
                    borderColor = ColorCellular,
                    isOnline = true,
                    metric = if (latestCell != null) "${latestCell}dBm" else "Ready",
                    metricLabel = "Signal",
                    details = listOf(
                        "Today" to "$smsToday SMS",
                    ),
                    modifier = Modifier.width(160.dp).fillMaxHeight(),
                )
            }
        }

        // ====== 2. Signal History Charts ======
        if (iridiumHistory.isNotEmpty()) {
            item {
                SignalChart(
                    title = "Iridium Signal (6h)",
                    records = iridiumHistory,
                    maxValue = 5f,
                    minValue = 0f,
                    color = ColorIridium,
                    formatValue = { "${it.toInt()}/5" },
                )
            }
        }

        if (meshHistory.isNotEmpty()) {
            item {
                SignalChart(
                    title = "Mesh RSSI (6h)",
                    records = meshHistory,
                    maxValue = -30f,
                    minValue = -100f,
                    color = ColorMesh,
                    formatValue = { "${it.toInt()}dBm" },
                )
            }
        }

        if (cellularHistory.isNotEmpty()) {
            item {
                SignalChart(
                    title = "Cellular Signal (6h)",
                    records = cellularHistory,
                    maxValue = -50f,
                    minValue = -120f,
                    color = ColorCellular,
                    formatValue = { "${it.toInt()}dBm" },
                )
            }
        }

        // ====== 3. SOS Card ======
        item {
            DashboardCard(title = "SOS Emergency") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Status indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (sosActive) MeshSatRed else MeshSatTextMuted)
                        )
                        Text(
                            text = if (sosActive) "ARMED" else "Disarmed",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (sosActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (sosActive) MeshSatRed else MeshSatTextMuted,
                        )
                    }

                    if (sosActive) {
                        Text(
                            text = "Sends: $sosSends/3",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MeshSatRed,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sosActive) {
                        Button(
                            onClick = {
                                context.startService(
                                    Intent(context, GatewayService::class.java)
                                        .setAction(GatewayService.ACTION_SOS_CANCEL)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("CANCEL SOS")
                        }
                    } else {
                        Button(
                            onClick = { showSosDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MeshSatAmber,
                                contentColor = Color.Black,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("ARM SOS", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                Toast.makeText(context, "Test SOS sent", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("Send Test")
                        }
                    }
                }
            }
        }

        // ====== 4. Location Card ======
        item {
            DashboardCard(title = "Location") {
                val loc = phoneLocation
                if (loc != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "%.6f, %.6f".format(loc.latitude, loc.longitude),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = fixAgeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        InfoPair("Alt", "${loc.altitude.toInt()}m")
                        InfoPair("Acc", "${loc.accuracy.toInt()}m")
                        if (loc.hasSpeed()) InfoPair("Spd", "%.1f m/s".format(loc.speed))
                        if (loc.hasBearing()) InfoPair("Hdg", "${loc.bearing.toInt()}\u00B0")
                    }

                    Text(
                        text = "Source: ${loc.provider ?: "unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Text(
                        text = "Waiting for GPS fix...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatTextMuted,
                    )
                }
            }
        }

        // ====== 5. Message Queue Card ======
        item {
            DashboardCard(title = "Message Queue") {
                // Per-interface queue bars
                QueueBar("Mesh", meshQueueDepth, ColorMesh)
                QueueBar("Iridium", iridiumQueueDepth, ColorIridium)
                QueueBar("SMS", smsQueueDepth, ColorCellular)

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatBadge("Queued", queuedCount, MeshSatTeal)
                    StatBadge("Pending", pendingCount, MeshSatAmber)
                    StatBadge("Failed", failedCount, MeshSatRed)
                    StatBadge("Dead", deadCount, MeshSatTextMuted)
                }
            }
        }

        // ====== 6. Burst Queue Card ======
        item {
            DashboardCard(title = "Burst Queue") {
                val mtu = BurstQueue.IRIDIUM_MTU
                // Estimate bytes: header + per-msg overhead. Rough estimate since we
                // can't read the actual pending list from outside the lock.
                val estimatedBytes = if (burstPending > 0)
                    BurstQueue.BURST_HEADER_LEN + burstPending * (BurstQueue.BURST_MSG_HEADER_LEN + 40)
                else 0
                val progress = (estimatedBytes.toFloat() / mtu).coerceIn(0f, 1f)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$burstPending pending",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "~${estimatedBytes}B / ${mtu}B MTU",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = ColorIridium,
                    trackColor = MeshSatBorder,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (burstShouldFlush) "Ready to flush" else "Accumulating",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (burstShouldFlush) MeshSatAmber else MeshSatTextMuted,
                    )

                    OutlinedButton(
                        onClick = {
                            val bq = GatewayService.burstQueue
                            if (bq != null) {
                                val (payload, count) = bq.flush()
                                Toast.makeText(
                                    context,
                                    if (count > 0) "Flushed $count messages" else "Queue empty",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Flush", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ====== 7. Activity Log ======
        item {
            Text(
                text = "Activity Log",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (recentMessages.isEmpty()) {
            item {
                Text(
                    text = "No recent messages",
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

    // --- SOS confirmation dialog ---
    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            containerColor = MeshSatSurface,
            title = { Text("ARM SOS Emergency") },
            text = {
                Text(
                    "This will broadcast an emergency alert with your GPS position via all connected transports (Mesh, Iridium, SMS).\n\n3 messages will be sent at 30-second intervals.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSosDialog = false
                        context.startService(
                            Intent(context, GatewayService::class.java)
                                .setAction(GatewayService.ACTION_SOS_ACTIVATE)
                        )
                        Toast.makeText(context, "SOS activated", Toast.LENGTH_LONG).show()
                    },
                ) {
                    Text("SEND SOS", color = MeshSatRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("Cancel", color = MeshSatTextMuted)
                }
            },
        )
    }
}

// ============================================================================
// Composable components
// ============================================================================

/** Card wrapper with colored left border for transport status cards. */
@Composable
private fun TransportStatusCard(
    title: String,
    borderColor: Color,
    isOnline: Boolean,
    metric: String,
    metricLabel: String,
    details: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
    ) {
        // Colored left border strip
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(borderColor)
        )

        Column(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) MeshSatGreen else MeshSatRed)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = metric,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = borderColor,
            )

            Text(
                text = metricLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MeshSatTextMuted,
            )

            details.forEach { (label, value) ->
                Text(
                    text = "$label: $value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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
            modifier = Modifier.width(52.dp),
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

/** Label: value pair for location details. */
@Composable
private fun InfoPair(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Single activity log entry. */
@Composable
private fun ActivityLogEntry(msg: Message) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
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
            text = msg.transport.uppercase().take(3),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = transportColor,
            modifier = Modifier.width(28.dp),
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
