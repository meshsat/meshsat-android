package com.cubeos.meshsat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.rules.ForwardingRule
import com.cubeos.meshsat.service.GatewayService
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen() {
    val context = LocalContext.current
    val rules = remember { mutableStateListOf<ForwardingRule>().apply { addAll(GatewayService.rulesEngine.getRules()) } }
    var showDialog by remember { mutableStateOf(false) }
    var nextId by remember { mutableLongStateOf((rules.maxOfOrNull { it.id } ?: 0) + 1) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = "Forwarding Rules",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "Route messages between mesh, satellite, and SMS",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (rules.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No rules configured. Tap + to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MeshSatTextMuted,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            onToggle = { enabled ->
                                val idx = rules.indexOfFirst { it.id == rule.id }
                                if (idx >= 0) {
                                    val updated = rule.copy(enabled = enabled)
                                    rules[idx] = updated
                                    syncRules(rules)
                                }
                            },
                            onDelete = {
                                rules.removeAll { it.id == rule.id }
                                syncRules(rules)
                                Toast.makeText(context, "Rule deleted", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { showDialog = true },
            containerColor = MeshSatTeal,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add rule")
        }
    }

    if (showDialog) {
        AddRuleDialog(
            onDismiss = { showDialog = false },
            onAdd = { rule ->
                val newRule = rule.copy(id = nextId)
                nextId++
                rules.add(newRule)
                syncRules(rules)
                showDialog = false
                Toast.makeText(context, "Rule added", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

private fun syncRules(rules: List<ForwardingRule>) {
    GatewayService.rulesEngine.setRules(rules)
}

@Composable
private fun RuleCard(
    rule: ForwardingRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val srcColor = transportColor(rule.sourceTransport)
    val dstColor = transportColor(rule.destTransport)

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
            Text(
                text = rule.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MeshSatRed)
                }
            }
        }

        // Source → Dest
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportBadge(rule.sourceTransport.name, srcColor)
            Text(text = "->", style = MaterialTheme.typography.bodyMedium, color = MeshSatTextMuted)
            TransportBadge(rule.destTransport.name, dstColor)

            if (rule.encrypt) {
                Text(
                    text = "ENC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatAmber,
                    modifier = Modifier
                        .background(MeshSatAmber.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // Filters
        if (!rule.filterSender.isNullOrBlank()) {
            Text(
                text = "Sender: ${rule.filterSender}",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (!rule.filterPattern.isNullOrBlank()) {
            Text(
                text = "Pattern: ${rule.filterPattern}",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }
    }
}

@Composable
private fun TransportBadge(name: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun transportColor(transport: ForwardingRule.Transport) = when (transport) {
    ForwardingRule.Transport.MESH -> ColorMesh
    ForwardingRule.Transport.IRIDIUM -> ColorIridium
    ForwardingRule.Transport.SMS -> ColorCellular
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (ForwardingRule) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(ForwardingRule.Transport.MESH) }
    var dest by remember { mutableStateOf(ForwardingRule.Transport.SMS) }
    var direction by remember { mutableStateOf(ForwardingRule.Direction.INBOUND) }
    var encrypt by remember { mutableStateOf(false) }
    var filterSender by remember { mutableStateOf("") }
    var filterPattern by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("Add Forwarding Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                // Direction dropdown
                DropdownField(
                    label = "Direction",
                    value = direction.name,
                    options = ForwardingRule.Direction.entries.map { it.name },
                    onSelect = { direction = ForwardingRule.Direction.valueOf(it) },
                )

                // Source transport
                DropdownField(
                    label = "Source",
                    value = source.name,
                    options = ForwardingRule.Transport.entries.map { it.name },
                    onSelect = { source = ForwardingRule.Transport.valueOf(it) },
                )

                // Dest transport
                DropdownField(
                    label = "Destination",
                    value = dest.name,
                    options = ForwardingRule.Transport.entries.map { it.name },
                    onSelect = { dest = ForwardingRule.Transport.valueOf(it) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Encrypt", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = encrypt,
                        onCheckedChange = { encrypt = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }

                OutlinedTextField(
                    value = filterSender,
                    onValueChange = { filterSender = it },
                    label = { Text("Sender filter (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )

                OutlinedTextField(
                    value = filterPattern,
                    onValueChange = { filterPattern = it },
                    label = { Text("Text pattern (regex, optional)") },
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
                    onAdd(
                        ForwardingRule(
                            name = name,
                            direction = direction,
                            sourceTransport = source,
                            destTransport = dest,
                            encrypt = encrypt,
                            filterSender = filterSender.ifBlank { null },
                            filterPattern = filterPattern.ifBlank { null },
                        )
                    )
                },
            ) {
                Text("Add", color = MeshSatTeal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MeshSatTextMuted)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = value,
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
                    text = { Text(option) },
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
