package com.cubeos.meshsat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.AuditLogEntity
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.MeshSatBlue
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val interfaceFilters = listOf("all", "mesh_0", "iridium_0", "sms_0")

private fun eventColor(eventType: String): androidx.compose.ui.graphics.Color = when {
    eventType.contains("forward", ignoreCase = true) -> MeshSatGreen
    eventType.contains("deny", ignoreCase = true) || eventType.contains("reject", ignoreCase = true) -> MeshSatRed
    eventType.contains("bind", ignoreCase = true) || eventType.contains("connect", ignoreCase = true) -> MeshSatBlue
    else -> MeshSatTextMuted
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val auditDao = db.auditLogDao()
    val signing = GatewayService.signingServiceRef
    val scope = rememberCoroutineScope()

    var events by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(100) }
    var filterInterface by remember { mutableStateOf<String?>(null) }
    var verifyResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var verifying by remember { mutableStateOf(false) }

    LaunchedEffect(limit, filterInterface) {
        withContext(Dispatchers.IO) {
            events = if (filterInterface != null) {
                auditDao.getByInterface(filterInterface!!, limit)
            } else {
                auditDao.getRecent(limit)
            }
            totalCount = auditDao.count()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Header
        Text(
            text = "Audit Log",
            style = MaterialTheme.typography.headlineMedium,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$totalCount events",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
            )

            signing?.let { svc ->
                Text(
                    text = "Signer: ${svc.signerId.take(12)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTeal,
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Signer ID", svc.signerId))
                        Toast.makeText(context, "Signer ID copied", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Filter chips
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            interfaceFilters.forEach { iface ->
                val selected = if (iface == "all") filterInterface == null else filterInterface == iface
                FilterChip(
                    selected = selected,
                    onClick = {
                        filterInterface = if (iface == "all") null else iface
                        limit = 100
                        verifyResult = null
                    },
                    label = { Text(iface, style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                        selectedLabelColor = MeshSatTeal,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Verify button
        if (signing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        verifying = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { signing.verifyChain() }
                            verifyResult = result
                            verifying = false
                        }
                    },
                    enabled = !verifying,
                ) {
                    Text(if (verifying) "Verifying..." else "Verify Chain")
                }

                verifyResult?.let { (valid, brokenAt) ->
                    if (brokenAt < 0) {
                        Text(
                            text = "$valid of $valid valid",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatGreen,
                        )
                    } else {
                        Text(
                            text = "Chain broken at entry $brokenAt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatRed,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Event list
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No audit events recorded yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    AuditEventCard(event)
                }

                if (events.size >= limit) {
                    item {
                        Button(
                            onClick = { limit += 100 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                        ) {
                            Text("Load more")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditEventCard(event: AuditLogEntity) {
    val dotColor = eventColor(event.eventType)

    // Parse timestamp — format is ISO string from SigningService
    val timeDisplay = remember(event.timestamp) {
        try {
            // Try to extract HH:mm:ss from ISO timestamp
            if (event.timestamp.contains("T") && event.timestamp.length >= 19) {
                event.timestamp.substring(11, 19)
            } else {
                event.timestamp.takeLast(8)
            }
        } catch (_: Exception) {
            event.timestamp.takeLast(8)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Color dot
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Top row: time + event type badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                Text(
                    text = event.eventType,
                    style = MaterialTheme.typography.labelSmall,
                    color = dotColor,
                    modifier = Modifier
                        .background(dotColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // Interface + direction
            if (event.interfaceId != null || event.direction != null) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    event.interfaceId?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTeal,
                        )
                    }
                    event.direction?.let { dir ->
                        val arrow = when (dir.lowercase()) {
                            "inbound", "in" -> "\u2192"
                            "outbound", "out" -> "\u2190"
                            else -> "\u2194"
                        }
                        Text(
                            text = arrow,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
            }

            // Detail text
            if (event.detail.isNotBlank()) {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Delivery ID / Rule ID
            if (event.deliveryId != null || event.ruleId != null) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    event.deliveryId?.let {
                        Text(
                            text = "delivery:$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextMuted,
                        )
                    }
                    event.ruleId?.let {
                        Text(
                            text = "rule:$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
            }
        }
    }
}
