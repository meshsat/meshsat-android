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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.meshsat.android.data.AccessRuleEntity
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.FailoverGroupEntity
import net.meshsat.android.data.ObjectGroupEntity
import net.meshsat.android.engine.HealthScore
import net.meshsat.android.engine.InterfaceState
import net.meshsat.android.engine.InterfaceStatus
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.Words
import org.json.JSONArray
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import kotlinx.coroutines.delay
import net.meshsat.android.ui.theme.PlexMono
import net.meshsat.android.ui.components.collectOrNull

// ═══════════════════════════════════════════════════════════════════════
// Links: each way the phone sends and receives, its rules, groups and health
// ═══════════════════════════════════════════════════════════════════════

private enum class IfaceTab(val label: String) {
    Interfaces("Links"),
    AccessRules("Rules"),
    Channels("Capabilities"),
    ObjectGroups("Groups"),
    Failover("Backup links"),
    Health("Health"),
}

/** How often the groups and backup links tabs re-read the database. */
private const val GROUPS_REFRESH_MS = 10_000L

@Composable
fun InterfacesScreen() {
    val context = LocalContext.current

    val ifaceStates: Map<String, InterfaceStatus> =
        GatewayService.ifaceManager?.states.collectOrNull()?.value ?: emptyMap()

    var activeTab by remember { mutableStateOf(IfaceTab.Interfaces) }
    var confirmOff by remember { mutableStateOf<String?>(null) }

    // Health scores (refreshed periodically)
    var healthScores by remember { mutableStateOf<List<HealthScore>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            GatewayService.healthScorer?.let { hs ->
                healthScores = hs.scoreAll()
            }
            delay(30_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Each way this phone can send and receive messages, and how well it is working.",
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
            IfaceTab.entries.forEach { tab ->
                val selected = activeTab == tab
                val badgeCount = when (tab) {
                    IfaceTab.Interfaces -> ifaceStates.count { it.value.state == InterfaceState.Online }
                    IfaceTab.Health -> healthScores.count { it.score < 50 && it.available }
                    else -> 0
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
                        val badgeColor = when (tab) {
                            IfaceTab.Interfaces -> MeshSatGreen
                            IfaceTab.Health -> MeshSatAmber
                            else -> MeshSatTextSecondary
                        }
                        Text(
                            text = badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
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
            IfaceTab.Interfaces -> InterfacesTabContent(
                interfaces = ifaceStates,
                onEnable = { id ->
                    GatewayService.ifaceManager?.enable(id)
                    Toast.makeText(context, "${Words.channel(id)} switched on", Toast.LENGTH_SHORT).show()
                },
                // Switching a link off is confirmed first: it stops everything that goes by it.
                onDisable = { id -> confirmOff = id },
                onReconnect = { id ->
                    GatewayService.ifaceManager?.reconnectNow(id)
                    Toast.makeText(context, "Trying to connect ${Words.channel(id)} now", Toast.LENGTH_SHORT).show()
                },
            )

            IfaceTab.AccessRules -> AccessRulesTabContent()

            IfaceTab.Channels -> ChannelsTabContent()

            IfaceTab.ObjectGroups -> ObjectGroupsTabContent()

            IfaceTab.Failover -> FailoverTabContent()

            IfaceTab.Health -> HealthTabContent(healthScores = healthScores)
        }
    }

    confirmOff?.let { id ->
        val link = Words.channel(id)
        AlertDialog(
            onDismissRequest = { confirmOff = null },
            containerColor = MeshSatSurface,
            title = { Text("Switch off $link?") },
            text = {
                Text(
                    text = if (id.startsWith("iridium")) {
                        "The phone stops using the satellite modem and stops reconnecting to it. Nothing goes out " +
                            "or comes in by satellite until you switch it back on. Messages waiting for it stay in the queue."
                    } else {
                        "Messages stop going out by $link until you switch it back on. " +
                            "Messages waiting for it stay in the queue."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOff = null
                        GatewayService.ifaceManager?.disable(id)
                        Toast.makeText(context, "$link switched off", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Switch off", color = MeshSatRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = null }) {
                    Text("Keep it on", color = MeshSatTextSecondary)
                }
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Links tab: live status with controls
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InterfacesTabContent(
    interfaces: Map<String, InterfaceStatus>,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onReconnect: (String) -> Unit,
) {
    val now = rememberTickingNow()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (interfaces.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No links yet. They appear once the MeshSat service is running.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Sort: mesh first, then satellite, then SMS
        val sorted = interfaces.entries.sortedBy { (id, _) ->
            when {
                id.startsWith("mesh") -> 0
                id.startsWith("iridium") -> 1
                id.startsWith("sms") -> 2
                else -> 3
            }
        }

        sorted.forEach { (id, status) ->
            InterfaceCard(
                status = status,
                now = now,
                onEnable = { onEnable(id) },
                onDisable = { onDisable(id) },
                onReconnect = { onReconnect(id) },
            )
        }
    }
}

@Composable
private fun InterfaceCard(
    status: InterfaceStatus,
    now: Long,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onReconnect: () -> Unit,
) {
    val stateColor = stateColor(status.state)
    val link = Words.channel(status.id)
    val isDisabled = status.state == InterfaceState.Disabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .then(if (isDisabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier)
            .padding(12.dp),
    ) {
        // Name and on/off
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Words.channelColor(status.id), CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = link,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = !isDisabled,
                onCheckedChange = { enabled ->
                    if (enabled) onEnable() else onDisable()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                modifier = Modifier.semantics { contentDescription = "Use $link" },
            )
        }

        // State, and what went wrong
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = Words.linkState(status.state.name),
                style = MaterialTheme.typography.labelLarge,
                color = stateColor,
                modifier = Modifier
                    .background(stateColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            if (status.error.isNotBlank()) {
                Text(
                    text = status.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatRed,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // When it last worked and last carried a message
        val times = listOfNotNull(
            status.lastOnline.takeIf { it > 0 }?.let { "Last working ${Words.ago(it, now)}" },
            status.lastActivity.takeIf { it > 0 }?.let { "last message ${Words.ago(it, now)}" },
        ).joinToString(" · ")
        if (times.isNotEmpty() || status.reconnectAttempts > 0) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                if (times.isNotEmpty()) {
                    Text(
                        text = times,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
                if (status.reconnectAttempts > 0) {
                    Text(
                        text = "Tried to reconnect ${Words.count(status.reconnectAttempts, "time")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatAmber,
                    )
                }
            }
        }

        // Reconnect (only when off or not working)
        if (status.state in listOf(InterfaceState.Offline, InterfaceState.Error)) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onReconnect,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = "Try to connect now",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Capabilities tab: what each link can carry (read-only)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ChannelsTabContent() {
    val channels = GatewayService.channelReg?.list() ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "What each link can carry, and how it retries.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (channels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing to show yet. It appears once the MeshSat service is running.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        channels.forEach { ch ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Words.channelColor(ch.id), CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Words.channel(ch.id),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = ch.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }

                CapabilityRow("Largest message", if (ch.maxPayload > 0) "${ch.maxPayload} bytes" else "No limit")
                CapabilityRow("Sends", if (ch.canSend) "Yes" else "No")
                CapabilityRow("Receives", if (ch.canReceive) "Yes" else "No")
                CapabilityRow("Carries data, not only text", if (ch.binaryCapable) "Yes" else "No")
                CapabilityRow("Cost", if (ch.isPaid) "Paid" else "Free")

                if (ch.retryConfig.enabled) {
                    val first = ch.retryConfig.initialWait.inWholeSeconds
                    val longest = ch.retryConfig.maxWait.inWholeSeconds
                    val times = ch.retryConfig.maxRetries
                    val howOften = if (ch.retryConfig.backoffFunc == "isu") {
                        "waits for the next satellite pass"
                    } else {
                        "first after $first s, then up to $longest s apart"
                    }
                    Text(
                        text = "Retries: $howOften" + if (times > 0) ", at most ${Words.count(times, "time")}." else ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (value.firstOrNull()?.isDigit() == true) PlexMono else null,
            color = if (value == "Yes") MeshSatTextPrimary else MeshSatTextSecondary,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Health tab: one score per link
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun HealthTabContent(healthScores: List<HealthScore>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "A score out of 100 for each link, from its signal, how many messages got through, how fast, and what it costs.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (healthScores.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No scores yet. They appear once the MeshSat service is running.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        healthScores.forEach { hs ->
            val scoreColor = healthScoreColor(hs.score)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Words.channelColor(hs.interfaceId), CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Words.channel(hs.interfaceId),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!hs.available) {
                        Text(
                            text = "Not connected",
                            style = MaterialTheme.typography.labelLarge,
                            color = MeshSatTextMuted,
                            modifier = Modifier
                                .background(MeshSatTextMuted.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${hs.score}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = PlexMono,
                        color = scoreColor,
                        modifier = Modifier
                            .background(scoreColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Health bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MeshSatBorder, RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(hs.score.coerceIn(0, 100) / 100f)
                            .height(6.dp)
                            .background(scoreColor, RoundedCornerShape(3.dp)),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // The parts of the score, each out of 100
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ScoreColumn("Signal", hs.signal)
                    ScoreColumn("Got through", (hs.successRate * 100).toInt())
                    ScoreColumn(
                        "Speed",
                        if (hs.latencyMs > 0) (100 - (hs.latencyMs / 1000).coerceAtMost(100)) else 0,
                    )
                    ScoreColumn("Low cost", hs.costScore)
                }
            }
        }
    }
}

@Composable
private fun ScoreColumn(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = PlexMono,
            color = MeshSatTextPrimary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Rules tab: the routing rules of every link (read-only)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AccessRulesTabContent() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val rules by db.accessRuleDao().getAll().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "The routing rules of every link. Change them in Routing rules.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No routing rules yet. Add them in Routing rules.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        rules.forEach { rule ->
            AccessRuleCard(rule)
        }
    }
}

@Composable
private fun AccessRuleCard(rule: AccessRuleEntity) {
    val route = when {
        rule.direction == "egress" -> "Messages leaving by ${Words.channel(rule.interfaceId)}"
        rule.action == "forward" && rule.forwardTo.isNotBlank() ->
            "${Words.channel(rule.interfaceId)} to ${Words.channel(rule.forwardTo)}"
        else -> "Messages from ${Words.channel(rule.interfaceId)}"
    }
    val stateColor = if (rule.enabled) MeshSatGreen else MeshSatTextMuted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .then(if (!rule.enabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rule.name.ifBlank { "Rule ${rule.id}" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (rule.enabled) "On" else "Off",
                style = MaterialTheme.typography.labelLarge,
                color = stateColor,
                modifier = Modifier
                    .background(stateColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Text(
            text = "${ruleActionLabel(rule.action)}: $route",
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextSecondary,
        )

        val matches = rule.matchCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        Text(
            text = if (matches == 0) "No matches yet" else Words.count(matches, "match", "matches"),
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Groups tab: named groups of nodes, senders or message types (read-only)
// ═══════════════════════════════════════════════════════════════════════

/** A group's type ("node_group", "sender_group", ...) in plain words. */
private fun groupTypeLabel(type: String): String = when (type.lowercase().removeSuffix("_group")) {
    "node" -> "Nodes"
    "sender" -> "Senders"
    "portnum" -> "Message types"
    "contact" -> "Contacts"
    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun ObjectGroupsTabContent() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    var groups by remember { mutableStateOf<List<ObjectGroupEntity>>(emptyList()) }

    // Re-read now and then, like the other tabs, so a group added through the local API or a
    // config import shows up without leaving the screen.
    LaunchedEffect(Unit) {
        while (true) {
            groups = try {
                db.objectGroupDao().getAll()
            } catch (_: Exception) {
                groups
            }
            delay(GROUPS_REFRESH_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Named groups of nodes, senders or message types that rules can match.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No groups yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        }

        groups.forEach { group ->
            val memberCount = try {
                JSONArray(group.members).length()
            } catch (_: Exception) {
                0
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = group.label.ifBlank { group.id },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Words.count(memberCount, "member"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextSecondary,
                    )
                }
                Text(
                    text = groupTypeLabel(group.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Backup links tab: failover and broadcast groups (read-only)
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FailoverTabContent() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    var groups by remember { mutableStateOf<List<FailoverGroupEntity>>(emptyList()) }
    var memberCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    // Re-read now and then, like the other tabs.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val allGroups = db.failoverGroupDao().getAllGroups()
                memberCounts = allGroups.associate { g ->
                    g.id to db.failoverGroupDao().getMembers(g.id).size
                }
                groups = allGroups
            } catch (_: Exception) {
                // keep what is shown; try again on the next round
            }
            delay(GROUPS_REFRESH_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Groups of links that stand in for each other, or that all carry the same message.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No backup links set up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        }

        groups.forEach { group ->
            val count = memberCounts[group.id] ?: 0
            val mode = when (group.mode) {
                "failover" -> "Uses the first link that works"
                "broadcast" -> "Sends on every link"
                else -> group.mode.replaceFirstChar { it.uppercase() }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = group.label.ifBlank { group.id },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Words.count(count, "link"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextSecondary,
                    )
                }
                Text(
                    text = mode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════

/** Working, trying, failed; a link that is off or switched off is grey, never red. */
private fun stateColor(state: InterfaceState): Color = when (state) {
    InterfaceState.Online -> MeshSatGreen
    InterfaceState.Connecting -> MeshSatAmber
    InterfaceState.Offline -> MeshSatTextMuted
    InterfaceState.Error -> MeshSatRed
    InterfaceState.Disabled -> MeshSatTextMuted
}

private fun healthScoreColor(score: Int): Color = when {
    score >= 80 -> MeshSatGreen
    score >= 50 -> MeshSatAmber
    score > 0 -> MeshSatRed
    else -> MeshSatTextMuted
}
