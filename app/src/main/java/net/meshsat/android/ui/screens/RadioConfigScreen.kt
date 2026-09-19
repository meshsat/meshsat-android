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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.geeksville.mesh.ChannelProtos
import com.geeksville.mesh.ConfigProtos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.MeshtasticProtocol
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.components.RegionCheck
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBg
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono

// ═══════════════════════════════════════════════════════════════════════
// Radio Configuration: MESHSAT-243, revamped in MESHSAT-1249
//
// Every section is edited on top of what the radio reported (toBuilder of the loaded config): a
// field the user did not see loaded is never sent, and nothing can be applied before the radio's
// own settings have arrived. Built-in defaults once sent region US, Long Fast, 0 dBm, Bluetooth on
// with PIN 123456 and WiFi off to a radio whose config had not loaded yet.
//
// What the firmware does with each apply (AdminModule::handleSetConfig in meshsat-firmware):
// LoRa and channels apply live without a restart (older firmware restarts for LoRa); owner, position,
// Bluetooth and network changes restart the node about 5 s later, so BLE drops and reconnects.
// ═══════════════════════════════════════════════════════════════════════

private enum class RadioTab(val label: String) {
    Identity("Name"),
    RadioConfig("Radio"),
    Channels("Channels"),
    Position("Position"),
    Bluetooth("Bluetooth"),
    Network("WiFi"),
    DeviceAdmin("Restart and reset"),
}

private const val READING = "Reading the radio's settings..."
private const val RESTARTS = "Sent to the radio. It restarts to apply the change."

@Composable
fun RadioConfigScreen(onConnect: () -> Unit = {}) {
    val scope = rememberCoroutineScope()

    val ble = GatewayService.meshtasticBle
    val connected = ble?.state?.collectAsState()?.value == MeshtasticBle.State.Connected
    val myNodeNum = ble?.myInfo?.collectAsState()?.value?.myNodeNum ?: 0L
    // Admin messages are addressed to our own node number; without it nothing can be sent.
    val canSend = connected && myNodeNum != 0L

    var activeTab by remember { mutableStateOf(RadioTab.Identity) }

    // The radio sends its settings with want_config on every connect (MeshtasticBle.startSession).
    // If they have not arrived a few seconds later, ask once more for the settings alone.
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        delay(5_000)
        val b = GatewayService.meshtasticBle ?: return@LaunchedEffect
        if (b.loraConfig.value == null || b.channels.value.isEmpty()) {
            b.sendToRadio(MeshtasticProtocol.encodeWantConfig(MeshtasticProtocol.WANT_CONFIG_ONLY_CONFIG))
        }
    }

    // After a change that applies without a restart, read the settings back so this screen shows
    // what the radio actually holds (it may also correct a value), not what was typed.
    val readBack: () -> Unit = {
        scope.launch {
            delay(2_500)
            GatewayService.meshtasticBle?.sendToRadio(
                MeshtasticProtocol.encodeWantConfig(MeshtasticProtocol.WANT_CONFIG_ONLY_CONFIG),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (!connected) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Your phone is not connected to your node, so its settings cannot be read or changed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextSecondary,
                )
                Button(
                    onClick = onConnect,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Connect your node")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Tab bar: 48 dp targets.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RadioTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) MeshSatSurfaceLight else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { activeTab = tab })
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MeshSatTextPrimary else MeshSatTextMuted,
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

        val onSend: (ByteArray) -> Unit = { data -> GatewayService.meshtasticBle?.sendToRadio(data) }

        when (activeTab) {
            RadioTab.Identity -> IdentityTabContent(canSend, connected, myNodeNum, onSend)
            RadioTab.RadioConfig -> RadioConfigTabContent(canSend, connected, myNodeNum, onSend, readBack)
            RadioTab.Channels -> ChannelsTabContent(canSend, connected, myNodeNum, onSend, readBack)
            RadioTab.Position -> PositionTabContent(canSend, connected, myNodeNum, onSend)
            RadioTab.Bluetooth -> BluetoothTabContent(canSend, connected, myNodeNum, onSend)
            RadioTab.Network -> NetworkTabContent(canSend, connected, myNodeNum, onSend)
            RadioTab.DeviceAdmin -> DeviceAdminTabContent(canSend, myNodeNum, onSend)
        }
    }
}

/** Shown in place of a section until the radio has reported it. */
@Composable
private fun NotLoaded(connected: Boolean) {
    Text(
        text = if (connected) READING else "Connect your node to read its settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MeshSatTextSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Name tab: device name, hardware, node ID
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun IdentityTabContent(
    canSend: Boolean,
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle

    val ownerName = ble?.ownerName?.collectAsState()?.value ?: ""
    val ownerShortName = ble?.ownerShortName?.collectAsState()?.value ?: ""
    val metadata = ble?.deviceMetadata?.collectAsState()?.value
    val nodes = ble?.nodes?.collectAsState()?.value ?: emptyList()
    val myNode = nodes.find { it.nodeNum == myNodeNum }
    val ownerLoaded = ownerName.isNotEmpty() && myNode != null

    var editLongName by remember(ownerName) { mutableStateOf(ownerName) }
    var editShortName by remember(ownerShortName) { mutableStateOf(ownerShortName) }
    val changed = editLongName.trim() != ownerName || editShortName.trim() != ownerShortName

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfigCard(title = "This node") {
            InfoRow("Node ID", if (myNodeNum != 0L) MeshtasticProtocol.formatNodeId(myNodeNum) else "-", mono = true)
            val hw = myNode?.hwModel?.takeIf { it != 0 } ?: metadata?.hwModel ?: 0
            InfoRow("Hardware", if (hw != 0) MeshtasticProtocol.hardwareName(hw) else "-")
            InfoRow("Firmware", metadata?.firmwareVersion?.ifBlank { null } ?: "-", mono = true)
            InfoRow(
                "Has",
                metadata?.let { md ->
                    buildList {
                        if (md.hasWifi) add("WiFi")
                        if (md.hasBluetooth) add("Bluetooth")
                        if (md.hasEthernet) add("Ethernet")
                        if (md.canShutdown) add("Power off")
                    }.joinToString(", ").ifEmpty { "-" }
                } ?: "-",
            )
        }

        ConfigCard(title = "Name") {
            Hint("The long name shows in other people's node lists. The short name, up to 4 characters, is used where space is tight.")
            if (!ownerLoaded) {
                NotLoaded(connected)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editLongName,
                    onValueChange = { editLongName = it.take(39) },
                    label = { Text("Long name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editShortName,
                    onValueChange = { editShortName = it.take(4) },
                    label = { Text("Short name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        // is_licensed goes back as the radio reported it: sending false would switch
                        // a licensed (ham) node out of licensed mode and change its keys.
                        onSend(
                            MeshtasticProtocol.buildAdminSetOwner(
                                myNodeNum,
                                editLongName.trim(),
                                editShortName.trim(),
                                isLicensed = myNode?.isLicensed ?: false,
                            ),
                        )
                        Toast.makeText(context, RESTARTS, Toast.LENGTH_LONG).show()
                    },
                    enabled = canSend && changed && editLongName.isNotBlank() && editShortName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Save name")
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Radio tab: LoRa region, preset, transmit power, hops
// ═══════════════════════════════════════════════════════════════════════

private fun regionLabel(code: Int): String = when {
    code == MeshtasticProtocol.LoRaRegion.Unset.code -> "Not set"
    else -> MeshtasticProtocol.LoRaRegion.entries.find { it.code == code }?.label ?: "Region code $code"
}

private fun presetLabel(code: Int): String =
    MeshtasticProtocol.ModemPreset.entries.find { it.code == code }?.label ?: "Preset code $code"

/** Spreading factor, bandwidth and coding rate of each preset, as the firmware sets them. */
private fun presetDetails(code: Int): String? = when (code) {
    0 -> "spreading factor 11, bandwidth 250 kHz, coding rate 4/5"
    1 -> "spreading factor 12, bandwidth 125 kHz, coding rate 4/8"
    2 -> "spreading factor 12, bandwidth 62.5 kHz, coding rate 4/8"
    3 -> "spreading factor 10, bandwidth 250 kHz, coding rate 4/5"
    4 -> "spreading factor 9, bandwidth 250 kHz, coding rate 4/5"
    5 -> "spreading factor 8, bandwidth 250 kHz, coding rate 4/5"
    6 -> "spreading factor 7, bandwidth 250 kHz, coding rate 4/5"
    7 -> "spreading factor 11, bandwidth 125 kHz, coding rate 4/8"
    8 -> "spreading factor 7, bandwidth 500 kHz, coding rate 4/5"
    else -> null
}

@Composable
private fun RadioConfigTabContent(
    canSend: Boolean,
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
    readBack: () -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val loaded = ble?.loraConfig?.collectAsState()?.value
    if (loaded == null) {
        NotLoaded(connected)
        return
    }
    val phoneCountry = remember { RegionCheck.phoneCountry(context) }

    // Enum values are read and written as numbers: a region or preset newer than the bundled
    // proto would throw on .number and must survive an apply untouched.
    var region by remember(loaded) { mutableIntStateOf(loaded.regionValue) }
    var preset by remember(loaded) { mutableIntStateOf(loaded.modemPresetValue) }
    var presetPicked by remember(loaded) { mutableStateOf(false) }
    var txPower by remember(loaded) { mutableStateOf(loaded.txPower.toString()) }
    var hopLimit by remember(loaded) { mutableStateOf(loaded.hopLimit.toString()) }
    var txEnabled by remember(loaded) { mutableStateOf(loaded.txEnabled) }

    var showRegionPicker by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val power = txPower.toIntOrNull()
    val hops = hopLimit.toIntOrNull()
    // Whatever the radio reported is accepted as it is; a new value must be in range.
    val powerOk = power != null && (power in 0..30 || power == loaded.txPower)
    val hopsOk = hops != null && (hops in 1..7 || hops == loaded.hopLimit)
    val regionChanged = region != loaded.regionValue
    val presetChanged = presetPicked && (preset != loaded.modemPresetValue || !loaded.usePreset)
    val powerChanged = powerOk && power != loaded.txPower
    val hopsChanged = hopsOk && hops != loaded.hopLimit
    val txChanged = txEnabled != loaded.txEnabled
    val changed = regionChanged || presetChanged || powerChanged || hopsChanged || txChanged

    fun applyNow() {
        val b = loaded.toBuilder()
        if (regionChanged) b.setRegionValue(region)
        if (presetChanged) {
            b.setModemPresetValue(preset)
            b.setUsePreset(true)
        }
        if (powerChanged && power != null) b.setTxPower(power)
        if (hopsChanged && hops != null) b.setHopLimit(hops)
        if (txChanged) b.setTxEnabled(txEnabled)
        val config = ConfigProtos.Config.newBuilder().setLora(b.build()).build()
        onSend(MeshtasticProtocol.buildAdminSetConfig(myNodeNum, config.toByteArray()))
        Toast.makeText(
            context,
            "Sent to the radio. It switches over in a few seconds; older firmware restarts to do it.",
            Toast.LENGTH_LONG,
        ).show()
        readBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfigCard(title = "Region") {
            Hint("The radio band for the country you are in. Every node on your mesh uses the same one.")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRegionPicker = true },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(regionLabel(region))
            }
            RegionCheck.warning(region, phoneCountry)?.let { warning ->
                Spacer(modifier = Modifier.height(8.dp))
                StatusBanner(warning, MeshSatAmber)
            }
        }

        ConfigCard(title = "Preset") {
            Hint("How far and how fast the radio talks. Every node on your mesh must use the same preset.")
            Spacer(modifier = Modifier.height(8.dp))
            val custom = !loaded.usePreset && !presetPicked
            OutlinedButton(
                onClick = { showPresetPicker = true },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (custom) "Custom settings" else presetLabel(preset))
            }
            TextButton(
                onClick = { showDetails = !showDetails },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (showDetails) "Hide details" else "Details")
            }
            if (showDetails) {
                val text = if (custom) {
                    "Custom: spreading factor ${loaded.spreadFactor}, bandwidth ${loaded.bandwidth} kHz, " +
                        "coding rate 4/${loaded.codingRate}. Picking a preset replaces these."
                } else {
                    presetDetails(preset)?.let { "${presetLabel(preset)}: $it. Radios on 2.4 GHz use wider bandwidths." }
                        ?: "This app does not know the details of this preset."
                }
                Hint(text)
            }
        }

        ConfigCard(title = "Transmit power") {
            Hint("In dBm. 0 means the highest power allowed in your region.")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = txPower,
                onValueChange = { v -> txPower = v.filter { it.isDigit() }.take(2) },
                label = { Text("dBm") },
                singleLine = true,
                enabled = canSend,
                isError = !powerOk,
                supportingText = if (!powerOk) {
                    { Text("Enter a number from 0 to 30.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ConfigCard(title = "Hops") {
            Hint("How many times other nodes pass your messages on, 1 to 7. Fewer keeps the mesh quieter.")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = hopLimit,
                onValueChange = { v -> hopLimit = v.filter { it.isDigit() }.take(1) },
                label = { Text("Hops") },
                singleLine = true,
                enabled = canSend,
                isError = !hopsOk,
                supportingText = if (!hopsOk) {
                    { Text("Enter a number from 1 to 7.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ConfigCard(title = "Transmit") {
            ToggleRow(
                label = "Transmit",
                checked = txEnabled,
                hint = "Off makes your node listen only: nothing you send leaves it.",
                enabled = canSend,
            ) { txEnabled = it }
        }

        Button(
            onClick = {
                if (regionChanged || presetChanged || (loaded.txEnabled && !txEnabled)) showConfirm = true else applyNow()
            },
            enabled = canSend && changed && powerOk && hopsOk,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Apply")
        }
    }

    if (showRegionPicker) {
        PickerDialog(
            title = "Region",
            options = MeshtasticProtocol.LoRaRegion.entries
                .filter { it != MeshtasticProtocol.LoRaRegion.Unset }
                .map { it.code to it.label },
            selected = region,
            onSelect = { region = it; showRegionPicker = false },
            onDismiss = { showRegionPicker = false },
        )
    }

    if (showPresetPicker) {
        PickerDialog(
            title = "Preset",
            options = MeshtasticProtocol.ModemPreset.entries.map { it.code to it.label },
            selected = if (!loaded.usePreset && !presetPicked) -1 else preset,
            onSelect = { preset = it; presetPicked = true; showPresetPicker = false },
            onDismiss = { showPresetPicker = false },
        )
    }

    if (showConfirm) {
        val consequences = buildList {
            if (regionChanged || presetChanged) {
                add("Changing the region or preset can cut you off from other nodes until they change too.")
            }
            if (loaded.txEnabled && !txEnabled) {
                add("With transmit off, nothing you send reaches the mesh, and other nodes stop hearing your node.")
            }
        }
        ConfirmDialog(
            title = "Apply these radio settings?",
            message = consequences.joinToString("\n\n"),
            confirmLabel = "Apply",
            onConfirm = {
                showConfirm = false
                applyNow()
            },
            onDismiss = { showConfirm = false },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Channels tab
// ═══════════════════════════════════════════════════════════════════════

private fun roleLabel(role: Int): String = when (role) {
    1 -> "Main channel"
    2 -> "Extra channel"
    else -> "Off"
}

private fun roleHint(role: Int): String = when (role) {
    1 -> "Every node on this mesh shares it."
    2 -> "A group channel beside the main one."
    else -> "Not in use."
}

/** What the channel key means, in words, and a one-line hint. */
private fun channelKey(psk: ByteArray, role: Int): Pair<String, String> = when {
    psk.isEmpty() && role == 2 -> "Channel key: same as the main channel" to "It is as private as the main channel."
    psk.isEmpty() || (psk.size == 1 && psk[0] == 0.toByte()) ->
        "Channel key: none (not encrypted)" to "Anyone in range can read it."
    psk.size == 1 -> "Channel key: default (not private)" to "Every Meshtastic radio knows this key, so anyone can read it."
    else -> "Channel key: private" to "Only nodes that have this key can read it."
}

@Composable
private fun ChannelsTabContent(
    canSend: Boolean,
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
    readBack: () -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val channels = ble?.channels?.collectAsState()?.value ?: emptyList()

    var editingChannel by remember { mutableStateOf<MeshtasticProtocol.MeshChannel?>(null) }
    var pendingSave by remember { mutableStateOf<Pair<MeshtasticProtocol.MeshChannel, MeshtasticProtocol.MeshChannel>?>(null) }

    if (channels.isEmpty()) {
        NotLoaded(connected)
        return
    }

    // Only what the user changed is written over the radio's own ChannelSettings.
    fun save(original: MeshtasticProtocol.MeshChannel, updated: MeshtasticProtocol.MeshChannel) {
        val settings = (
            original.settings
                ?: ChannelProtos.ChannelSettings.newBuilder()
                    .setPsk(com.google.protobuf.ByteString.copyFrom(original.psk))
                    .build()
            ).toBuilder()
        if (updated.name != original.name) settings.setName(updated.name)
        if (updated.uplinkEnabled != original.uplinkEnabled) settings.setUplinkEnabled(updated.uplinkEnabled)
        if (updated.downlinkEnabled != original.downlinkEnabled) settings.setDownlinkEnabled(updated.downlinkEnabled)
        val protoChannel = ChannelProtos.Channel.newBuilder()
            .setIndex(updated.index)
            .setRoleValue(updated.role)
            .setSettings(settings.build())
            .build()
        onSend(MeshtasticProtocol.buildAdminSetChannel(myNodeNum, protoChannel))
        Toast.makeText(context, "Sent to the radio.", Toast.LENGTH_SHORT).show()
        readBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hint("Nodes hear each other on a channel when they share its name and key.")

        channels.forEach { ch ->
            val (keyLabel, keyHint) = channelKey(ch.psk, ch.role)
            ConfigCard(title = "Channel ${ch.index}") {
                Text(
                    text = ch.name.ifEmpty { if (ch.role == 1) "Default name" else "No name" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeshSatTextPrimary,
                )
                Text(
                    text = "${roleLabel(ch.role)}. ${roleHint(ch.role)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ch.role == 0) MeshSatTextMuted else MeshSatTextSecondary,
                )
                if (ch.role != 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = keyLabel, style = MaterialTheme.typography.bodyMedium, color = MeshSatTextSecondary)
                    Hint(keyHint)
                    if (ch.uplinkEnabled || ch.downlinkEnabled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = listOfNotNull(
                                "Send to MQTT".takeIf { ch.uplinkEnabled },
                                "Receive from MQTT".takeIf { ch.downlinkEnabled },
                            ).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { editingChannel = ch },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Edit")
                }
            }
        }
    }

    editingChannel?.let { ch ->
        ChannelEditDialog(
            channel = ch,
            canSend = canSend,
            onSave = { updated ->
                editingChannel = null
                val roleChanged = updated.role != ch.role
                val renamedInUse = updated.name != ch.name && ch.role != 0
                if (roleChanged || renamedInUse) pendingSave = ch to updated else save(ch, updated)
            },
            onDismiss = { editingChannel = null },
        )
    }

    pendingSave?.let { (original, updated) ->
        ConfirmDialog(
            title = "Change channel ${original.index}?",
            message = "Changing a channel's name or role can cut you off from nodes that still use the old one " +
                "until they change too.",
            confirmLabel = "Change",
            onConfirm = {
                pendingSave = null
                save(original, updated)
            },
            onDismiss = { pendingSave = null },
        )
    }
}

@Composable
private fun ChannelEditDialog(
    channel: MeshtasticProtocol.MeshChannel,
    canSend: Boolean,
    onSave: (MeshtasticProtocol.MeshChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(channel.name) }
    var role by remember { mutableIntStateOf(channel.role) }
    var uplinkEnabled by remember { mutableStateOf(channel.uplinkEnabled) }
    var downlinkEnabled by remember { mutableStateOf(channel.downlinkEnabled) }
    var showRolePicker by remember { mutableStateOf(false) }

    // Channel 0 is always the main channel, and there is only one: the picker cannot break that.
    val roleOptions = buildList {
        if (channel.role == 1) add(1 to roleLabel(1))
        add(2 to roleLabel(2))
        add(0 to roleLabel(0))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("Channel ${channel.index}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(11) },
                    label = { Text("Channel name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (channel.index == 0) {
                    Text(roleLabel(1), style = MaterialTheme.typography.bodyMedium)
                    Hint("Channel 0 is always the main channel.")
                } else {
                    OutlinedButton(
                        onClick = { showRolePicker = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(roleLabel(role))
                    }
                    Hint(roleHint(role))
                }
                ToggleRow(
                    label = "Send to MQTT",
                    checked = uplinkEnabled,
                    hint = "Copies this channel's messages to an internet server when the node has internet.",
                ) { uplinkEnabled = it }
                ToggleRow(
                    label = "Receive from MQTT",
                    checked = downlinkEnabled,
                    hint = "Brings messages from that server onto this channel.",
                ) { downlinkEnabled = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        channel.copy(
                            name = name.trim(),
                            role = if (channel.index == 0) channel.role else role,
                            uplinkEnabled = uplinkEnabled,
                            downlinkEnabled = downlinkEnabled,
                        ),
                    )
                },
                enabled = canSend,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (showRolePicker) {
        PickerDialog(
            title = "Channel role",
            options = roleOptions,
            selected = role,
            onSelect = { role = it; showRolePicker = false },
            onDismiss = { showRolePicker = false },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Position tab
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun PositionTabContent(
    canSend: Boolean,
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val loaded = ble?.positionConfig?.collectAsState()?.value
    if (loaded == null) {
        NotLoaded(connected)
        return
    }

    var gpsEnabled by remember(loaded) { mutableStateOf(loaded.gpsEnabled) }
    var fixedPosition by remember(loaded) { mutableStateOf(loaded.fixedPosition) }
    var broadcastSecs by remember(loaded) { mutableStateOf(loaded.positionBroadcastSecs.toString()) }
    var smartEnabled by remember(loaded) { mutableStateOf(loaded.positionBroadcastSmartEnabled) }

    val secs = broadcastSecs.toIntOrNull()
    val changed = gpsEnabled != loaded.gpsEnabled ||
        fixedPosition != loaded.fixedPosition ||
        (secs != null && secs != loaded.positionBroadcastSecs) ||
        smartEnabled != loaded.positionBroadcastSmartEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfigCard(title = "GPS") {
            ToggleRow("GPS on", gpsEnabled, enabled = canSend) { gpsEnabled = it }
            ToggleRow(
                label = "Fixed position",
                checked = fixedPosition,
                hint = "Use a position set by hand instead of the GPS.",
                enabled = canSend,
            ) { fixedPosition = it }
        }

        ConfigCard(title = "Sharing") {
            Hint("How often the node shares its position, in seconds. 0 uses the default, 15 minutes.")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = broadcastSecs,
                onValueChange = { v -> broadcastSecs = v.filter { it.isDigit() }.take(5) },
                label = { Text("Every (seconds)") },
                singleLine = true,
                enabled = canSend,
                isError = secs == null,
                supportingText = if (secs == null) {
                    { Text("Enter a number of seconds.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow(
                label = "Smart sharing",
                checked = smartEnabled,
                hint = "Shares sooner when the node moves.",
                enabled = canSend,
            ) { smartEnabled = it }
        }

        Button(
            onClick = {
                val b = loaded.toBuilder()
                if (gpsEnabled != loaded.gpsEnabled) b.setGpsEnabled(gpsEnabled)
                if (fixedPosition != loaded.fixedPosition) b.setFixedPosition(fixedPosition)
                if (secs != null && secs != loaded.positionBroadcastSecs) b.setPositionBroadcastSecs(secs)
                if (smartEnabled != loaded.positionBroadcastSmartEnabled) b.setPositionBroadcastSmartEnabled(smartEnabled)
                val config = ConfigProtos.Config.newBuilder().setPosition(b.build()).build()
                onSend(MeshtasticProtocol.buildAdminSetConfig(myNodeNum, config.toByteArray()))
                Toast.makeText(context, RESTARTS, Toast.LENGTH_LONG).show()
            },
            enabled = canSend && changed && secs != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Apply")
        }
        Hint("Applying restarts the node. The phone reconnects by itself.")
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bluetooth tab
// ═══════════════════════════════════════════════════════════════════════

private fun pairingLabel(mode: Int): String = when (mode) {
    0 -> "PIN shown on the node's screen"
    1 -> "Fixed PIN"
    2 -> "No PIN"
    else -> "Pairing mode $mode"
}

@Composable
private fun BluetoothTabContent(
    canSend: Boolean,
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val loaded = ble?.bluetoothConfig?.collectAsState()?.value
    if (loaded == null) {
        NotLoaded(connected)
        return
    }

    var btEnabled by remember(loaded) { mutableStateOf(loaded.enabled) }
    var pairingMode by remember(loaded) { mutableIntStateOf(loaded.modeValue) }
    var fixedPin by remember(loaded) {
        mutableStateOf(if (loaded.fixedPin != 0) "%06d".format(loaded.fixedPin) else "")
    }
    var showModePicker by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val pin = fixedPin.toIntOrNull()
    val pinOk = pairingMode != 1 || (fixedPin.length == 6 && pin != null)
    val pinChanged = pairingMode == 1 && pin != null && pin != loaded.fixedPin
    val changed = btEnabled != loaded.enabled || pairingMode != loaded.modeValue || pinChanged

    fun applyNow() {
        val b = loaded.toBuilder()
        if (btEnabled != loaded.enabled) b.setEnabled(btEnabled)
        if (pairingMode != loaded.modeValue) b.setModeValue(pairingMode)
        if (pinChanged && pin != null) b.setFixedPin(pin)
        val config = ConfigProtos.Config.newBuilder().setBluetooth(b.build()).build()
        onSend(MeshtasticProtocol.buildAdminSetConfig(myNodeNum, config.toByteArray()))
        Toast.makeText(context, RESTARTS, Toast.LENGTH_LONG).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfigCard(title = "Bluetooth") {
            ToggleRow(
                label = "Bluetooth on",
                checked = btEnabled,
                hint = "This is how your phone talks to the node.",
                enabled = canSend,
            ) { btEnabled = it }
        }

        ConfigCard(title = "Pairing") {
            OutlinedButton(
                onClick = { showModePicker = true },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(pairingLabel(pairingMode))
            }
            if (pairingMode == 2) {
                Spacer(modifier = Modifier.height(4.dp))
                Hint("Anyone nearby can pair with the node.")
            }
            if (pairingMode == 1) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fixedPin,
                    onValueChange = { v -> fixedPin = v.filter { it.isDigit() }.take(6) },
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = canSend,
                    isError = !pinOk,
                    supportingText = if (!pinOk) {
                        { Text("Enter 6 digits.") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = { if (loaded.enabled && !btEnabled) showConfirm = true else applyNow() },
            enabled = canSend && changed && pinOk,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Apply")
        }
        Hint("Applying restarts the node. The phone reconnects by itself.")
    }

    if (showModePicker) {
        PickerDialog(
            title = "Pairing",
            options = listOf(0 to pairingLabel(0), 1 to pairingLabel(1), 2 to pairingLabel(2)),
            selected = pairingMode,
            onSelect = { pairingMode = it; showModePicker = false },
            onDismiss = { showModePicker = false },
        )
    }

    if (showConfirm) {
        ConfirmDialog(
            title = "Turn off Bluetooth?",
            message = "You will lose the connection to this node from the phone, and with it the satellite modem. " +
                "Turning it back on then needs the node itself or a USB cable.",
            confirmLabel = "Turn off",
            danger = true,
            onConfirm = {
                showConfirm = false
                applyNow()
            },
            onDismiss = { showConfirm = false },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// WiFi tab
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun NetworkTabContent(
    canSend: Boolean,
    connected: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val ble = GatewayService.meshtasticBle
    val loaded = ble?.networkConfig?.collectAsState()?.value
    val metadata = ble?.deviceMetadata?.collectAsState()?.value
    if (loaded == null) {
        NotLoaded(connected)
        return
    }
    if (metadata != null && !metadata.hasWifi) {
        Text(
            text = "This node has no WiFi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextSecondary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    var wifiEnabled by remember(loaded) { mutableStateOf(loaded.wifiEnabled) }
    var wifiSsid by remember(loaded) { mutableStateOf(loaded.wifiSsid) }
    var wifiPsk by remember(loaded) { mutableStateOf(loaded.wifiPsk) }
    var showPassword by remember { mutableStateOf(false) }

    val changed = wifiEnabled != loaded.wifiEnabled || wifiSsid != loaded.wifiSsid || wifiPsk != loaded.wifiPsk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfigCard(title = "WiFi") {
            ToggleRow(
                label = "WiFi on",
                checked = wifiEnabled,
                hint = "Lets the node reach the internet, for MQTT, when a network is in range.",
                enabled = canSend,
            ) { wifiEnabled = it }
            if (wifiEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = wifiSsid,
                    onValueChange = { wifiSsid = it.take(32) },
                    label = { Text("Network name") },
                    singleLine = true,
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = wifiPsk,
                    onValueChange = { wifiPsk = it.take(64) },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = canSend,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = {
                val b = loaded.toBuilder()
                if (wifiEnabled != loaded.wifiEnabled) b.setWifiEnabled(wifiEnabled)
                if (wifiSsid != loaded.wifiSsid) b.setWifiSsid(wifiSsid)
                if (wifiPsk != loaded.wifiPsk) b.setWifiPsk(wifiPsk)
                val config = ConfigProtos.Config.newBuilder().setNetwork(b.build()).build()
                onSend(MeshtasticProtocol.buildAdminSetConfig(myNodeNum, config.toByteArray()))
                Toast.makeText(context, RESTARTS, Toast.LENGTH_LONG).show()
            },
            enabled = canSend && changed,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Apply")
        }
        Hint("Applying restarts the node. The phone reconnects by itself.")
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Restart and reset tab
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun DeviceAdminTabContent(
    canSend: Boolean,
    myNodeNum: Long,
    onSend: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val metadata = GatewayService.meshtasticBle?.deviceMetadata?.collectAsState()?.value

    var rebootDelay by remember { mutableStateOf("5") }
    var showFactoryResetConfirm by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }
    var showShutdownConfirm by remember { mutableStateOf(false) }
    var showNodeDbResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfigCard(title = "Clock") {
            Hint("Sets the node's clock to the phone's time.")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    onSend(MeshtasticProtocol.buildAdminSetTime(myNodeNum, System.currentTimeMillis() / 1000))
                    Toast.makeText(context, "Sent to the radio.", Toast.LENGTH_SHORT).show()
                },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Set the clock")
            }
        }

        ConfigCard(title = "Restart") {
            Hint("Restarts the node after a delay. The phone reconnects by itself.")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = rebootDelay,
                onValueChange = { v -> rebootDelay = v.filter { it.isDigit() }.take(4) },
                label = { Text("Delay (seconds)") },
                singleLine = true,
                enabled = canSend,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRebootConfirm = true },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Restart the node")
            }
        }

        ConfigCard(title = "Switch off") {
            val canShutdown = metadata?.canShutdown != false
            Hint(
                if (canShutdown) "Switches the node off. Someone has to switch it on again at the node."
                else "This node cannot switch itself off.",
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showShutdownConfirm = true },
                enabled = canSend && canShutdown,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Switch off the node")
            }
        }

        ConfigCard(title = "Forget heard nodes") {
            Hint("Clears the node's list of the nodes it has heard. They come back as they transmit again.")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showNodeDbResetConfirm = true },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Forget heard nodes")
            }
        }

        ConfigCard(title = "Factory reset") {
            Text(
                text = "Erases every setting on the node and restores the factory ones. This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatRed,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showFactoryResetConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed, contentColor = MeshSatBg),
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Factory reset")
            }
        }
    }

    if (showFactoryResetConfirm) {
        ConfirmDialog(
            title = "Erase every setting on your node?",
            message = "Its region, channels, keys and name go back to the factory ones and it restarts. " +
                "It will no longer hear your mesh until it is set up again, and the phone may have to pair " +
                "with it again. This cannot be undone.",
            confirmLabel = "Erase",
            danger = true,
            onConfirm = {
                showFactoryResetConfirm = false
                onSend(MeshtasticProtocol.buildAdminFactoryReset(myNodeNum))
                Toast.makeText(context, "Sent to the radio. It erases its settings and restarts.", Toast.LENGTH_LONG).show()
            },
            onDismiss = { showFactoryResetConfirm = false },
        )
    }

    if (showRebootConfirm) {
        val secs = rebootDelay.toIntOrNull() ?: 5
        ConfirmDialog(
            title = "Restart your node?",
            message = "In $secs seconds the phone loses the node, the mesh and the satellite modem until the node " +
                "is back, usually within a minute. The phone reconnects by itself.",
            confirmLabel = "Restart",
            onConfirm = {
                showRebootConfirm = false
                onSend(MeshtasticProtocol.buildAdminReboot(myNodeNum, secs))
                Toast.makeText(context, "Sent to the radio. It restarts in $secs seconds.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showRebootConfirm = false },
        )
    }

    if (showShutdownConfirm) {
        ConfirmDialog(
            title = "Switch off your node?",
            message = "The phone loses the connection to it, and with it the mesh and the satellite modem, " +
                "until someone switches it on again at the node.",
            confirmLabel = "Switch off",
            danger = true,
            onConfirm = {
                showShutdownConfirm = false
                onSend(MeshtasticProtocol.buildAdminShutdown(myNodeNum, 5))
                Toast.makeText(context, "Sent to the radio. It switches off in 5 seconds.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showShutdownConfirm = false },
        )
    }

    if (showNodeDbResetConfirm) {
        ConfirmDialog(
            title = "Forget heard nodes?",
            message = "Your node clears its list of the nodes it has heard. They come back as they transmit again.",
            confirmLabel = "Forget",
            onConfirm = {
                showNodeDbResetConfirm = false
                onSend(MeshtasticProtocol.buildAdminNodeDbReset(myNodeNum))
                Toast.makeText(context, "Sent to the radio.", Toast.LENGTH_SHORT).show()
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
            color = MeshSatTextPrimary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MeshSatTextMuted,
    )
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
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
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) PlexMono else null,
            color = MeshSatTextSecondary,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Hint(hint)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun StatusBanner(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(10.dp),
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
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MeshSatTextPrimary)
        },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MeshSatTextSecondary)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (danger) {
                    ButtonDefaults.buttonColors(containerColor = MeshSatRed, contentColor = MeshSatBg)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) MeshSatSurfaceLight else Color.Transparent)
                            .clickable { onSelect(code) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) MeshSatTextPrimary else MeshSatTextSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
