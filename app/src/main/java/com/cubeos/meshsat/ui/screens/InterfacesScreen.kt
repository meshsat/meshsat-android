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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.data.AccessRuleEntity
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.FailoverGroupEntity
import com.cubeos.meshsat.data.FailoverMemberEntity
import com.cubeos.meshsat.data.ObjectGroupEntity
import com.cubeos.meshsat.engine.HealthScore
import com.cubeos.meshsat.engine.InterfaceState
import com.cubeos.meshsat.engine.InterfaceStatus
import com.cubeos.meshsat.service.GatewayService
import org.json.JSONArray
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
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════
// Interface Management — Phase I
// Tabbed UI for transport interfaces, channel registry, and health scores
// ═══════════════════════════════════════════════════════════════════════

private enum class IfaceTab(val label: String) {
    Interfaces("Interfaces"),
    AccessRules("Access Rules"),
    Channels("Channels"),
    ObjectGroups("Object Groups"),
    Failover("Failover"),
    Health("Health"),
}

@Composable
fun InterfacesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ifaceStates by GatewayService.ifaceManager?.states
        ?.collectAsState()
        ?: remember { mutableStateOf<Map<String, InterfaceStatus>>(emptyMap()) }

    var activeTab by remember { mutableStateOf(IfaceTab.Interfaces) }

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
            text = "Interfaces",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "Transport configuration and health monitoring",
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
            IfaceTab.entries.forEach { tab ->
                val selected = activeTab == tab
                val badgeCount = when (tab) {
                    IfaceTab.Interfaces -> ifaceStates.count { it.value.state == InterfaceState.Online }
                    IfaceTab.Health -> healthScores.count { it.score < 50 && it.available }
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
                            IfaceTab.Interfaces -> MeshSatGreen
                            IfaceTab.Health -> MeshSatAmber
                            else -> MeshSatTeal
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
                    Toast.makeText(context, "$id enabled", Toast.LENGTH_SHORT).show()
                },
                onDisable = { id ->
                    GatewayService.ifaceManager?.disable(id)
                    Toast.makeText(context, "$id disabled", Toast.LENGTH_SHORT).show()
                },
                onReconnect = { id ->
                    GatewayService.ifaceManager?.reconnectNow(id)
                    Toast.makeText(context, "Reconnecting $id...", Toast.LENGTH_SHORT).show()
                },
            )

            IfaceTab.AccessRules -> AccessRulesTabContent()

            IfaceTab.Channels -> ChannelsTabContent()

            IfaceTab.ObjectGroups -> ObjectGroupsTabContent()

            IfaceTab.Failover -> FailoverTabContent()

            IfaceTab.Health -> HealthTabContent(healthScores = healthScores)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Interfaces Tab — live transport status with controls
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InterfacesTabContent(
    interfaces: Map<String, InterfaceStatus>,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onReconnect: (String) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Active transport interfaces",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (interfaces.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No interfaces registered.\nStart the gateway service first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        }

        // Sort: mesh first, then iridium, then sms
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
                fmt = fmt,
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
    fmt: SimpleDateFormat,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onReconnect: () -> Unit,
) {
    val stateColor = stateColor(status.state)
    val ifaceColor = interfaceTypeColor(status.channelType)
    val isDisabled = status.state == InterfaceState.Disabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .then(if (isDisabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier)
            .padding(12.dp),
    ) {
        // Row 1: state dot + ID + channel type badge + enable toggle
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
                // State indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(stateColor, CircleShape),
                )

                // Interface ID
                Text(
                    text = status.id,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )

                // Channel type badge
                Text(
                    text = channelTypeLabel(status.channelType),
                    style = MaterialTheme.typography.labelSmall,
                    color = ifaceColor,
                    modifier = Modifier
                        .background(ifaceColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Switch(
                checked = !isDisabled,
                onCheckedChange = { enabled ->
                    if (enabled) onEnable() else onDisable()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
            )
        }

        // Row 2: state label + error
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status.state.name.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = stateColor,
                modifier = Modifier
                    .background(stateColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            if (status.error.isNotBlank()) {
                Text(
                    text = status.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatRed.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Row 3: timestamps + reconnect info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status.lastOnline > 0) {
                Text(
                    text = "Online: ${fmt.format(Date(status.lastOnline))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }

            if (status.lastActivity > 0) {
                Text(
                    text = "Activity: ${fmt.format(Date(status.lastActivity))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }

            if (status.reconnectAttempts > 0) {
                Text(
                    text = "Retries: ${status.reconnectAttempts}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatAmber,
                )
            }
        }

        // Row 4: reconnect button (only when offline/error)
        if (status.state in listOf(InterfaceState.Offline, InterfaceState.Error)) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onReconnect,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                modifier = Modifier.height(32.dp),
            ) {
                Text(
                    text = "Reconnect Now",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Channels Tab — read-only channel registry capabilities
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
            text = "Registered transport channel capabilities",
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
                    text = "No channels registered.\nStart the gateway service first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        }

        channels.forEach { ch ->
            val color = interfaceTypeColor(ch.id)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                // Header: label + badge
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = ch.label,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = ch.id,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier
                            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Capability grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CapabilityRow("MTU", "${ch.maxPayload}B")
                        CapabilityRow("Binary", if (ch.binaryCapable) "Yes" else "No")
                        CapabilityRow("Paid", if (ch.isPaid) "Yes" else "Free")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CapabilityRow("Send", if (ch.canSend) "Yes" else "No")
                        CapabilityRow("Receive", if (ch.canReceive) "Yes" else "No")
                        CapabilityRow("Satellite", if (ch.isSatellite) "Yes" else "No")
                    }
                }

                // Retry config (if enabled)
                if (ch.retryConfig.enabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Retry: ${ch.retryConfig.backoffFunc}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextSecondary,
                        )
                        Text(
                            text = "Init: ${ch.retryConfig.initialWait.inWholeSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextMuted,
                        )
                        Text(
                            text = "Max: ${ch.retryConfig.maxWait.inWholeSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextMuted,
                        )
                        if (ch.retryConfig.maxRetries > 0) {
                            Text(
                                text = "x${ch.retryConfig.maxRetries}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MeshSatTextMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MeshSatTextMuted,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (value == "Yes") MeshSatGreen
            else if (value == "No") MeshSatTextMuted
            else MeshSatTextSecondary,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Health Tab — composite health scores per interface
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
            text = "Composite health: Signal(30%) + Success(30%) + Latency(20%) + Cost(20%)",
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
                    text = "No health data available.\nStart the gateway service first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        }

        healthScores.forEach { hs ->
            val scoreColor = healthScoreColor(hs.score)
            val ifaceColor = interfaceTypeColor(
                hs.interfaceId.substringBefore("_")
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                // Header: interface ID + score badge + availability
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = hs.interfaceId,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (!hs.available) {
                            Text(
                                text = "OFFLINE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MeshSatRed,
                                modifier = Modifier
                                    .background(MeshSatRed.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    // Score badge
                    Text(
                        text = "${hs.score}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
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
                            .fillMaxWidth(hs.score / 100f)
                            .height(6.dp)
                            .background(scoreColor, RoundedCornerShape(3.dp)),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Component scores
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ScoreColumn("Signal", hs.signal, MeshSatTeal)
                    ScoreColumn("Success", (hs.successRate * 100).toInt(), MeshSatGreen)
                    ScoreColumn(
                        "Latency",
                        if (hs.latencyMs > 0) (100 - (hs.latencyMs / 1000).coerceAtMost(100)) else 0,
                        MeshSatAmber,
                    )
                    ScoreColumn("Cost", hs.costScore, ColorIridium)
                }
            }
        }
    }
}

@Composable
private fun ScoreColumn(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MeshSatTextMuted,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Access Rules Tab — read-only list of forwarding access rules
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
            text = "Access control rules for transport interfaces",
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
                    text = "No access rules configured.\nAdd rules in the Rules screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
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
    val actionColor = when (rule.action) {
        "forward" -> MeshSatGreen
        "drop" -> MeshSatRed
        "log" -> MeshSatAmber
        else -> MeshSatTextMuted
    }
    val dirColor = when (rule.direction) {
        "ingress" -> ColorMesh
        "egress" -> ColorIridium
        else -> MeshSatTextMuted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .then(if (!rule.enabled) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier)
            .padding(12.dp),
    ) {
        // Row 1: name + enabled indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rule.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (rule.enabled) "ENABLED" else "DISABLED",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (rule.enabled) MeshSatGreen else MeshSatTextMuted,
                modifier = Modifier
                    .background(
                        (if (rule.enabled) MeshSatGreen else MeshSatTextMuted).copy(alpha = 0.12f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: interface + direction + action badges
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rule.interfaceId,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MeshSatTextSecondary,
            )
            Text(
                text = rule.direction.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = dirColor,
                modifier = Modifier
                    .background(dirColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                text = rule.action.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = actionColor,
                modifier = Modifier
                    .background(actionColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // Row 3: match stats
        if (rule.matchCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Matches: ${rule.matchCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MeshSatTextMuted,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Object Groups Tab — read-only list of object groups
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ObjectGroupsTabContent() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    var groups by remember { mutableStateOf<List<ObjectGroupEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        groups = db.objectGroupDao().getAll()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Named groups of nodes, portnums, or senders",
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
                    text = "No object groups defined.",
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
            val typeColor = when (group.type) {
                "node" -> ColorMesh
                "portnum" -> ColorIridium
                "sender" -> ColorCellular
                else -> MeshSatTeal
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.label,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = group.type.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = typeColor,
                            modifier = Modifier
                                .background(typeColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Text(
                        text = "$memberCount members",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextSecondary,
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Failover Tab — read-only list of failover/broadcast groups
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FailoverTabContent() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    var groups by remember { mutableStateOf<List<FailoverGroupEntity>>(emptyList()) }
    var memberCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val allGroups = db.failoverGroupDao().getAllGroups()
        groups = allGroups
        memberCounts = allGroups.associate { g ->
            g.id to db.failoverGroupDao().getMembers(g.id).size
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Failover and broadcast interface groups",
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
                    text = "No failover groups configured.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                )
            }
        }

        groups.forEach { group ->
            val modeColor = when (group.mode) {
                "failover" -> MeshSatAmber
                "broadcast" -> ColorMesh
                else -> MeshSatTeal
            }
            val count = memberCounts[group.id] ?: 0

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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.label,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = group.mode.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = modeColor,
                            modifier = Modifier
                                .background(modeColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Text(
                        text = "$count members",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextSecondary,
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════

private fun stateColor(state: InterfaceState): Color = when (state) {
    InterfaceState.Online -> MeshSatGreen
    InterfaceState.Connecting -> MeshSatAmber
    InterfaceState.Offline -> MeshSatTextMuted
    InterfaceState.Error -> MeshSatRed
    InterfaceState.Disabled -> MeshSatBorder
}

private fun interfaceTypeColor(channelType: String): Color = when (channelType) {
    "mesh" -> ColorMesh
    "iridium", "iridium9704" -> ColorIridium
    "sms", "cellular" -> ColorCellular
    else -> MeshSatTeal
}

private fun channelTypeLabel(channelType: String): String = when (channelType) {
    "mesh" -> "Mesh"
    "iridium" -> "Iridium 9603"
    "iridium9704" -> "Iridium 9704"
    "sms" -> "SMS"
    "cellular" -> "Cellular"
    else -> channelType
}

private fun healthScoreColor(score: Int): Color = when {
    score >= 80 -> MeshSatGreen
    score >= 50 -> MeshSatAmber
    score > 0 -> MeshSatRed
    else -> MeshSatTextMuted
}
