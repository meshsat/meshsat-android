package com.cubeos.meshsat.ui.screens

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.MessageDeliveryEntity
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.ColorSMS
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Delivery Status screen — per-message delivery lifecycle tracking
 * across all transports (queued → sending → sent → delivered / failed / dead).
 */
@Composable
fun DeliveryScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val scope = rememberCoroutineScope()

    val deliveries by db.messageDeliveryDao().getRecent(200).collectAsState(initial = emptyList())

    // Filter state
    var filterStatus by remember { mutableStateOf<String?>(null) }
    var filterChannel by remember { mutableStateOf<String?>(null) }
    var selectedDelivery by remember { mutableStateOf<MessageDeliveryEntity?>(null) }

    val filtered = remember(deliveries, filterStatus, filterChannel) {
        deliveries.filter { d ->
            (filterStatus == null || d.status == filterStatus) &&
                    (filterChannel == null || d.channel == filterChannel)
        }
    }

    // Status summary counts
    val statusCounts = remember(deliveries) {
        deliveries.groupBy { it.status }.mapValues { it.value.size }
    }

    // Unique channels
    val channels = remember(deliveries) {
        deliveries.map { it.channel }.distinct().sorted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Delivery Status",
            style = MaterialTheme.typography.headlineMedium,
        )

        // Summary bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DeliveryStatChip("queued", statusCounts["queued"] ?: 0, MeshSatAmber, filterStatus == "queued") {
                filterStatus = if (filterStatus == "queued") null else "queued"
            }
            DeliveryStatChip("sending", statusCounts["sending"] ?: 0, MeshSatTeal, filterStatus == "sending") {
                filterStatus = if (filterStatus == "sending") null else "sending"
            }
            DeliveryStatChip("sent", statusCounts["sent"] ?: 0, MeshSatGreen, filterStatus == "sent") {
                filterStatus = if (filterStatus == "sent") null else "sent"
            }
            DeliveryStatChip("failed", statusCounts["failed"] ?: 0, MeshSatRed, filterStatus == "failed") {
                filterStatus = if (filterStatus == "failed") null else "failed"
            }
            DeliveryStatChip("dead", statusCounts["dead"] ?: 0, MeshSatTextMuted, filterStatus == "dead") {
                filterStatus = if (filterStatus == "dead") null else "dead"
            }
        }

        // Channel filter chips
        if (channels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                channels.forEach { ch ->
                    val selected = filterChannel == ch
                    Text(
                        text = ch,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) channelColor(ch) else MeshSatTextMuted,
                        modifier = Modifier
                            .background(
                                if (selected) channelColor(ch).copy(alpha = 0.15f) else MeshSatSurface,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                if (selected) channelColor(ch) else MeshSatBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { filterChannel = if (filterChannel == ch) null else ch }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Delivery list
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (deliveries.isEmpty()) "No deliveries yet.\nMessages will appear here when the dispatcher routes them."
                    else "No deliveries match the current filter.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { it.id }) { delivery ->
                    DeliveryRow(delivery) { selectedDelivery = delivery }
                }
            }
        }
    }

    // Detail dialog
    selectedDelivery?.let { delivery ->
        DeliveryDetailDialog(
            delivery = delivery,
            onDismiss = { selectedDelivery = null },
            onRetry = {
                scope.launch {
                    db.messageDeliveryDao().retryNow(delivery.id)
                    selectedDelivery = null
                }
            },
            onCancel = {
                scope.launch {
                    db.messageDeliveryDao().cancel(delivery.id)
                    selectedDelivery = null
                }
            },
        )
    }
}

@Composable
private fun DeliveryStatChip(
    label: String,
    count: Int,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(
                if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else MeshSatTextMuted,
        )
    }
}

@Composable
private fun DeliveryRow(delivery: MessageDeliveryEntity, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(6.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status indicator dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(statusColor(delivery.status), CircleShape),
        )

        // Channel + preview
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = delivery.channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = channelColor(delivery.channel),
                )
                Text(
                    text = delivery.status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor(delivery.status),
                )
            }

            if (delivery.textPreview.isNotBlank()) {
                Text(
                    text = delivery.textPreview.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextSecondary,
                    maxLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = fmt.format(Date(delivery.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )

                // ACK status if present
                delivery.ackStatus?.let { ack ->
                    Text(
                        text = "ACK: $ack",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (ack) {
                            "acked" -> MeshSatGreen
                            "nacked" -> MeshSatRed
                            "pending" -> MeshSatAmber
                            "timeout" -> MeshSatRed
                            else -> MeshSatTextMuted
                        },
                    )
                }

                if (delivery.retries > 0) {
                    Text(
                        text = "retry ${delivery.retries}/${delivery.maxRetries}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatAmber,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeliveryDetailDialog(
    delivery: MessageDeliveryEntity,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor(delivery.status), CircleShape),
                )
                Text("Delivery #${delivery.id}")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("Channel", delivery.channel)
                DetailRow("Status", delivery.status.uppercase())
                DetailRow("Priority", delivery.priority.toString())
                DetailRow("Created", fmt.format(Date(delivery.createdAt)))
                DetailRow("Updated", fmt.format(Date(delivery.updatedAt)))
                DetailRow("MsgRef", delivery.msgRef)

                if (delivery.retries > 0) {
                    DetailRow("Retries", "${delivery.retries}/${delivery.maxRetries}")
                }
                if (delivery.lastError.isNotBlank()) {
                    DetailRow("Last Error", delivery.lastError)
                }
                delivery.ackStatus?.let { DetailRow("ACK Status", it) }
                if (delivery.seqNum > 0) {
                    DetailRow("Seq #", delivery.seqNum.toString())
                }
                if (delivery.qosLevel > 0) {
                    DetailRow("QoS Level", delivery.qosLevel.toString())
                }
                if (delivery.ttlSeconds > 0) {
                    DetailRow("TTL", "${delivery.ttlSeconds}s")
                }
                delivery.expiresAt?.let {
                    DetailRow("Expires", fmt.format(Date(it)))
                }
                if (delivery.textPreview.isNotBlank()) {
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextMuted,
                    )
                    Text(
                        text = delivery.textPreview,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MeshSatBorder.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (delivery.status in listOf("failed", "dead")) {
                    TextButton(onClick = onRetry) {
                        Text("Retry", color = MeshSatTeal)
                    }
                }
                if (delivery.status in listOf("queued", "retry")) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = MeshSatRed)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = MeshSatTextMuted)
                }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun statusColor(status: String): Color = when (status) {
    "queued" -> MeshSatAmber
    "sending" -> MeshSatTeal
    "sent" -> MeshSatGreen
    "delivered" -> MeshSatGreen
    "failed" -> MeshSatRed
    "dead" -> Color(0xFF6B7280)
    "expired" -> Color(0xFF6B7280)
    "denied" -> MeshSatRed
    "held" -> MeshSatAmber
    "retry" -> MeshSatAmber
    else -> Color(0xFF6B7280)
}

private fun channelColor(channel: String): Color = when {
    channel.startsWith("mesh") -> ColorMesh
    channel.startsWith("iridium") -> ColorIridium
    channel.startsWith("sms") -> ColorSMS
    else -> MeshSatTeal
}
