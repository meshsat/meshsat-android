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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
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
import com.geeksville.mesh.ChannelProtos
import com.geeksville.mesh.ConfigProtos
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════
// Radio Configuration — MESHSAT-243
// Full Meshtastic radio config: Identity, LoRa, Channels, Position,
// Bluetooth, Network, Power, Display, Device Admin
// ═══════════════════════════════════════════════════════════════════════

private enum class RadioTab(val label: String) {
    Identity("Identity"),
    RadioConfig("LoRa"),
    Channels("Channels"),
    Position("Position"),
    Bluetooth("Bluetooth"),
    Network("Network"),
    DeviceAdmin("Admin"),
}

@Composable
fun RadioConfigScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ble = GatewayService.meshtasticBle
    val meshState = ble?.state?.collectAsState()
    val myInfo = ble?.myInfo?.collectAsState()
    val connected = meshState?.value == MeshtasticBle.State.Connected
    val myNodeNum = myInfo?.value?.myNodeNum ?: 0L

    var activeTab by remember { mutableStateOf(RadioTab.Identity) }

    // Auto-request all config sections on first connect
    LaunchedEffect(connected) {
        if (connected && myNodeNum != 0L) {
            val ble = GatewayService.meshtasticBle ?: return@LaunchedEffect
            // Request all 8 config sections
            for (configType in 0..7) {
                ble.sendToRadio(MeshtasticProtocol.buildAdminGetConfig(myNodeNum, configType))
                kotlinx.coroutines.delay(100)
            }
            // Request all 8 channels
            for (i in 0..7) {
                ble.sendToRadio(MeshtasticProtocol.buildAdminGetChannel(myNodeNum, i))
                kotlinx.coroutines.delay(100)
            }
            // Request device metadata
            ble.sendToRadio(MeshtasticProtocol.buildAdminGetDeviceMetadata(myNodeNum))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Meshtastic",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "Radio settings and device administration",
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

        val onSend: (ByteArray) -> Unit = { data -> ble?.sendToRadio(data) }

        // Tab content
        when (activeTab) {
            RadioTab.Identity -> IdentityTabContent(connected, myNodeNum, onSend)
            RadioTab.RadioConfig -> RadioConfigTabContent(connected, myNodeNum, onSend)
            RadioTab.Channels -> ChannelsTabContent(connected, myNodeNum, onSend)
            RadioTab.Position -> PositionTabContent(connected, myNodeNum, onSend)
            RadioTab.Bluetooth -> BluetoothTabContent(connected, myNodeNum, onSend)
            RadioTab.Network -> NetworkTabContent(connected, myNodeNum, onSend)
            RadioTab.DeviceAdmin -> DeviceAdminTabContent(connected, myNodeNum, onSend)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Identity Tab — Device name, short name, HW model, node ID
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun IdentityTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle

    val ownerName by ble?.ownerName?.collectAsState() ?: remember { mutableStateOf("") }
    val ownerShortName by ble?.ownerShortName?.collectAsState() ?: remember { mutableStateOf("") }
    val metadata by ble?.deviceMetadata?.collectAsState() ?: remember { mutableStateOf(null) }
    val nodes by ble?.nodes?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val myNode = nodes.find { it.nodeNum == myNodeNum }

    var editLongName by remember(ownerName) { mutableStateOf(ownerName) }
    var editShortName by remember(ownerShortName) { mutableStateOf(ownerShortName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Read-only info
        ConfigCard(title = "Device Info") {
            InfoRow("Node ID", if (myNodeNum != 0L) MeshtasticProtocol.formatNodeId(myNodeNum) else "—")
            InfoRow("Hardware", myNode?.let { hwModelName(it.hwModel) } ?: "—")
            InfoRow("Firmware", metadata?.firmwareVersion ?: "—")
            InfoRow("Capabilities", buildString {
                metadata?.let { md ->
                    val caps = mutableListOf<String>()
                    if (md.hasWifi) caps.add("WiFi")
                    if (md.hasBluetooth) caps.add("BT")
                    if (md.hasEthernet) caps.add("Eth")
                    if (md.canShutdown) caps.add("Shutdown")
                    append(caps.joinToString(", ").ifEmpty { "—" })
                } ?: append("—")
            })
        }

        // Editable name
        ConfigCard(title = "Device Name") {
            Text(
                text = "Long name is shown in the mesh node list. Short name (max 4 chars) is used as a compact identifier.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = editLongName,
                onValueChange = { editLongName = it.take(40) },
                label = { Text("Long Name") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    cursorColor = MeshSatTeal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = editShortName,
                onValueChange = { editShortName = it.take(4) },
                label = { Text("Short Name (max 4)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    cursorColor = MeshSatTeal,
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
                    val toRadio = MeshtasticProtocol.buildAdminSetOwner(
                        myNodeNum, editLongName, editShortName
                    )
                    onSend(toRadio)
                    Toast.makeText(context, "Device name updated", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Name")
            }
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
    val ble = GatewayService.meshtasticBle
    val loraConfig by ble?.loraConfig?.collectAsState() ?: remember { mutableStateOf(null) }

    var selectedRegion by remember(loraConfig) {
        mutableIntStateOf(loraConfig?.region?.number ?: MeshtasticProtocol.LoRaRegion.US.code)
    }
    var selectedPreset by remember(loraConfig) {
        mutableIntStateOf(loraConfig?.modemPreset?.number ?: MeshtasticProtocol.ModemPreset.LongFast.code)
    }
    var txPower by remember(loraConfig) {
        mutableStateOf(loraConfig?.txPower?.toString() ?: "0")
    }
    var hopLimit by remember(loraConfig) {
        mutableStateOf(loraConfig?.hopLimit?.toString() ?: "3")
    }
    var txEnabled by remember(loraConfig) {
        mutableStateOf(loraConfig?.txEnabled ?: true)
    }

    var showRegionPicker by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loraConfig != null) {
            StatusBanner("Config loaded from radio", MeshSatGreen)
        }

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
                onValueChange = { v -> txPower = v.filter { it.isDigit() || it == '-' }.take(3) },
                label = { Text("dBm") },
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
            ToggleRow("Allow radio to transmit", txEnabled) { txEnabled = it }
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
// Channels Tab — View/edit all 8 channels
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ChannelsTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val channels by ble?.channels?.collectAsState() ?: remember { mutableStateOf(emptyList()) }

    var editingChannel by remember { mutableStateOf<MeshtasticProtocol.MeshChannel?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (channels.isEmpty()) {
            Text(
                text = "No channels loaded. Connect to a radio to see channel configuration.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        channels.forEach { ch ->
            val roleName = when (ch.role) {
                1 -> "PRIMARY"
                2 -> "SECONDARY"
                else -> "DISABLED"
            }
            val roleColor = when (ch.role) {
                1 -> MeshSatGreen
                2 -> MeshSatTeal
                else -> MeshSatTextMuted
            }

            ConfigCard(title = "Channel ${ch.index}") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = ch.name.ifEmpty { "(default)" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = roleName,
                            style = MaterialTheme.typography.labelSmall,
                            color = roleColor,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "PSK: ${pskHashLetter(ch.psk)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MeshSatTextSecondary,
                        )
                        Text(
                            text = "${ch.psk.size} bytes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
                if (ch.uplinkEnabled || ch.downlinkEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            if (ch.uplinkEnabled) append("Uplink ")
                            if (ch.downlinkEnabled) append("Downlink")
                        }.trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatAmber,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { editingChannel = ch },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connected,
                ) {
                    Text("Edit")
                }
            }
        }
    }

    // Channel editor dialog
    editingChannel?.let { ch ->
        ChannelEditDialog(
            channel = ch,
            connected = connected,
            onSave = { updatedChannel ->
                val protoChannel = ChannelProtos.Channel.newBuilder()
                    .setIndex(updatedChannel.index)
                    .setRole(ChannelProtos.Channel.Role.forNumber(updatedChannel.role)
                        ?: ChannelProtos.Channel.Role.DISABLED)
                    .setSettings(
                        ChannelProtos.ChannelSettings.newBuilder()
                            .setName(updatedChannel.name)
                            .setPsk(com.google.protobuf.ByteString.copyFrom(updatedChannel.psk))
                            .setUplinkEnabled(updatedChannel.uplinkEnabled)
                            .setDownlinkEnabled(updatedChannel.downlinkEnabled)
                            .build()
                    )
                    .build()
                val toRadio = MeshtasticProtocol.buildAdminSetChannel(myNodeNum, protoChannel)
                onSend(toRadio)
                Toast.makeText(context, "Channel ${updatedChannel.index} updated", Toast.LENGTH_SHORT).show()
                editingChannel = null
            },
            onDismiss = { editingChannel = null },
        )
    }
}

@Composable
private fun ChannelEditDialog(
    channel: MeshtasticProtocol.MeshChannel,
    connected: Boolean,
    onSave: (MeshtasticProtocol.MeshChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(channel.name) }
    var role by remember { mutableIntStateOf(channel.role) }
    var uplinkEnabled by remember { mutableStateOf(channel.uplinkEnabled) }
    var downlinkEnabled by remember { mutableStateOf(channel.downlinkEnabled) }
    var showRolePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("Edit Channel ${channel.index}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("Channel Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        cursorColor = MeshSatTeal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { showRolePicker = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when (role) { 1 -> "PRIMARY"; 2 -> "SECONDARY"; else -> "DISABLED" })
                }
                ToggleRow("Uplink", uplinkEnabled) { uplinkEnabled = it }
                ToggleRow("Downlink", downlinkEnabled) { downlinkEnabled = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(channel.copy(
                        name = name,
                        role = role,
                        uplinkEnabled = uplinkEnabled,
                        downlinkEnabled = downlinkEnabled,
                    ))
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                enabled = connected,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTextSecondary),
            ) {
                Text("Cancel")
            }
        },
    )

    if (showRolePicker) {
        PickerDialog(
            title = "Channel Role",
            options = listOf(0 to "DISABLED", 1 to "PRIMARY", 2 to "SECONDARY"),
            selected = role,
            onSelect = { role = it; showRolePicker = false },
            onDismiss = { showRolePicker = false },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Position Tab
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun PositionTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val posConfig by ble?.positionConfig?.collectAsState() ?: remember { mutableStateOf(null) }

    var gpsEnabled by remember(posConfig) { mutableStateOf(posConfig?.gpsEnabled ?: true) }
    var fixedPosition by remember(posConfig) { mutableStateOf(posConfig?.fixedPosition ?: false) }
    var broadcastSecs by remember(posConfig) {
        mutableStateOf(posConfig?.positionBroadcastSecs?.toString() ?: "900")
    }
    var smartEnabled by remember(posConfig) {
        mutableStateOf(posConfig?.positionBroadcastSmartEnabled ?: true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (posConfig != null) {
            StatusBanner("Config loaded from radio", MeshSatGreen)
        }

        ConfigCard(title = "GPS") {
            ToggleRow("GPS enabled", gpsEnabled) { gpsEnabled = it }
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow("Fixed position", fixedPosition) { fixedPosition = it }
        }

        ConfigCard(title = "Broadcast") {
            Text(
                text = "How often to broadcast position (seconds). 0 = default.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = broadcastSecs,
                onValueChange = { v -> broadcastSecs = v.filter { it.isDigit() }.take(5) },
                label = { Text("Broadcast interval (sec)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    cursorColor = MeshSatTeal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow("Smart position broadcast", smartEnabled) { smartEnabled = it }
        }

        Button(
            onClick = {
                if (!connected) return@Button
                val config = ConfigProtos.Config.newBuilder()
                    .setPosition(
                        ConfigProtos.Config.PositionConfig.newBuilder()
                            .setGpsEnabled(gpsEnabled)
                            .setFixedPosition(fixedPosition)
                            .setPositionBroadcastSecs(broadcastSecs.toIntOrNull() ?: 900)
                            .setPositionBroadcastSmartEnabled(smartEnabled)
                            .build()
                    ).build()
                onSend(MeshtasticProtocol.buildAdminSetConfig(myNodeNum, config.toByteArray()))
                Toast.makeText(context, "Position config sent", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply Position Config")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bluetooth Tab
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun BluetoothTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val btConfig by ble?.bluetoothConfig?.collectAsState() ?: remember { mutableStateOf(null) }

    var btEnabled by remember(btConfig) { mutableStateOf(btConfig?.enabled ?: true) }
    var pairingMode by remember(btConfig) { mutableIntStateOf(btConfig?.mode?.number ?: 0) }
    var fixedPin by remember(btConfig) {
        mutableStateOf(btConfig?.fixedPin?.toString() ?: "123456")
    }

    var showModePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (btConfig != null) {
            StatusBanner("Config loaded from radio", MeshSatGreen)
        }

        ConfigCard(title = "Bluetooth") {
            ToggleRow("Bluetooth enabled", btEnabled) { btEnabled = it }
        }

        ConfigCard(title = "Pairing Mode") {
            OutlinedButton(
                onClick = { showModePicker = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshSatTeal),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(when (pairingMode) { 0 -> "Random PIN"; 1 -> "Fixed PIN"; else -> "No PIN" })
            }
            if (pairingMode == 1) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fixedPin,
                    onValueChange = { v -> fixedPin = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Fixed PIN") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        cursorColor = MeshSatTeal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = {
                if (!connected) return@Button
                val config = ConfigProtos.Config.newBuilder()
                    .setBluetooth(
                        ConfigProtos.Config.BluetoothConfig.newBuilder()
                            .setEnabled(btEnabled)
                            .setMode(ConfigProtos.Config.BluetoothConfig.PairingMode.forNumber(pairingMode)
                                ?: ConfigProtos.Config.BluetoothConfig.PairingMode.RANDOM_PIN)
                            .setFixedPin(fixedPin.toIntOrNull() ?: 123456)
                            .build()
                    ).build()
                onSend(MeshtasticProtocol.buildAdminSetConfig(myNodeNum, config.toByteArray()))
                Toast.makeText(context, "Bluetooth config sent", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply Bluetooth Config")
        }
    }

    if (showModePicker) {
        PickerDialog(
            title = "Pairing Mode",
            options = listOf(0 to "Random PIN", 1 to "Fixed PIN", 2 to "No PIN"),
            selected = pairingMode,
            onSelect = { pairingMode = it; showModePicker = false },
            onDismiss = { showModePicker = false },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Network Tab
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun NetworkTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val netConfig by ble?.networkConfig?.collectAsState() ?: remember { mutableStateOf(null) }

    var wifiEnabled by remember(netConfig) { mutableStateOf(netConfig?.wifiEnabled ?: false) }
    var wifiSsid by remember(netConfig) { mutableStateOf(netConfig?.wifiSsid ?: "") }
    var wifiPsk by remember(netConfig) { mutableStateOf(netConfig?.wifiPsk ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (netConfig != null) {
            StatusBanner("Config loaded from radio", MeshSatGreen)
        }

        ConfigCard(title = "WiFi") {
            ToggleRow("WiFi enabled", wifiEnabled) { wifiEnabled = it }
            if (wifiEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = wifiSsid,
                    onValueChange = { wifiSsid = it.take(32) },
                    label = { Text("SSID") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        cursorColor = MeshSatTeal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = wifiPsk,
                    onValueChange = { wifiPsk = it.take(64) },
                    label = { Text("Password") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        cursorColor = MeshSatTeal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = {
                if (!connected) return@Button
                val config = ConfigProtos.Config.newBuilder()
                    .setNetwork(
                        ConfigProtos.Config.NetworkConfig.newBuilder()
                            .setWifiEnabled(wifiEnabled)
                            .setWifiSsid(wifiSsid)
                            .setWifiPsk(wifiPsk)
                            .build()
                    ).build()
                onSend(MeshtasticProtocol.buildAdminSetConfig(myNodeNum, config.toByteArray()))
                Toast.makeText(context, "Network config sent", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply Network Config")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Device Admin Tab — reboot, factory reset, time sync, node DB reset
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun DeviceAdminTabContent(
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current

    var rebootDelay by remember { mutableStateOf("5") }
    var showFactoryResetConfirm by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var showNodeDbResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    if (!connected) return@Button
                    val unixSec = System.currentTimeMillis() / 1000
                    onSend(MeshtasticProtocol.buildAdminSetTime(myNodeNum, unixSec))
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
                    if (!connected) return@Button
                    showRebootConfirm = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reboot Radio")
            }
        }

        // Shutdown
        ConfigCard(title = "Shutdown Radio") {
            Text(
                text = "Power off the connected radio (if hardware supports it)",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!connected) return@Button
                    onSend(MeshtasticProtocol.buildAdminShutdown(myNodeNum, 5))
                    Toast.makeText(context, "Shutdown command sent (5s delay)", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Shutdown Radio")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Node DB reset
        ConfigCard(title = "Reset Node Database") {
            Text(
                text = "Clear the radio's known node list. Nodes will be rediscovered via mesh.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatAmber,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showNodeDbResetConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset Node DB")
            }
        }

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

    // Factory reset confirmation
    if (showFactoryResetConfirm) {
        ConfirmDialog(
            title = "Confirm Factory Reset",
            message = "This will erase ALL settings on the connected radio and restore factory defaults. " +
                "The radio will reboot. This action cannot be undone.",
            confirmLabel = "Reset",
            confirmColor = MeshSatRed,
            onConfirm = {
                showFactoryResetConfirm = false
                onSend(MeshtasticProtocol.buildAdminFactoryReset(myNodeNum))
                Toast.makeText(context, "Factory reset command sent", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showFactoryResetConfirm = false },
        )
    }

    // Reboot confirmation
    if (showRebootConfirm) {
        val secs = rebootDelay.toIntOrNull() ?: 5
        ConfirmDialog(
            title = "Reboot Radio?",
            message = "The radio will reboot in $secs seconds. Active connections will be interrupted.",
            confirmLabel = "Reboot",
            confirmColor = MeshSatAmber,
            onConfirm = {
                onSend(MeshtasticProtocol.buildAdminReboot(myNodeNum, secs))
                Toast.makeText(context, "Reboot command sent (${secs}s delay)", Toast.LENGTH_SHORT).show()
                showRebootConfirm = false
            },
            onDismiss = { showRebootConfirm = false },
        )
    }

    // Node DB reset confirmation
    if (showNodeDbResetConfirm) {
        ConfirmDialog(
            title = "Reset Node Database?",
            message = "This will clear the radio's known node list. Nodes will be rediscovered over time.",
            confirmLabel = "Reset",
            confirmColor = MeshSatAmber,
            onConfirm = {
                showNodeDbResetConfirm = false
                onSend(MeshtasticProtocol.buildAdminNodeDbReset(myNodeNum))
                Toast.makeText(context, "Node DB reset command sent", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showNodeDbResetConfirm = false },
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
        )
    }
}

@Composable
private fun StatusBanner(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, color = confirmColor)
        },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = confirmColor),
            ) {
                Text(confirmLabel)
            }
        },
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

// ═══════════════════════════════════════════════════════════════════════
// Utilities
// ═══════════════════════════════════════════════════════════════════════

/** Compute PSK hash letter (A-Z, like Meshtastic official app). */
private fun pskHashLetter(psk: ByteArray): String {
    if (psk.isEmpty()) return "-"
    if (psk.size == 1 && psk[0] == 0.toByte()) return "—"
    // Default PSK (single byte 1) gets a special label
    if (psk.size == 1 && psk[0] == 1.toByte()) return "Default"
    val hash = psk.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
    return ('A' + (hash % 26)).toString()
}

/** Map hardware model code to human-readable name. */
private fun hwModelName(code: Int): String = when (code) {
    4 -> "T-Beam"
    7 -> "T-Echo"
    9 -> "RAK4631"
    43 -> "Heltec V3"
    48 -> "Heltec Wireless Tracker"
    50 -> "T-Deck"
    51 -> "T-Watch S3"
    65 -> "Heltec Capsule Sensor V3"
    71 -> "Tracker T1000-E"
    255 -> "Private HW"
    else -> "Unknown ($code)"
}
