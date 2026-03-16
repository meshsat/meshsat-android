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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.ble.MeshtasticProtocol
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════
// Radio Configuration — Phase L
// LoRa radio config + device admin commands via Meshtastic admin proto
// ═══════════════════════════════════════════════════════════════════════

private enum class RadioTab(val label: String) {
    RadioConfig("Radio Config"),
    DeviceAdmin("Device Admin"),
}

@Composable
fun RadioConfigScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val myInfo = GatewayService.meshtasticBle?.myInfo?.collectAsState()
    val connected = meshState?.value == MeshtasticBle.State.Connected
    val myNodeNum = myInfo?.value?.myNodeNum ?: 0L

    var activeTab by remember { mutableStateOf(RadioTab.RadioConfig) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Radio Configuration",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "Meshtastic radio settings and device administration",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Connection status banner
        if (!connected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatRed.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .border(1.dp, MeshSatRed.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "Radio not connected. Connect via BLE in Settings first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatRed,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Tab bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RadioTab.entries.forEach { tab ->
                Row(
                    modifier = Modifier
                        .background(
                            if (activeTab == tab) MeshSatTeal.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { activeTab = tab }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal,
                        color = if (activeTab == tab) MeshSatTeal else MeshSatTextMuted,
                    )
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
            RadioTab.RadioConfig -> RadioConfigTabContent(
                connected = connected,
                myNodeNum = myNodeNum,
                onSend = { data ->
                    GatewayService.meshtasticBle?.sendToRadio(data)
                },
            )

            RadioTab.DeviceAdmin -> DeviceAdminTabContent(
                connected = connected,
                myNodeNum = myNodeNum,
                onSend = { data ->
                    GatewayService.meshtasticBle?.sendToRadio(data)
                },
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Radio Config Tab — LoRa region, modem preset, TX power, hop limit
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun RadioConfigTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedRegion by remember { mutableIntStateOf(MeshtasticProtocol.LoRaRegion.US.code) }
    var selectedPreset by remember { mutableIntStateOf(MeshtasticProtocol.ModemPreset.LongFast.code) }
    var txPower by remember { mutableStateOf("0") }
    var hopLimit by remember { mutableStateOf("3") }
    var txEnabled by remember { mutableStateOf(true) }

    var showRegionPicker by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "LoRa radio parameters — changes are sent to the connected radio",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // Region selector
        ConfigCard(title = "Region") {
            Text(
                text = "Defines regulatory frequency band for your country",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRegionPicker = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(MeshtasticProtocol.LoRaRegion.fromCode(selectedRegion).label)
            }
        }

        // Modem preset selector
        ConfigCard(title = "Modem Preset") {
            Text(
                text = "Predefined LoRa modulation settings (SF, BW, CR)",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showPresetPicker = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(MeshtasticProtocol.ModemPreset.fromCode(selectedPreset).label)
            }
        }

        // TX Power
        ConfigCard(title = "TX Power (dBm)") {
            Text(
                text = "Transmit power. 0 = use region default. Max varies by radio hardware.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = txPower,
                onValueChange = { v -> txPower = v.filter { it.isDigit() }.take(2) },
                label = { Text("dBm (0-30)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    cursorColor = MeshSatTeal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Hop limit
        ConfigCard(title = "Hop Limit") {
            Text(
                text = "Maximum number of mesh relay hops (1-7). Lower = less network load.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = hopLimit,
                onValueChange = { v -> hopLimit = v.filter { it.isDigit() }.take(1) },
                label = { Text("Hops (1-7)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    cursorColor = MeshSatTeal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // TX enabled toggle
        ConfigCard(title = "Transmit Enabled") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Allow radio to transmit",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = txEnabled,
                    onCheckedChange = { txEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }
        }

        // Apply button
        Button(
            onClick = {
                if (!connected) {
                    Toast.makeText(context, "Radio not connected", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val power = txPower.toIntOrNull() ?: 0
                val hops = hopLimit.toIntOrNull()?.coerceIn(1, 7) ?: 3
                val configData = MeshtasticProtocol.encodeLoRaConfig(
                    region = selectedRegion,
                    modemPreset = selectedPreset,
                    txPower = power,
                    hopLimit = hops,
                    txEnabled = txEnabled,
                )
                val toRadio = MeshtasticProtocol.buildAdminSetConfig(myNodeNum, configData)
                onSend(toRadio)
                Toast.makeText(context, "LoRa config sent to radio", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply LoRa Config")
        }

        // Request current config button
        OutlinedButton(
            onClick = {
                if (!connected) {
                    Toast.makeText(context, "Radio not connected", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                val toRadio = MeshtasticProtocol.buildAdminGetConfig(myNodeNum, MeshtasticProtocol.CONFIG_LORA)
                onSend(toRadio)
                Toast.makeText(context, "Requested LoRa config from radio", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Request Current Config")
        }
    }

    // Region picker dialog
    if (showRegionPicker) {
        PickerDialog(
            title = "Select Region",
            options = MeshtasticProtocol.LoRaRegion.entries.map { it.code to it.label },
            selected = selectedRegion,
            onSelect = { selectedRegion = it; showRegionPicker = false },
            onDismiss = { showRegionPicker = false },
        )
    }

    // Preset picker dialog
    if (showPresetPicker) {
        PickerDialog(
            title = "Select Modem Preset",
            options = MeshtasticProtocol.ModemPreset.entries.map { it.code to it.label },
            selected = selectedPreset,
            onSelect = { selectedPreset = it; showPresetPicker = false },
            onDismiss = { showPresetPicker = false },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Device Admin Tab — reboot, factory reset, time sync
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun DeviceAdminTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rebootDelay by remember { mutableStateOf("5") }
    var showFactoryResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Device administration commands sent via Meshtastic admin channel",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // Sync time
        ConfigCard(title = "Sync Time") {
            Text(
                text = "Set the radio's clock to the phone's current UTC time",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!connected) {
                        Toast.makeText(context, "Radio not connected", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val unixSec = System.currentTimeMillis() / 1000
                    val toRadio = MeshtasticProtocol.buildAdminSetTime(myNodeNum, unixSec)
                    onSend(toRadio)
                    Toast.makeText(context, "Time sync sent", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sync Time Now")
            }
        }

        // Reboot
        ConfigCard(title = "Reboot Radio") {
            Text(
                text = "Reboot the connected Meshtastic radio after a delay",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = rebootDelay,
                onValueChange = { v -> rebootDelay = v.filter { it.isDigit() }.take(4) },
                label = { Text("Delay (seconds)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatAmber,
                    cursorColor = MeshSatAmber,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!connected) {
                        Toast.makeText(context, "Radio not connected", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val secs = rebootDelay.toIntOrNull() ?: 5
                    val toRadio = MeshtasticProtocol.buildAdminReboot(myNodeNum, secs)
                    onSend(toRadio)
                    Toast.makeText(context, "Reboot command sent (${secs}s delay)", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reboot Radio")
            }
        }

        // Request configs (debug/diagnostic)
        ConfigCard(title = "Request Config Sections") {
            Text(
                text = "Request configuration from the radio for diagnostics",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val configSections = listOf(
                "Device" to MeshtasticProtocol.CONFIG_DEVICE,
                "Position" to MeshtasticProtocol.CONFIG_POSITION,
                "Power" to MeshtasticProtocol.CONFIG_POWER,
                "Network" to MeshtasticProtocol.CONFIG_NETWORK,
                "Display" to MeshtasticProtocol.CONFIG_DISPLAY,
                "LoRa" to MeshtasticProtocol.CONFIG_LORA,
                "Bluetooth" to MeshtasticProtocol.CONFIG_BLUETOOTH,
                "Security" to MeshtasticProtocol.CONFIG_SECURITY,
            )
            configSections.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (label, type) ->
                        OutlinedButton(
                            onClick = {
                                if (!connected) {
                                    Toast.makeText(context, "Radio not connected", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                val toRadio = MeshtasticProtocol.buildAdminGetConfig(myNodeNum, type)
                                onSend(toRadio)
                                Toast.makeText(context, "Requested $label config", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                            enabled = connected,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Factory reset — danger zone
        ConfigCard(title = "Factory Reset") {
            Text(
                text = "Erase all settings and restore factory defaults. This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatRed,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showFactoryResetConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Factory Reset")
            }
        }
    }

    // Factory reset confirmation dialog
    if (showFactoryResetConfirm) {
        AlertDialog(
            onDismissRequest = { showFactoryResetConfirm = false },
            containerColor = MeshSatSurface,
            title = {
                Text(
                    "Confirm Factory Reset",
                    style = MaterialTheme.typography.titleMedium,
                    color = MeshSatRed,
                )
            },
            text = {
                Text(
                    "This will erase ALL settings on the connected radio and restore factory defaults. " +
                        "The radio will reboot. This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFactoryResetConfirm = false
                        val toRadio = MeshtasticProtocol.buildAdminFactoryReset(myNodeNum)
                        onSend(toRadio)
                        Toast.makeText(context, "Factory reset command sent", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showFactoryResetConfirm = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTextSecondary),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Shared UI components
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ConfigCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { (code, label) ->
                    val isSelected = code == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MeshSatTeal.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { onSelect(code) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MeshSatTeal else Color.Unspecified,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTextSecondary),
            ) {
                Text("Cancel")
            }
        },
    )
}
