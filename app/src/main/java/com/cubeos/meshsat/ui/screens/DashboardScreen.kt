package com.cubeos.meshsat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.components.StatusCard
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.SignalExcellent
import com.cubeos.meshsat.ui.theme.SignalFair
import com.cubeos.meshsat.ui.theme.SignalGood
import com.cubeos.meshsat.ui.theme.SignalPoor

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    // Use Flow for reactive updates — counters update live when new messages arrive
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

        // Transport status cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val meshConnected = meshState?.value == MeshtasticBle.State.Connected
            val meshConnecting = meshState?.value == MeshtasticBle.State.Connecting
                    || meshState?.value == MeshtasticBle.State.Scanning
            StatusCard(
                title = "Meshtastic",
                status = when {
                    meshConnected -> "Connected"
                    meshConnecting -> "Connecting..."
                    else -> "Disconnected"
                },
                isOnline = meshConnected,
                color = ColorMesh,
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
                color = if (iridiumConnected) signalColor(sig) else ColorIridium,
                detail = modemInfo?.value?.let {
                    if (it.imei.isNotBlank()) "IMEI: ${it.imei}" else "HC-05 SPP"
                } ?: "HC-05 SPP",
                modifier = Modifier.weight(1f),
            )
        }

        StatusCard(
            title = "Cellular SMS",
            status = "Ready",
            isOnline = true,
            color = ColorCellular,
            detail = "Native SMS",
            modifier = Modifier.fillMaxWidth(),
        )

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
}

@Composable
private fun signalColor(signal: Int) = when {
    signal >= 4 -> SignalExcellent
    signal >= 3 -> SignalGood
    signal >= 2 -> SignalFair
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
