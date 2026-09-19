package net.meshsat.android.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.meshsat.android.data.AccessRuleEntity
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════════
// Routing rules: which messages pass from one link to another, plus the
// messages those rules put in the queue (MESHSAT-1249)
// ═══════════════════════════════════════════════════════════════════════

private enum class BridgeTab(val label: String) {
    Outbound("From mesh"),
    Inbound("Into mesh"),
    CrossBridge("Between links"),
    Deliveries("Deliveries"),
    Queue("Queue"),
}

/**
 * The tab a rule is listed under. Every rule lands on exactly one tab, whatever its action or
 * direction: before, only forward rules with a target were listed, so a Drop or Log only rule
 * vanished from the screen once saved (B10).
 */
private fun ruleTab(rule: AccessRuleEntity): BridgeTab = when {
    rule.interfaceId.startsWith("mesh") -> BridgeTab.Outbound
    rule.forwardTo.startsWith("mesh") -> BridgeTab.Inbound
    else -> BridgeTab.CrossBridge
}

// Android interface IDs used by the gateway
private fun getAvailableInterfaces(): List<String> {
    val mgr = GatewayService.ifaceManager
    if (mgr != null) {
        val ids = mgr.getAllStatus().map { it.id }
        if (ids.isNotEmpty()) return ids
    }
    return listOf("mesh_0", "iridium_0", "sms_0") // fallback
}

/** A rule's action, as the user reads it: Forward, Drop, Log only. */
internal fun ruleActionLabel(action: String): String = when (action.lowercase()) {
    "forward" -> "Forward"
    "drop" -> "Drop"
    "log" -> "Log only"
    else -> action.replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppDatabase.getInstance(context)
    val allRules by db.accessRuleDao().getAll().collectAsState(initial = emptyList())
    val deliveries by db.messageDeliveryDao().getRecent(200).collectAsState(initial = emptyList())
    val now = rememberTickingNow()

    var activeTab by remember { mutableStateOf(BridgeTab.Outbound) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editRule by remember { mutableStateOf<AccessRuleEntity?>(null) }
    var ruleToDelete by remember { mutableStateOf<AccessRuleEntity?>(null) }
    var selectedDeliveryId by remember { mutableStateOf<Long?>(null) }
    var queueRequest by remember { mutableStateOf<QueueRequest?>(null) }

    val rulesByTab = remember(allRules) { allRules.groupBy { ruleTab(it) } }
    val queueCount = remember(deliveries) {
        deliveries.count { it.status in QUEUE_WAITING_STATUSES || it.status in QUEUE_GAVE_UP_STATUSES }
    }

    // Reload access evaluator after DB changes
    fun reloadEvaluator() {
        scope.launch {
            GatewayService.accessEval?.reloadFromDb()
        }
    }

    val onToggle: (AccessRuleEntity, Boolean) -> Unit = { rule, enabled ->
        scope.launch {
            db.accessRuleDao().update(rule.copy(enabled = enabled))
            reloadEvaluator()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = "Rules decide which messages are passed from one link to another.",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
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
                        BridgeTab.Outbound, BridgeTab.Inbound, BridgeTab.CrossBridge -> rulesByTab[tab]?.size ?: 0
                        BridgeTab.Queue -> queueCount
                        BridgeTab.Deliveries -> 0
                    }
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .background(
                                if (selected) MeshSatSurfaceLight else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { activeTab = tab }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MeshSatTextPrimary else MeshSatTextMuted,
                        )
                        if (badgeCount > 0) {
                            Text(
                                text = badgeCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MeshSatTextSecondary,
                                modifier = Modifier
                                    .background(MeshSatBorder, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
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
                    rules = rulesByTab[BridgeTab.Outbound].orEmpty(),
                    now = now,
                    emptyText = "No rules for mesh messages yet, so they stay on the mesh. Tap + to add one.",
                    subtitle = "What happens to messages heard on the mesh.",
                    onToggle = onToggle,
                    onDelete = { ruleToDelete = it },
                    onEdit = { editRule = it },
                )

                BridgeTab.Inbound -> RulesListContent(
                    rules = rulesByTab[BridgeTab.Inbound].orEmpty(),
                    now = now,
                    emptyText = "No rules pass messages into the mesh yet. Tap + to add one.",
                    subtitle = "Messages from satellite, SMS or the Hub that are passed into the mesh.",
                    onToggle = onToggle,
                    onDelete = { ruleToDelete = it },
                    onEdit = { editRule = it },
                )

                BridgeTab.CrossBridge -> RulesListContent(
                    rules = rulesByTab[BridgeTab.CrossBridge].orEmpty(),
                    now = now,
                    emptyText = "No rules between the other links yet. Tap + to add one.",
                    subtitle = "Messages from satellite, SMS or the Hub that go to another link, or are stopped or only logged.",
                    onToggle = onToggle,
                    onDelete = { ruleToDelete = it },
                    onEdit = { editRule = it },
                )

                BridgeTab.Deliveries -> DeliveryLedger(
                    deliveries = deliveries,
                    onSelect = { selectedDeliveryId = it.id },
                    modifier = Modifier.fillMaxSize(),
                )

                BridgeTab.Queue -> QueueTabContent(
                    deliveries = deliveries,
                    now = now,
                    onSelect = { selectedDeliveryId = it.id },
                    onRequest = { queueRequest = it },
                )
            }
        }

        // FAB, only on the rule tabs
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
                // Show the tab the rule is listed under, so it never seems to disappear.
                activeTab = ruleTab(rule)
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
                activeTab = ruleTab(updated)
                Toast.makeText(context, "Rule saved", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Delete, after the user has read what it changes
    ruleToDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { ruleToDelete = null },
            containerColor = MeshSatSurface,
            title = { Text("Delete this rule?") },
            text = {
                Text(
                    text = ruleDeleteConsequence(rule) + " You cannot undo this.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ruleToDelete = null
                        scope.launch {
                            db.accessRuleDao().deleteById(rule.id)
                            reloadEvaluator()
                        }
                        Toast.makeText(context, "Rule deleted", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Delete", color = MeshSatRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDelete = null }) {
                    Text("Keep it", color = MeshSatTextSecondary)
                }
            },
        )
    }

    // Delivery details, and the confirmation of a retry or cancel
    DeliveryDialogs(
        deliveries = deliveries,
        selectedId = selectedDeliveryId,
        onSelectedIdChange = { selectedDeliveryId = it },
        request = queueRequest,
        onRequestChange = { queueRequest = it },
    )
}

/** What deleting [rule] changes, in one sentence. */
private fun ruleDeleteConsequence(rule: AccessRuleEntity): String = when (rule.action) {
    "forward" ->
        if (rule.forwardTo.isNotBlank()) {
            "Messages that matched it will no longer be forwarded to ${Words.channel(rule.forwardTo)}."
        } else {
            "Messages that matched it will no longer be forwarded."
        }
    "drop" -> "Messages it stopped can get through again, if another rule passes them on."
    "log" -> "Its matches will no longer be counted. Messages are not affected."
    else -> "Messages that matched it will no longer be handled by it."
}

// ═══════════════════════════════════════════════════════════════════════
// Rules list (the three rule tabs)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun RulesListContent(
    rules: List<AccessRuleEntity>,
    now: Long,
    emptyText: String,
    subtitle: String,
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
                    textAlign = TextAlign.Center,
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
                        now = now,
                        onToggle = { enabled -> onToggle(rule, enabled) },
                        onDelete = { onDelete(rule) },
                        onEdit = { onEdit(rule) },
                    )
                }
                // Room under the last card for the add button
                item { Spacer(modifier = Modifier.height(72.dp)) }
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
    now: Long,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val name = rule.name.ifBlank { "Rule ${rule.id}" }
    val matches = rule.matchCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val meta = listOfNotNull(
        if (matches == 0) "No matches yet" else Words.count(matches, "match", "matches"),
        parseUtcStamp(rule.lastMatchAt)?.let { "last ${Words.ago(it, now)}" },
        ruleRateLimitText(rule.rateLimitPerMin, rule.rateLimitWindow),
        queueGuaranteeLabel(rule.qosLevel).takeIf { rule.qosLevel <= 0 },
        queueUrgencyLabel(rule.priority).takeIf { rule.priority == 0 },
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .then(if (!rule.enabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier)
            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Name and on/off
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Rule $name is ${if (rule.enabled) "on" else "off"}" },
            )
        }

        // What it does: "Forward: Mesh to Satellite"
        Text(
            text = ruleRouteText(rule),
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextSecondary,
            modifier = Modifier.padding(end = 8.dp),
        )

        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(end = 8.dp),
        )

        val filterSummary = buildFilterSummary(rule)
        if (filterSummary.isNotEmpty()) {
            Text(
                text = filterSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextSecondary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit rule $name", tint = MeshSatTextSecondary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete rule $name", tint = MeshSatTextSecondary)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendLink(id: String) {
    withStyle(SpanStyle(color = Words.channelColor(id))) { append(Words.channel(id)) }
}

/** "Forward: Mesh to Satellite", "Drop: messages from SMS", with each link in its colour. */
private fun ruleRouteText(rule: AccessRuleEntity): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MeshSatTextPrimary)) {
        append(ruleActionLabel(rule.action))
        append(": ")
    }
    when {
        rule.direction == "egress" -> {
            append("messages leaving by ")
            appendLink(rule.interfaceId)
        }
        rule.action == "forward" && rule.forwardTo.isNotBlank() -> {
            appendLink(rule.interfaceId)
            append(" to ")
            appendLink(rule.forwardTo)
        }
        else -> {
            append("messages from ")
            appendLink(rule.interfaceId)
        }
    }
}

/** "At most 5 messages per minute", or null when the rule has no limit (both numbers are needed). */
private fun ruleRateLimitText(perWindow: Int, windowSeconds: Int): String? {
    if (perWindow <= 0 || windowSeconds <= 0) return null
    val per = if (windowSeconds == 60) "per minute" else "per ${Words.count(windowSeconds, "second")}"
    return "At most ${Words.count(perWindow, "message")} $per"
}

/** A JSON array of ids or numbers as "a, b, c"; anything else as it is. */
private fun jsonListText(raw: String): String = try {
    val arr = JSONArray(raw)
    (0 until arr.length()).joinToString(", ") { arr.get(it).toString() }
} catch (_: Exception) {
    raw
}

private fun buildFilterSummary(rule: AccessRuleEntity): String {
    val parts = mutableListOf<String>()

    if (rule.filters.isNotEmpty() && rule.filters != "{}") {
        try {
            val obj = JSONObject(rule.filters)
            obj.optString("keyword", "").takeIf { it.isNotEmpty() }?.let { parts.add("Contains “$it”") }
            obj.optString("channels", "").takeIf { it.isNotEmpty() && it != "[]" }?.let { parts.add("Mesh channels ${jsonListText(it)}") }
            obj.optString("nodes", "").takeIf { it.isNotEmpty() && it != "[]" }?.let { parts.add("From nodes ${jsonListText(it)}") }
            obj.optString("portnums", "").takeIf { it.isNotEmpty() && it != "[]" }?.let { parts.add("Message types ${jsonListText(it)}") }
        } catch (_: Exception) { /* unreadable filters: the evaluator ignores them too */ }
    }
    rule.filterNodeGroup?.takeIf { it.isNotEmpty() }?.let { parts.add("Node group $it") }
    rule.filterSenderGroup?.takeIf { it.isNotEmpty() }?.let { parts.add("Sender group $it") }
    rule.filterPortnumGroup?.takeIf { it.isNotEmpty() }?.let { parts.add("Message type group $it") }

    return parts.joinToString(" · ")
}

// ═══════════════════════════════════════════════════════════════════════
// Queue tab: what is waiting, and what did not go out
// ═══════════════════════════════════════════════════════════════════════

/**
 * Waiting messages (with Cancel) and messages that gave up (with Retry). The tab used to list
 * only the ones that gave up while offering Cancel only to waiting ones, so Cancel never showed (B11).
 */
@Composable
private fun QueueTabContent(
    deliveries: List<MessageDeliveryEntity>,
    now: Long,
    onSelect: (MessageDeliveryEntity) -> Unit,
    onRequest: (QueueRequest) -> Unit,
) {
    val waiting = remember(deliveries) { deliveries.filter { it.status in QUEUE_WAITING_STATUSES } }
    val gaveUp = remember(deliveries) { deliveries.filter { it.status in QUEUE_GAVE_UP_STATUSES } }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Messages still waiting to go out, and messages that did not. Cancel one that is waiting, or retry one that gave up.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (waiting.isEmpty() && gaveUp.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing is waiting, and nothing has failed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (waiting.isNotEmpty()) {
                    item(key = "waiting-title") { QueueSectionTitle("Waiting to go out (${waiting.size})") }
                    items(waiting, key = { it.id }) { d ->
                        DeliveryCard(
                            delivery = d,
                            now = now,
                            onClick = { onSelect(d) },
                            onCancel = { onRequest(QueueRequest(d, retry = false)) },
                        )
                    }
                }
                if (gaveUp.isNotEmpty()) {
                    item(key = "gave-up-title") { QueueSectionTitle("Did not go out (${gaveUp.size})") }
                    items(gaveUp, key = { it.id }) { d ->
                        DeliveryCard(
                            delivery = d,
                            now = now,
                            onClick = { onSelect(d) },
                            onRetry = { onRequest(QueueRequest(d, retry = true)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MeshSatTextSecondary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Add / Edit Rule Dialog
// ═══════════════════════════════════════════════════════════════════════

/** The keyword filter of a rule, or "" when it has none. */
private fun ruleKeyword(rule: AccessRuleEntity?): String {
    val raw = rule?.filters?.trim().orEmpty()
    if (raw.isEmpty() || raw == "{}") return ""
    return try {
        JSONObject(raw).optString("keyword", "")
    } catch (_: Exception) {
        ""
    }
}

/**
 * The rule's filters with the keyword set to [keyword] (removed when blank). Every other filter
 * (mesh channels, nodes, message types) is kept: saving used to replace them all with the keyword
 * alone (B9). Filters this code cannot read are kept as they are unless a keyword was typed.
 */
private fun mergeKeywordFilter(existing: String?, keyword: String): String {
    val raw = existing?.trim().orEmpty()
    val obj = if (raw.isEmpty()) {
        JSONObject()
    } else {
        try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }
    if (obj == null) {
        return if (keyword.isBlank()) raw else JSONObject().put("keyword", keyword).toString()
    }
    if (keyword.isBlank()) obj.remove("keyword") else obj.put("keyword", keyword)
    return if (obj.length() == 0) "{}" else obj.toString()
}

/** The settings of [rule] that the editor does not show, which saving keeps. */
private fun hiddenRuleSettings(rule: AccessRuleEntity?): List<String> {
    if (rule == null) return emptyList()
    val hidden = mutableListOf<String>()
    try {
        val raw = rule.filters.trim()
        if (raw.isNotEmpty() && raw != "{}") {
            val obj = JSONObject(raw)
            if (obj.optString("channels", "").let { it.isNotEmpty() && it != "[]" }) hidden.add("mesh channels")
            if (obj.optString("nodes", "").let { it.isNotEmpty() && it != "[]" }) hidden.add("nodes")
            if (obj.optString("portnums", "").let { it.isNotEmpty() && it != "[]" }) hidden.add("message types")
        }
    } catch (_: Exception) {
        hidden.add("filters this screen cannot read")
    }
    if (!rule.filterPortnumGroup.isNullOrEmpty()) hidden.add("a message type group")
    val options = rule.forwardOptions.trim()
    if (options.isNotEmpty() && options != "{}") hidden.add("forwarding options")
    return hidden
}

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

    // The editor reads the rule once, keyed by its id, and from then on holds what the user
    // types. It used to re-read the keyword on every recomposition, so a typed keyword was
    // overwritten at once and an existing rule's keyword could not be changed (B9).
    val key = rule?.id
    var name by remember(key) { mutableStateOf(rule?.name ?: "") }
    var interfaceId by remember(key) { mutableStateOf(rule?.interfaceId ?: defaultInterface) }
    var forwardTo by remember(key) { mutableStateOf(rule?.forwardTo?.takeIf { it.isNotBlank() } ?: defaultForwardTo) }
    var action by remember(key) { mutableStateOf(rule?.action ?: "forward") }
    var enabled by remember(key) { mutableStateOf(rule?.enabled ?: true) }
    var qosLevel by remember(key) { mutableIntStateOf(rule?.qosLevel ?: 1) }
    var rateLimitPerMin by remember(key) { mutableStateOf((rule?.rateLimitPerMin ?: 0).toString()) }
    var rateLimitWindow by remember(key) { mutableStateOf((rule?.rateLimitWindow ?: 0).toString()) }
    var priority by remember(key) { mutableStateOf((rule?.priority ?: 10).toString()) }
    var filterKeyword by remember(key) { mutableStateOf(ruleKeyword(rule)) }
    var filterNodeGroup by remember(key) { mutableStateOf(rule?.filterNodeGroup ?: "") }
    var filterSenderGroup by remember(key) { mutableStateOf(rule?.filterSenderGroup ?: "") }
    var triedToSave by remember(key) { mutableStateOf(false) }
    val hiddenSettings = remember(key) { hiddenRuleSettings(rule) }

    val isEgress = rule?.direction == "egress"
    val nameError = triedToSave && name.isBlank()
    val targetError = triedToSave && action == "forward" && (forwardTo.isBlank() || forwardTo == interfaceId)
    val priorityValue = priority.toIntOrNull() ?: 10
    val perWindow = rateLimitPerMin.toIntOrNull() ?: 0
    val windowSeconds = rateLimitWindow.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text(if (isEdit) "Edit rule" else "New rule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule name") },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("Give the rule a name so you can find it later.") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                DropdownField(
                    label = if (isEgress) "When a message leaves by" else "When a message arrives by",
                    value = interfaceId,
                    options = getAvailableInterfaces(),
                    displayMapper = { Words.channel(it) },
                    onSelect = { interfaceId = it },
                )

                DropdownField(
                    label = "Then",
                    value = action,
                    options = listOf("forward", "drop", "log"),
                    displayMapper = { ruleActionLabel(it) },
                    supportingText = when (action) {
                        "forward" -> "Pass it on to another link."
                        "drop" -> "Stop it. It is not passed on, whatever other rules say."
                        else -> "Only count the match. Other rules still decide what happens."
                    },
                    onSelect = { action = it },
                )

                if (action == "forward") {
                    DropdownField(
                        label = "Pass it on by",
                        value = forwardTo,
                        options = getAvailableInterfaces().filter { it != interfaceId },
                        displayMapper = { Words.channel(it) },
                        isError = targetError,
                        supportingText = if (targetError) "Pick a different link from the one it arrives by." else null,
                        onSelect = { forwardTo = it },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Rule is on", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }

                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("Urgency") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text("${queueUrgencyLabel(priorityValue)}. Lower numbers go first and are checked first; 0 never expires.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                DropdownField(
                    label = "Delivery guarantee",
                    value = qosLevel.toString(),
                    options = listOf("0", "1", "2"),
                    displayMapper = { queueGuaranteeLabel(it.toIntOrNull() ?: 1) },
                    supportingText = "Try once gives up after one failed attempt. Keep trying retries until it goes out.",
                    onSelect = { qosLevel = it.toIntOrNull() ?: 1 },
                )

                Text(
                    text = "Limit",
                    style = MaterialTheme.typography.titleSmall,
                    color = MeshSatTextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = rateLimitPerMin,
                        onValueChange = { rateLimitPerMin = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("At most (messages)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = rateLimitWindow,
                        onValueChange = { rateLimitWindow = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("Every (seconds)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        colors = fieldColors(),
                    )
                }
                Text(
                    text = ruleRateLimitText(perWindow, windowSeconds)?.let { "$it. Leave either box at 0 for no limit." }
                        ?: "No limit. Fill in both boxes to set one, for example 5 messages every 60 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                Text(
                    text = "Only messages that match",
                    style = MaterialTheme.typography.titleSmall,
                    color = MeshSatTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )

                OutlinedTextField(
                    value = filterKeyword,
                    onValueChange = { filterKeyword = it },
                    label = { Text("Contains the text (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                OutlinedTextField(
                    value = filterNodeGroup,
                    onValueChange = { filterNodeGroup = it },
                    label = { Text("From a node group (group id, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                OutlinedTextField(
                    value = filterSenderGroup,
                    onValueChange = { filterSenderGroup = it },
                    label = { Text("From a sender group (group id, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                if (hiddenSettings.isNotEmpty()) {
                    Text(
                        text = "This rule also has settings this screen does not show (" +
                            hiddenSettings.joinToString(", ") + "). Saving keeps them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    triedToSave = true
                    val badTarget = action == "forward" && (forwardTo.isBlank() || forwardTo == interfaceId)
                    if (name.isBlank() || badTarget) return@TextButton

                    // Start from the rule itself, so what the editor does not show (direction,
                    // message type group, forwarding options, match count) survives a save.
                    val base = rule ?: AccessRuleEntity(interfaceId = interfaceId, direction = "ingress", name = name.trim())
                    val result = base.copy(
                        interfaceId = interfaceId,
                        priority = priority.toIntOrNull() ?: 10,
                        name = name.trim(),
                        enabled = enabled,
                        action = action,
                        forwardTo = if (action == "forward") forwardTo else "",
                        filters = mergeKeywordFilter(rule?.filters, filterKeyword),
                        filterNodeGroup = filterNodeGroup.trim().ifBlank { null },
                        filterSenderGroup = filterSenderGroup.trim().ifBlank { null },
                        qosLevel = qosLevel,
                        rateLimitPerMin = rateLimitPerMin.toIntOrNull() ?: 0,
                        rateLimitWindow = rateLimitWindow.toIntOrNull() ?: 0,
                    )
                    onSave(result)
                },
            ) {
                Text(if (isEdit) "Save" else "Add", color = MeshSatTeal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MeshSatTextSecondary)
            }
        },
    )
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
    supportingText: String? = null,
    isError: Boolean = false,
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
            isError = isError,
            supportingText = if (supportingText != null) {
                { Text(supportingText) }
            } else {
                null
            },
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
