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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.ui.components.StatusCard
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    val totalMessages by produceState(0) { value = db.messageDao().count() }
    val smsCount by produceState(0) { value = db.messageDao().countByTransport("sms") }
    val encryptedCount by produceState(0) { value = db.messageDao().countEncrypted() }

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
            StatusCard(
                title = "Meshtastic",
                status = "Not connected",
                isOnline = false,
                color = ColorMesh,
                detail = "BLE",
                modifier = Modifier.weight(1f),
            )
            StatusCard(
                title = "Iridium",
                status = "Not connected",
                isOnline = false,
                color = ColorIridium,
                detail = "HC-05 SPP",
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
            StatRow("SMS messages", smsCount.toString())
            StatRow("Encrypted", encryptedCount.toString())
        }
    }
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
