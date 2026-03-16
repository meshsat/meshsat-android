package com.cubeos.meshsat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.data.AccessRuleEntity
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.MessageDeliveryEntity
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.ColorSMS
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBlue
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

// ═══════════════════════════════════════════════════════════════════════
// Bridge Rules Management — Phase H
// Tabbed UI for outbound/inbound/cross-bridge access rules + DLQ
// ═══════════════════════════════════════════════════════════════════════

private enum class BridgeTab(val label: String) {
    Outbound("Outbound"),
    Inbound("Inbound"),
    CrossBridge("Cross-Bridge"),
    Deliveries("Deliveries"),
    Queue("Queue"),
}

// Android interface IDs used by the gateway
private val KNOWN_INTERFACES = listOf("mesh_0", "iridium_0", "sms_0")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppDatabase.getInstance(context)
    val allRules by db.accessRuleDao().getAll().collectAsState(initial = emptyList())
    val deliveries by db.messageDeliveryDao().getRecent(200).collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(BridgeTab.Outbound) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editRule by remember { mutableStateOf<AccessRuleEntity?>(null) }
    var selectedDelivery by remember { mutableStateOf<MessageDeliveryEntity?>(null) }

    // Categorize rules (matching BridgeView.vue logic)
    val outboundRules = remember(allRules) {
        allRules.filter { r ->
            r.interfaceId.startsWith("mesh") && r.direction == "ingress" &&
                    r.action == "forward" && r.forwardTo.isNotEmpty() &&
                    !r.forwardTo.startsWith("mesh")
        }
    }
    val inboundRules = remember(allRules) {
        allRules.filter { r ->
            !r.interfaceId.startsWith("mesh") && r.direction == "ingress" &&
                    r.action == "forward" && r.forwardTo.startsWith("mesh")
        }
    }
    val crossRules = remember(allRules) {
        allRules.filter { r ->
            !r.interfaceId.startsWith("mesh") && r.direction == "ingress" &&
                    r.action == "forward" && r.forwardTo.isNotEmpty() &&
                    !r.forwardTo.startsWith("mesh")
        }
    }

    // DLQ = dead/failed/expired deliveries
    val queueItems = remember(deliveries) {
        deliveries.filter { it.status in listOf("dead", "failed", "expired", "denied") }
    }

    // Delivery counts for tab badges
    val activeDeliveryCount = remember(deliveries) {
        deliveries.count { it.status in listOf("queued", "sending", "retry", "held") }
    }

    // Reload access evaluator after DB changes
    fun reloadEvaluator() {
        scope.launch {
            GatewayService.accessEval?.reloadFromDb()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = "Bridge",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "Manage message routing rules between transports",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Tab bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BridgeTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    val badgeCount = when (tab) {
                        BridgeTab.CrossBridge -> crossRules.size
                        BridgeTab.Deliveries -> activeDeliveryCount
                        BridgeTab.Queue -> queueItems.size
                        else -> 0
                    }
                    Row(
                        modifier = Modifier
                            .background(
                                if (selected) MeshSatTeal.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { activeTab = tab }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MeshSatTeal else MeshSatTextMuted,
                        )
                        if (badgeCount > 0) {
                            val badgeColor = when (tab) {
                                BridgeTab.Queue -> MeshSatAmber
                                BridgeTab.Deliveries -> MeshSatBlue
                                else -> ColorIridium
                            }
                            Text(
                                text = badgeCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier
                                    .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MeshSatBorder),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tab content
            when (activeTab) {
                BridgeTab.Outbound -> RulesListContent(
                    rules = outboundRules,
                    emptyText = "No outbound rules. Mesh messages stay local.",
                    subtitle = "Mesh messages forwarded to external channels",
                    badgeColor = MeshSatTeal,
                    onToggle = { rule, enabled ->
                        scope.launch {
                            db.accessRuleDao().update(rule.copy(enabled = enabled))
                            reloadEvaluator()
                        }
                    },
                    onDelete = { rule ->
                        scope.launch {
                            db.accessRuleDao().deleteById(rule.id)
                            reloadEvaluator()
                        }
                        Toast.makeText(context, "Rule deleted", Toast.LENGTH_SHORT).show()
                    },
                    onEdit = { editRule = it },
                )

                BridgeTab.Inbound -> RulesListContent(
                    rules = inboundRules,
                    emptyText = "No inbound rules. External messages are not routed to mesh.",
                    subtitle = "External messages routed back to the mesh network",
                    badgeColor = MeshSatBlue,
                    onToggle = { rule, enabled ->
                        scope.launch {
                            db.accessRuleDao().update(rule.copy(enabled = enabled))
                            reloadEvaluator()
                        }
                    },
                    onDelete = { rule ->
                        scope.launch {
                            db.accessRuleDao().deleteById(rule.id)
                            reloadEvaluator()
                        }
                        Toast.makeText(context, "Rule deleted", Toast.LENGTH_SHORT).show()
                    },
                    onEdit = { editRule = it },
                )

                BridgeTab.CrossBridge -> RulesListContent(
                    rules = crossRules,
                    emptyText = "No cross-bridge rules. External channels operate independently.",
                    subtitle = "Inter-channel bridging (e.g. Iridium \u2194 SMS)",
                    badgeColor = ColorIridium,
                    onToggle = { rule, enabled ->
                        scope.launch {
                            db.accessRuleDao().update(rule.copy(enabled = enabled))
                            reloadEvaluator()
                        }
                    },
                    onDelete = { rule ->
                        scope.launch {
                            db.accessRuleDao().deleteById(rule.id)
                            reloadEvaluator()
                        }
                        Toast.makeText(context, "Rule deleted", Toast.LENGTH_SHORT).show()
                    },
                    onEdit = { editRule = it },
                )

                BridgeTab.Deliveries -> DeliveriesTabContent(
                    deliveries = deliveries,
                    onSelect = { selectedDelivery = it },
                )

                BridgeTab.Queue -> QueueTabContent(
                    items = queueItems,
                    onRetry = { item ->
                        scope.launch { db.messageDeliveryDao().retryNow(item.id) }
                        Toast.makeText(context, "Queued for retry", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { item ->
                        scope.launch { db.messageDeliveryDao().cancel(item.id) }
                        Toast.makeText(context, "Cancelled", Toast.LENGTH_SHORT).show()
                    },
                    onSelect = { selectedDelivery = it },
                )
            }
        }

        // FAB — only show on rule tabs
        if (activeTab in listOf(BridgeTab.Outbound, BridgeTab.Inbound, BridgeTab.CrossBridge)) {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MeshSatTeal,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add rule")
            }
        }
    }

    // Add rule dialog
    if (showAddDialog) {
        AddEditRuleDialog(
            rule = null,
            activeTab = activeTab,
            onDismiss = { showAddDialog = false },
            onSave = { rule ->
                scope.launch {
                    db.accessRuleDao().insert(rule)
                    reloadEvaluator()
                }
                showAddDialog = false
                Toast.makeText(context, "Rule added", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Edit rule dialog
    editRule?.let { rule ->
        AddEditRuleDialog(
            rule = rule,
            activeTab = activeTab,
            onDismiss = { editRule = null },
            onSave = { updated ->
                scope.launch {
                    db.accessRuleDao().update(updated)
                    reloadEvaluator()
                }
                editRule = null
                Toast.makeText(context, "Rule updated", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Delivery detail dialog
    selectedDelivery?.let { delivery ->
        DeliveryDetailDialogBridge(
            delivery = delivery,
            onDismiss = { selectedDelivery = null },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Rules List Content (shared across Outbound/Inbound/Cross-Bridge tabs)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun RulesListContent(
    rules: List<AccessRuleEntity>,
    emptyText: String,
    subtitle: String,
    badgeColor: Color,
    onToggle: (AccessRuleEntity, Boolean) -> Unit,
    onDelete: (AccessRuleEntity) -> Unit,
    onEdit: (AccessRuleEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    AccessRuleCard(
                        rule = rule,
                        badgeColor = badgeColor,
                        onToggle = { enabled -> onToggle(rule, enabled) },
                        onDelete = { onDelete(rule) },
                        onEdit = { onEdit(rule) },
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Access Rule Card
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AccessRuleCard(
    rule: AccessRuleEntity,
    badgeColor: Color,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .then(if (!rule.enabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier)
            .padding(12.dp),
    ) {
        // Row 1: action badge + name + controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                // Action badge
                Text(
                    text = rule.action.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )

                // Rule name
                Text(
                    text = rule.name.ifBlank { "Rule #${rule.id}" },
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MeshSatTextMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MeshSatRed, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Row 2: source -> dest interface
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            InterfaceBadge(rule.interfaceId)
            Text(
                text = "\u2192",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextMuted,
            )
            InterfaceBadge(rule.forwardTo)
        }

        // Row 3: match count + QoS + rate limit
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "${rule.matchCount} hits",
                style = MaterialTheme.typography.labelSmall,
                color = MeshSatTextMuted,
            )

            if (rule.qosLevel > 0) {
                Text(
                    text = "QoS ${rule.qosLevel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }

            if (rule.rateLimitPerMin > 0) {
                Text(
                    text = "${rule.rateLimitPerMin}/min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatAmber,
                )
            }

            rule.lastMatchAt?.let { ts ->
                Text(
                    text = "last: $ts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        // Row 4: filters summary (if present)
        val filterSummary = buildFilterSummary(rule)
        if (filterSummary.isNotEmpty()) {
            Text(
                text = filterSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MeshSatTextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun InterfaceBadge(interfaceId: String) {
    val color = interfaceColor(interfaceId)
    val label = interfaceLabel(interfaceId)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun interfaceColor(id: String): Color = when {
    id.startsWith("mesh") -> ColorMesh
    id.startsWith("iridium") -> ColorIridium
    id.startsWith("sms") -> ColorCellular
    else -> MeshSatTeal
}

private fun interfaceLabel(id: String): String = when {
    id.startsWith("mesh") -> "Mesh"
    id.startsWith("iridium") -> "Iridium"
    id.startsWith("sms") -> "SMS"
    else -> id
}

private fun buildFilterSummary(rule: AccessRuleEntity): String {
    val parts = mutableListOf<String>()

    if (rule.filters.isNotEmpty() && rule.filters != "{}") {
        try {
            val obj = org.json.JSONObject(rule.filters)
            obj.optString("keyword", "").takeIf { it.isNotEmpty() }?.let { parts.add("keyword: $it") }
            obj.optString("channels", "").takeIf { it.isNotEmpty() && it != "[]" }?.let { parts.add("channels: $it") }
            obj.optString("nodes", "").takeIf { it.isNotEmpty() && it != "[]" }?.let { parts.add("nodes: $it") }
            obj.optString("portnums", "").takeIf { it.isNotEmpty() && it != "[]" }?.let { parts.add("portnums: $it") }
        } catch (_: Exception) { /* ignore */ }
    }
    rule.filterNodeGroup?.takeIf { it.isNotEmpty() }?.let { parts.add("node-group: $it") }
    rule.filterSenderGroup?.takeIf { it.isNotEmpty() }?.let { parts.add("sender-group: $it") }
    rule.filterPortnumGroup?.takeIf { it.isNotEmpty() }?.let { parts.add("portnum-group: $it") }

    return parts.joinToString(" | ")
}

// ═══════════════════════════════════════════════════════════════════════
// Deliveries Tab
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun DeliveriesTabContent(
    deliveries: List<MessageDeliveryEntity>,
    onSelect: (MessageDeliveryEntity) -> Unit,
) {
    var filterStatus by remember { mutableStateOf<String?>(null) }
    var filterChannel by remember { mutableStateOf<String?>(null) }

    val filtered = remember(deliveries, filterStatus, filterChannel) {
        deliveries.filter { d ->
            (filterStatus == null || d.status == filterStatus) &&
                    (filterChannel == null || d.channel == filterChannel)
        }
    }

    val statusCounts = remember(deliveries) {
        deliveries.groupBy { it.status }.mapValues { it.value.size }
    }

    val channels = remember(deliveries) {
        deliveries.map { it.channel }.distinct().sorted()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Summary bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("queued", "sending", "sent", "failed", "dead").forEach { status ->
                val count = statusCounts[status] ?: 0
                val color = deliveryStatusColor(status)
                val selected = filterStatus == status
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { filterStatus = if (selected) null else status }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) color else MeshSatTextMuted,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Channel filter chips
        if (channels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                channels.forEach { ch ->
                    val selected = filterChannel == ch
                    val color = channelColor(ch)
                    Text(
                        text = ch,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) color else MeshSatTextMuted,
                        modifier = Modifier
                            .background(
                                if (selected) color.copy(alpha = 0.15f) else MeshSatSurface,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                if (selected) color else MeshSatBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { filterChannel = if (filterChannel == ch) null else ch }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                    text = if (deliveries.isEmpty()) "No deliveries yet.\nMessages appear when the dispatcher routes them."
                    else "No deliveries match the current filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { it.id }) { delivery ->
                    DeliveryRowBridge(delivery) { onSelect(delivery) }
                }
            }
        }
    }
}

@Composable
private fun DeliveryRowBridge(delivery: MessageDeliveryEntity, onClick: () -> Unit) {
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
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(deliveryStatusColor(delivery.status), CircleShape),
        )

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
                    color = deliveryStatusColor(delivery.status),
                )
            }

            if (delivery.textPreview.isNotBlank()) {
                Text(
                    text = delivery.textPreview.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextSecondary,
                    maxLines = 1,
                    fontFamily = FontFamily.Monospace,
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
                delivery.ackStatus?.let { ack ->
                    Text(
                        text = "ACK: $ack",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (ack) {
                            "acked" -> MeshSatGreen
                            "pending" -> MeshSatAmber
                            else -> MeshSatRed
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

// ═══════════════════════════════════════════════════════════════════════
// Queue Tab (DLQ — dead/failed/expired deliveries)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun QueueTabContent(
    items: List<MessageDeliveryEntity>,
    onRetry: (MessageDeliveryEntity) -> Unit,
    onCancel: (MessageDeliveryEntity) -> Unit,
    onSelect: (MessageDeliveryEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Dead letter queue \u2014 failed, expired, and denied deliveries",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Queue is empty. No failed deliveries.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    DlqItemCard(
                        item = item,
                        onRetry = { onRetry(item) },
                        onCancel = { onCancel(item) },
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DlqItemCard(
    item: MessageDeliveryEntity,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClick: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val statusColor = deliveryStatusColor(item.status)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(6.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // Header: channel + status + time
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.channel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = channelColor(item.channel),
                modifier = Modifier
                    .background(channelColor(item.channel).copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                text = item.status.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = statusColor,
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (item.priority <= 1) {
                Text(
                    text = priorityLabel(item.priority),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = priorityColor(item.priority),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = fmt.format(Date(item.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MeshSatTextMuted,
            )
        }

        // Preview
        if (item.textPreview.isNotBlank()) {
            Text(
                text = item.textPreview.take(80),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MeshSatTextSecondary,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(MeshSatBorder.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .padding(6.dp),
            )
        }

        // Error
        if (item.lastError.isNotBlank()) {
            Text(
                text = item.lastError,
                style = MaterialTheme.typography.labelSmall,
                color = MeshSatRed.copy(alpha = 0.8f),
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Actions
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Retries: ${item.retries}/${item.maxRetries}",
                style = MaterialTheme.typography.labelSmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (item.status in listOf("failed", "dead")) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MeshSatTeal,
                    modifier = Modifier
                        .background(MeshSatTeal.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (item.status in listOf("queued", "retry")) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MeshSatRed,
                    modifier = Modifier
                        .background(MeshSatRed.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Add / Edit Rule Dialog
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditRuleDialog(
    rule: AccessRuleEntity?,
    activeTab: BridgeTab,
    onDismiss: () -> Unit,
    onSave: (AccessRuleEntity) -> Unit,
) {
    val isEdit = rule != null

    // Defaults based on active tab
    val defaultInterface = when (activeTab) {
        BridgeTab.Outbound -> "mesh_0"
        BridgeTab.Inbound -> "iridium_0"
        BridgeTab.CrossBridge -> "iridium_0"
        else -> "mesh_0"
    }
    val defaultForwardTo = when (activeTab) {
        BridgeTab.Outbound -> "iridium_0"
        BridgeTab.Inbound -> "mesh_0"
        BridgeTab.CrossBridge -> "sms_0"
        else -> "iridium_0"
    }

    var name by remember { mutableStateOf(rule?.name ?: "") }
    var interfaceId by remember { mutableStateOf(rule?.interfaceId ?: defaultInterface) }
    var forwardTo by remember { mutableStateOf(rule?.forwardTo ?: defaultForwardTo) }
    var action by remember { mutableStateOf(rule?.action ?: "forward") }
    var enabled by remember { mutableStateOf(rule?.enabled ?: true) }
    var qosLevel by remember { mutableIntStateOf(rule?.qosLevel ?: 1) }
    var rateLimitPerMin by remember { mutableStateOf((rule?.rateLimitPerMin ?: 0).toString()) }
    var rateLimitWindow by remember { mutableStateOf((rule?.rateLimitWindow ?: 0).toString()) }
    var priority by remember { mutableStateOf((rule?.priority ?: 10).toString()) }

    // Filter fields
    var filterKeyword by remember { mutableStateOf("") }
    var filterNodeGroup by remember { mutableStateOf(rule?.filterNodeGroup ?: "") }
    var filterSenderGroup by remember { mutableStateOf(rule?.filterSenderGroup ?: "") }

    // Parse existing filters
    if (rule != null && rule.filters.isNotEmpty() && rule.filters != "{}") {
        try {
            val obj = org.json.JSONObject(rule.filters)
            filterKeyword = obj.optString("keyword", "")
        } catch (_: Exception) { /* ignore */ }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text(if (isEdit) "Edit Rule" else "Add Rule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                // Source interface
                DropdownField(
                    label = "Source interface",
                    value = interfaceId,
                    options = KNOWN_INTERFACES,
                    displayMapper = { interfaceLabel(it) },
                    onSelect = { interfaceId = it },
                )

                // Action
                DropdownField(
                    label = "Action",
                    value = action,
                    options = listOf("forward", "drop", "log"),
                    onSelect = { action = it },
                )

                // Forward to (only for forward action)
                if (action == "forward") {
                    DropdownField(
                        label = "Forward to",
                        value = forwardTo,
                        options = KNOWN_INTERFACES.filter { it != interfaceId },
                        displayMapper = { interfaceLabel(it) },
                        onSelect = { forwardTo = it },
                    )
                }

                // Enabled toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }

                // Priority
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter { c -> c.isDigit() } },
                    label = { Text("Priority (lower = higher)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                // QoS
                DropdownField(
                    label = "QoS level",
                    value = qosLevel.toString(),
                    options = listOf("0", "1", "2"),
                    onSelect = { qosLevel = it.toIntOrNull() ?: 1 },
                )

                // Rate limit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = rateLimitPerMin,
                        onValueChange = { rateLimitPerMin = it.filter { c -> c.isDigit() } },
                        label = { Text("Rate/min") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = rateLimitWindow,
                        onValueChange = { rateLimitWindow = it.filter { c -> c.isDigit() } },
                        label = { Text("Window (s)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = fieldColors(),
                    )
                }

                // Filters section
                Text(
                    text = "FILTERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )

                OutlinedTextField(
                    value = filterKeyword,
                    onValueChange = { filterKeyword = it },
                    label = { Text("Keyword filter (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                OutlinedTextField(
                    value = filterNodeGroup,
                    onValueChange = { filterNodeGroup = it },
                    label = { Text("Node group ID (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                OutlinedTextField(
                    value = filterSenderGroup,
                    onValueChange = { filterSenderGroup = it },
                    label = { Text("Sender group ID (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton

                    // Build filters JSON
                    val filtersJson = if (filterKeyword.isNotBlank()) {
                        org.json.JSONObject().apply {
                            put("keyword", filterKeyword)
                        }.toString()
                    } else "{}"

                    val result = AccessRuleEntity(
                        id = rule?.id ?: 0,
                        interfaceId = interfaceId,
                        direction = "ingress",
                        priority = priority.toIntOrNull() ?: 10,
                        name = name,
                        enabled = enabled,
                        action = action,
                        forwardTo = if (action == "forward") forwardTo else "",
                        filters = filtersJson,
                        filterNodeGroup = filterNodeGroup.ifBlank { null },
                        filterSenderGroup = filterSenderGroup.ifBlank { null },
                        filterPortnumGroup = rule?.filterPortnumGroup,
                        forwardOptions = rule?.forwardOptions ?: "{}",
                        qosLevel = qosLevel,
                        rateLimitPerMin = rateLimitPerMin.toIntOrNull() ?: 0,
                        rateLimitWindow = rateLimitWindow.toIntOrNull() ?: 0,
                        matchCount = rule?.matchCount ?: 0,
                        lastMatchAt = rule?.lastMatchAt,
                    )
                    onSave(result)
                },
            ) {
                Text(if (isEdit) "Save" else "Add", color = MeshSatTeal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MeshSatTextMuted)
            }
        },
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Delivery Detail Dialog (shared by Deliveries + Queue tabs)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun DeliveryDetailDialogBridge(
    delivery: MessageDeliveryEntity,
    onDismiss: () -> Unit,
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
                        .background(deliveryStatusColor(delivery.status), CircleShape),
                )
                Text("Delivery #${delivery.id}")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("Channel", delivery.channel)
                DetailRow("Status", delivery.status.uppercase())
                DetailRow("Priority", priorityLabel(delivery.priority))
                DetailRow("Created", fmt.format(Date(delivery.createdAt)))
                DetailRow("Updated", fmt.format(Date(delivery.updatedAt)))
                DetailRow("MsgRef", delivery.msgRef)
                if (delivery.retries > 0) DetailRow("Retries", "${delivery.retries}/${delivery.maxRetries}")
                if (delivery.lastError.isNotBlank()) DetailRow("Last Error", delivery.lastError)
                delivery.ackStatus?.let { DetailRow("ACK Status", it) }
                if (delivery.seqNum > 0) DetailRow("Seq #", delivery.seqNum.toString())
                if (delivery.qosLevel > 0) DetailRow("QoS Level", delivery.qosLevel.toString())
                if (delivery.ttlSeconds > 0) DetailRow("TTL", "${delivery.ttlSeconds}s")
                delivery.expiresAt?.let { DetailRow("Expires", fmt.format(Date(it))) }

                if (delivery.textPreview.isNotBlank()) {
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextMuted,
                    )
                    Text(
                        text = delivery.textPreview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MeshSatBorder.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MeshSatTextMuted)
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

// ═══════════════════════════════════════════════════════════════════════
// Shared Composables
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    displayMapper: ((String) -> String)? = null,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = displayMapper?.invoke(value) ?: value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = fieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayMapper?.invoke(option) ?: option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MeshSatTeal,
    unfocusedBorderColor = MeshSatBorder,
)

// ═══════════════════════════════════════════════════════════════════════
// Utility functions
// ═══════════════════════════════════════════════════════════════════════

private fun deliveryStatusColor(status: String): Color = when (status) {
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

private fun priorityLabel(priority: Int): String = when (priority) {
    0 -> "Critical"
    1 -> "Normal"
    else -> "Low"
}

private fun priorityColor(priority: Int): Color = when (priority) {
    0 -> MeshSatRed
    1 -> MeshSatAmber
    else -> MeshSatTextMuted
}
