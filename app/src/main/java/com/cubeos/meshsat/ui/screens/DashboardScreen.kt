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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    // Use Flow for reactive updates
    val allMessages by db.messageDao().getRecent(999).collectAsState(initial = emptyList())
    val totalMessages = allMessages.size
    val smsCount = allMessages.count { it.transport == "sms" }
    val meshCount = allMessages.count { it.transport == "mesh" }
    val encryptedCount = allMessages.count { it.encrypted }

    // Observe real transport state
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val iridiumState = GatewayService.iridiumSpp?.state?.collectAsState()
    val iridiumSignal = GatewayService.iridiumSpp?.signal?.collectAsState()
    val modemInfo = GatewayService.iridiumSpp?.modemInfo?.collectAsState()
    val meshRssi = GatewayService.meshtasticBle?.rssi?.collectAsState()

    // Signal history for sparklines
    val iridiumHistory by db.signalDao().getRecent("iridium", 30).collectAsState(initial = emptyList())
    val meshHistory by db.signalDao().getRecent("mesh", 30).collectAsState(initial = emptyList())
    val cellularHistory by db.signalDao().getRecent("cellular", 30).collectAsState(initial = emptyList())

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
                detail = "BLE",
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

        // Cellular status with live signal from history
        val latestCell = cellularHistory.firstOrNull()?.value
        StatusCard(
            title = "Cellular SMS",
            status = if (latestCell != null) "Signal: ${latestCell}dBm" else "Ready",
            isOnline = true,
            color = if (latestCell != null) cellularSignalColor(latestCell) else ColorCellular,
            detail = "Native SMS",
            modifier = Modifier.fillMaxWidth(),
        )

        // Signal history graphs
        if (iridiumHistory.isNotEmpty()) {
            SignalSparkline(
                title = "Iridium Signal",
                records = iridiumHistory,
                maxValue = 5f,
                minValue = 0f,
                color = ColorIridium,
                formatValue = { "${it.toInt()}/5" },
            )
        }

        if (meshHistory.isNotEmpty()) {
            SignalSparkline(
                title = "Mesh BLE RSSI",
                records = meshHistory,
                maxValue = -30f,
                minValue = -100f,
                color = ColorMesh,
                formatValue = { "${it.toInt()}dBm" },
            )
        }

        if (cellularHistory.isNotEmpty()) {
            SignalSparkline(
                title = "Cellular Signal",
                records = cellularHistory,
                maxValue = -50f,
                minValue = -120f,
                color = ColorCellular,
                formatValue = { "${it.toInt()}dBm" },
            )
        }

        // Stats
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Message Stats",
                style = MaterialTheme.typography.titleMedium,
            )
            StatRow("Total messages", totalMessages.toString())
            StatRow("Mesh messages", meshCount.toString())
            StatRow("SMS messages", smsCount.toString())
            StatRow("Encrypted", encryptedCount.toString())
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
private fun SignalSparkline(
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
            val latest = records.firstOrNull()?.value?.toFloat() ?: 0f
            Text(formatValue(latest), style = MaterialTheme.typography.bodyMedium, color = color)
        }

        val range = maxValue - minValue
        val points = records.reversed().map { it.value.toFloat() }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(top = 8.dp),
        ) {
            if (points.size < 2) return@Canvas

            val w = size.width
            val h = size.height
            val stepX = w / (points.size - 1).coerceAtLeast(1)

            fun yFor(v: Float): Float = h - ((v - minValue) / range).coerceIn(0f, 1f) * h

            // Area fill
            val areaPath = Path().apply {
                moveTo(0f, h)
                points.forEachIndexed { i, v ->
                    lineTo(i * stepX, yFor(v))
                }
                lineTo((points.size - 1) * stepX, h)
                close()
            }
            drawPath(areaPath, color.copy(alpha = 0.15f))

            // Line
            val linePath = Path().apply {
                points.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = yFor(v)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(linePath, color, style = Stroke(width = 2.dp.toPx()))

            // Dots
            points.forEachIndexed { i, v ->
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(i * stepX, yFor(v)))
            }
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

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MeshSatTextMuted)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
