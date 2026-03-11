package com.cubeos.meshsat.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.SignalRecord
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.components.StatusCard
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.SignalExcellent
import com.cubeos.meshsat.ui.theme.SignalFair
import com.cubeos.meshsat.ui.theme.SignalGood
import com.cubeos.meshsat.ui.theme.SignalPoor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    // Message stats (reactive)
    val totalMessages by db.messageDao().getRecent(9999).collectAsState(initial = emptyList())
    val totalCount = totalMessages.size
    val smsCount = totalMessages.count { it.transport == "sms" }
    val meshCount = totalMessages.count { it.transport == "mesh" }
    val iridiumCount = totalMessages.count { it.transport == "iridium" }
    val encryptedCount = totalMessages.count { it.encrypted }
    val rxCount = totalMessages.count { it.direction == "rx" }
    val txCount = totalMessages.count { it.direction == "tx" }
    val fwdCount = totalMessages.count { it.forwarded }

    // Messages in last hour
    val oneHourAgo = System.currentTimeMillis() - 3600_000
    val recentCount = totalMessages.count { it.timestamp > oneHourAgo }

    // Observe real transport state
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val iridiumState = GatewayService.iridiumSpp?.state?.collectAsState()
    val iridiumSignal = GatewayService.iridiumSpp?.signal?.collectAsState()
    val modemInfo = GatewayService.iridiumSpp?.modemInfo?.collectAsState()
    val meshRssi = GatewayService.meshtasticBle?.rssi?.collectAsState()

    // Mesh node info
    val meshMyInfo = GatewayService.meshtasticBle?.myInfo?.collectAsState()
    val meshNodes = GatewayService.meshtasticBle?.nodes?.collectAsState()

    // Signal history — 6 hours
    val sixHoursAgo = System.currentTimeMillis() - 6 * 3600_000
    val iridiumHistory by db.signalDao().getSince("iridium", sixHoursAgo).collectAsState(initial = emptyList())
    val meshHistory by db.signalDao().getSince("mesh", sixHoursAgo).collectAsState(initial = emptyList())
    val cellularHistory by db.signalDao().getSince("cellular", sixHoursAgo).collectAsState(initial = emptyList())

    // Phone GPS
    val phoneLocation by GatewayService.phoneLocation.collectAsState()

    // SOS state
    val sosActive by GatewayService.sosActive.collectAsState()
    val sosSends by GatewayService.sosSends.collectAsState()
    var showSosDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Dashboard",
            style = MaterialTheme.typography.headlineMedium,
        )

        // SOS Button
        if (sosActive) {
            Button(
                onClick = {
                    context.startService(
                        Intent(context, GatewayService::class.java)
                            .setAction(GatewayService.ACTION_SOS_CANCEL)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "SOS ACTIVE - Sends: $sosSends/3 - TAP TO CANCEL",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            Button(
                onClick = { showSosDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MeshSatRed.copy(alpha = 0.2f),
                    contentColor = MeshSatRed,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("SOS Emergency", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Transport status cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val meshConnected = meshState?.value == MeshtasticBle.State.Connected
            val meshConnecting = meshState?.value == MeshtasticBle.State.Connecting
                    || meshState?.value == MeshtasticBle.State.Scanning
            val rssi = meshRssi?.value ?: 0
            StatusCard(
                title = "Meshtastic",
                status = when {
                    meshConnected -> if (rssi != 0) "RSSI: ${rssi}dBm" else "Connected"
                    meshConnecting -> "Connecting..."
                    else -> "Disconnected"
                },
                isOnline = meshConnected,
                color = if (meshConnected && rssi != 0) rssiColor(rssi) else ColorMesh,
                detail = when {
                    meshConnected && meshMyInfo?.value != null -> {
                        val fw = meshMyInfo?.value?.firmwareVersion ?: ""
                        if (fw.isNotBlank()) "FW: $fw" else "BLE"
                    }
                    else -> "BLE"
                },
                modifier = Modifier.weight(1f),
            )

            val iridiumConnected = iridiumState?.value == IridiumSpp.State.Connected
            val iridiumConnecting = iridiumState?.value == IridiumSpp.State.Connecting
            val sig = iridiumSignal?.value ?: 0
            StatusCard(
                title = "Iridium",
                status = when {
                    iridiumConnected -> "Signal: $sig/5"
                    iridiumConnecting -> "Connecting..."
                    else -> "Disconnected"
                },
                isOnline = iridiumConnected,
                color = if (iridiumConnected) iridiumSignalColor(sig) else ColorIridium,
                detail = modemInfo?.value?.let {
                    if (it.imei.isNotBlank()) "IMEI: ${it.imei}" else "HC-05 SPP"
                } ?: "HC-05 SPP",
                modifier = Modifier.weight(1f),
            )
        }

        // Cellular status
        val latestCell = cellularHistory.lastOrNull()?.value
        StatusCard(
            title = "Cellular SMS",
            status = if (latestCell != null) "Signal: ${latestCell}dBm" else "Ready",
            isOnline = true,
            color = if (latestCell != null) cellularSignalColor(latestCell) else ColorCellular,
            detail = "Native SMS",
            modifier = Modifier.fillMaxWidth(),
        )

        // GPS location
        phoneLocation?.let { loc ->
            DashboardWidget(title = "Phone GPS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "%.5f, %.5f".format(loc.latitude, loc.longitude),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "alt ${loc.altitude.toInt()}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
                Text(
                    text = "accuracy: ${loc.accuracy.toInt()}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        // Message Queue widget
        DashboardWidget(title = "Message Activity") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatColumn("Total", totalCount.toString(), MeshSatTeal)
                StatColumn("RX", rxCount.toString(), SignalGood)
                StatColumn("TX", txCount.toString(), SignalFair)
                StatColumn("FWD", fwdCount.toString(), ColorIridium)
                StatColumn("ENC", encryptedCount.toString(), Color(0xFFF59E0B))
            }
            Text(
                text = "$recentCount messages in last hour",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Transport breakdown
        DashboardWidget(title = "Transport Breakdown") {
            TransportBar(meshCount, smsCount, iridiumCount, totalCount)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TransportLabel("Mesh", meshCount, ColorMesh)
                TransportLabel("SMS", smsCount, ColorCellular)
                TransportLabel("Iridium", iridiumCount, ColorIridium)
            }
        }

        // Mesh nodes (if connected)
        val nodeList = meshNodes?.value
        if (!nodeList.isNullOrEmpty()) {
            DashboardWidget(title = "Mesh Nodes (${nodeList.size})") {
                nodeList.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = node.longName.ifBlank { "!%08x".format(node.nodeNum) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = node.shortName.ifBlank { "" },
                            style = MaterialTheme.typography.bodySmall,
                            color = ColorMesh,
                        )
                    }
                }
            }
        }

        // Signal history graphs — 6 hours
        if (iridiumHistory.isNotEmpty()) {
            SignalGraph(
                title = "Iridium Signal (6h)",
                records = iridiumHistory,
                maxValue = 5f,
                minValue = 0f,
                color = ColorIridium,
                formatValue = { "${it.toInt()}/5" },
            )
        }

        if (meshHistory.isNotEmpty()) {
            SignalGraph(
                title = "Mesh BLE RSSI (6h)",
                records = meshHistory,
                maxValue = -30f,
                minValue = -100f,
                color = ColorMesh,
                formatValue = { "${it.toInt()}dBm" },
            )
        }

        if (cellularHistory.isNotEmpty()) {
            SignalGraph(
                title = "Cellular Signal (6h)",
                records = cellularHistory,
                maxValue = -50f,
                minValue = -120f,
                color = ColorCellular,
                formatValue = { "${it.toInt()}dBm" },
            )
        }
    }

    // SOS confirmation dialog
    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            containerColor = MeshSatSurface,
            title = { Text("SOS Emergency") },
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
                    Text("SEND SOS", color = MeshSatRed)
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

@Composable
private fun DashboardWidget(title: String, content: @Composable () -> Unit) {
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

@Composable
private fun StatColumn(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

@Composable
private fun TransportBar(mesh: Int, sms: Int, iridium: Int, total: Int) {
    if (total == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MeshSatBorder, RoundedCornerShape(4.dp)),
    ) {
        if (mesh > 0) {
            Box(
                modifier = Modifier
                    .weight(mesh.toFloat() / total)
                    .height(8.dp)
                    .background(ColorMesh, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
            )
        }
        if (sms > 0) {
            Box(
                modifier = Modifier
                    .weight(sms.toFloat() / total)
                    .height(8.dp)
                    .background(ColorCellular)
            )
        }
        if (iridium > 0) {
            Box(
                modifier = Modifier
                    .weight(iridium.toFloat() / total)
                    .height(8.dp)
                    .background(ColorIridium, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
            )
        }
    }
}

@Composable
private fun TransportLabel(label: String, count: Int, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

@Composable
private fun SignalGraph(
    title: String,
    records: List<SignalRecord>,
    maxValue: Float,
    minValue: Float,
    color: Color,
    formatValue: (Float) -> String,
) {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
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
        val points = records.map { it.value.toFloat() }
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

            // Dots (only if fewer than 60 to avoid clutter)
            if (points.size < 60) {
                points.forEachIndexed { i, v ->
                    drawCircle(color, radius = 2.5f.dp.toPx(), center = Offset(i * stepX, yFor(v)))
                }
            }
        }

        // Min/max labels
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

@Composable
private fun iridiumSignalColor(signal: Int) = when {
    signal >= 4 -> SignalExcellent
    signal >= 3 -> SignalGood
    signal >= 2 -> SignalFair
    else -> SignalPoor
}

@Composable
private fun rssiColor(rssi: Int) = when {
    rssi >= -60 -> SignalExcellent
    rssi >= -70 -> SignalGood
    rssi >= -80 -> SignalFair
    else -> SignalPoor
}

@Composable
private fun cellularSignalColor(dbm: Int) = when {
    dbm >= -80 -> SignalExcellent
    dbm >= -90 -> SignalGood
    dbm >= -100 -> SignalFair
    else -> SignalPoor
}
