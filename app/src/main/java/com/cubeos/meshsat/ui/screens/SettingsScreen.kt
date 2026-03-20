package com.cubeos.meshsat.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.service.GatewayService
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
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.codec.CannedCodebook
import com.cubeos.meshsat.ui.theme.ThemeState
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val encryptionKey by settings.encryptionKey.collectAsState(initial = "")
    val encryptionEnabled by settings.encryptionEnabled.collectAsState(initial = false)
    val autoDecrypt by settings.autoDecryptSms.collectAsState(initial = true)
    val piPhone by settings.meshsatPiPhone.collectAsState(initial = "")
    val msvqscEnabled by settings.msvqscEnabled.collectAsState(initial = false)
    val msvqscStages by settings.msvqscStages.collectAsState(initial = "3")
    val compressMesh by settings.compressMesh.collectAsState(initial = "msvqsc")
    val compressIridium by settings.compressIridium.collectAsState(initial = "off")
    val compressSms by settings.compressSms.collectAsState(initial = "msvqsc")
    val compressMqtt by settings.compressMqtt.collectAsState(initial = "off")

    val deadmanEnabled by settings.deadmanEnabled.collectAsState(initial = false)
    val deadmanTimeoutMin by settings.deadmanTimeoutMin.collectAsState(initial = "120")

    // APRS settings
    val aprsEnabled by settings.aprsEnabled.collectAsState(initial = false)
    val aprsCallsign by settings.aprsCallsign.collectAsState(initial = "")
    val aprsSsid by settings.aprsSsid.collectAsState(initial = "10")
    val aprsKissHost by settings.aprsKissHost.collectAsState(initial = "localhost")
    val aprsKissPort by settings.aprsKissPort.collectAsState(initial = "8001")
    val aprsFrequency by settings.aprsFrequency.collectAsState(initial = "144.800")

    var keyInput by remember(encryptionKey) { mutableStateOf(encryptionKey) }
    var phoneInput by remember(piPhone) { mutableStateOf(piPhone) }
    var showKey by remember { mutableStateOf(false) }

    // APRS state
    var aprsCallsignInput by remember(aprsCallsign) { mutableStateOf(aprsCallsign) }
    var aprsSsidInput by remember(aprsSsid) { mutableStateOf(aprsSsid) }
    var aprsHostInput by remember(aprsKissHost) { mutableStateOf(aprsKissHost) }
    var aprsPortInput by remember(aprsKissPort) { mutableStateOf(aprsKissPort) }
    var aprsFreqInput by remember(aprsFrequency) { mutableStateOf(aprsFrequency) }
    val aprsState = GatewayService.kissClient?.state?.collectAsState()

    // BLE state
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val iridiumState = GatewayService.iridiumSpp?.state?.collectAsState()
    val iridiumSignal = GatewayService.iridiumSpp?.signal?.collectAsState()
    val modemInfo = GatewayService.iridiumSpp?.modemInfo?.collectAsState()

    // BLE scan results
    val scanResults = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }

    // BLE permission launcher for Android 12+
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            // Permissions granted — start scan
            scanning = true
            scanResults.clear()
            GatewayService.meshtasticBle?.let { ble ->
                scope.launch {
                    ble.scanResults.collect { device ->
                        if (scanResults.none { it.address == device.address }) {
                            scanResults.add(device)
                        }
                    }
                }
                ble.startScan()
            }
        } else {
            Toast.makeText(context, "Bluetooth permissions required for BLE scan", Toast.LENGTH_LONG).show()
        }
    }

    // HC-05 paired devices
    val pairedHc05 = remember {
        GatewayService.iridiumSpp?.getPairedDevices() ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
        )

        // --- Theme ---
        SectionCard("Appearance") {
            val darkModePref by ThemeState.darkMode.collectAsState()
            val modes = listOf(true to "Dark", false to "Light", null to "System")
            Text(
                text = "Theme",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (value, label) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (darkModePref == value) MeshSatTeal else MeshSatTextMuted,
                        modifier = Modifier
                            .background(
                                if (darkModePref == value) MeshSatTeal.copy(alpha = 0.15f)
                                else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                if (darkModePref == value) MeshSatTeal else MeshSatBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { ThemeState.setDarkMode(value) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // --- Meshtastic BLE Section ---
        SectionCard("Meshtastic BLE") {
            val state = meshState?.value ?: MeshtasticBle.State.Disconnected
            ConnectionStatusRow(
                label = "Status",
                connected = state == MeshtasticBle.State.Connected,
                statusText = when (state) {
                    MeshtasticBle.State.Connected -> "Connected"
                    MeshtasticBle.State.Connecting -> "Connecting..."
                    MeshtasticBle.State.Scanning -> "Scanning..."
                    MeshtasticBle.State.Disconnected -> "Disconnected"
                },
                color = ColorMesh,
            )

            if (state == MeshtasticBle.State.Connected) {
                // Show device info when connected
                val myInfo = GatewayService.meshtasticBle?.myInfo?.collectAsState()
                val meshNodes = GatewayService.meshtasticBle?.nodes?.collectAsState()

                myInfo?.value?.let { info ->
                    if (info.firmwareVersion.isNotBlank()) {
                        InfoRow("Firmware", info.firmwareVersion)
                    }
                    InfoRow("Node ID", "!%08x".format(info.myNodeNum))
                    if (info.rebootCount > 0) {
                        InfoRow("Reboots", info.rebootCount.toString())
                    }
                }

                meshNodes?.value?.let { nodes ->
                    if (nodes.isNotEmpty()) {
                        Text(
                            text = "Mesh Nodes (${nodes.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        nodes.forEach { node ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MeshSatSurface, RoundedCornerShape(4.dp))
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = node.longName.ifBlank { "!%08x".format(node.nodeNum) },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = node.shortName.ifBlank { "" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ColorMesh,
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        context.startService(
                            Intent(context, GatewayService::class.java)
                                .setAction(GatewayService.ACTION_DISCONNECT_MESH)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disconnect", style = MaterialTheme.typography.bodySmall)
                }
            } else if (state == MeshtasticBle.State.Disconnected) {
                Button(
                    onClick = {
                        // Check BLE permissions before scanning (required on Android 12+)
                        val blePerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        val missing = blePerms.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) {
                            blePermissionLauncher.launch(missing.toTypedArray())
                        } else {
                            scanning = true
                            scanResults.clear()
                            GatewayService.meshtasticBle?.let { ble ->
                                scope.launch {
                                    ble.scanResults.collect { device ->
                                        if (scanResults.none { it.address == device.address }) {
                                            scanResults.add(device)
                                        }
                                    }
                                }
                                ble.startScan()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Scan for Meshtastic devices", style = MaterialTheme.typography.bodySmall)
                }

                if (scanResults.isNotEmpty()) {
                    Text(
                        text = "Found devices:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                    scanResults.forEach { device ->
                        @Suppress("MissingPermission")
                        DeviceRow(
                            name = device.name ?: "Unknown",
                            address = device.address,
                            onClick = {
                                scanning = false
                                GatewayService.meshtasticBle?.stopScan()
                                context.startService(
                                    Intent(context, GatewayService::class.java)
                                        .setAction(GatewayService.ACTION_CONNECT_MESH)
                                        .putExtra(GatewayService.EXTRA_ADDRESS, device.address)
                                )
                            },
                        )
                    }
                }
            }
        }

        // --- Iridium HC-05 Section ---
        SectionCard("Iridium HC-05 SPP") {
            val state = iridiumState?.value ?: IridiumSpp.State.Disconnected
            ConnectionStatusRow(
                label = "Status",
                connected = state == IridiumSpp.State.Connected,
                statusText = when (state) {
                    IridiumSpp.State.Connected -> {
                        val sig = iridiumSignal?.value ?: 0
                        "Connected (Signal: $sig/5)"
                    }
                    IridiumSpp.State.Connecting -> "Connecting..."
                    IridiumSpp.State.Disconnected -> "Disconnected"
                },
                color = ColorIridium,
            )

            // Show modem info when connected
            if (state == IridiumSpp.State.Connected) {
                modemInfo?.value?.let { info ->
                    if (info.manufacturer.isNotBlank()) {
                        InfoRow("Manufacturer", info.manufacturer)
                    }
                    if (info.model.isNotBlank()) {
                        InfoRow("Model", info.model)
                    }
                    if (info.imei.isNotBlank()) {
                        InfoRow("IMEI", info.imei)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val sig = GatewayService.iridiumSpp?.pollSignal()
                                Toast.makeText(context, "Signal: $sig/5", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Poll Signal", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            context.startService(
                                Intent(context, GatewayService::class.java)
                                    .setAction(GatewayService.ACTION_DISCONNECT_IRIDIUM)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Disconnect", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Show paired HC-05/06 devices when disconnected
            if (state == IridiumSpp.State.Disconnected) {
                if (pairedHc05.isNotEmpty()) {
                    Text(
                        text = "Paired HC-05/06 devices:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                    pairedHc05.forEach { device ->
                        @Suppress("MissingPermission")
                        DeviceRow(
                            name = device.name ?: "HC-05",
                            address = device.address,
                            onClick = {
                                context.startService(
                                    Intent(context, GatewayService::class.java)
                                        .setAction(GatewayService.ACTION_CONNECT_IRIDIUM)
                                        .putExtra(GatewayService.EXTRA_ADDRESS, device.address)
                                )
                            },
                        )
                    }
                } else {
                    Text(
                        text = "No paired HC-05/06 modules found. Pair the HC-05 in Android Bluetooth settings first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
            }
        }

        // --- Encryption Section ---
        SectionCard("Encryption") {
            SettingRow("Encryption enabled") {
                Switch(
                    checked = encryptionEnabled,
                    onCheckedChange = { scope.launch { settings.setEncryptionEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            SettingRow("Auto-decrypt incoming SMS") {
                Switch(
                    checked = autoDecrypt,
                    onCheckedChange = { scope.launch { settings.setAutoDecryptSms(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("AES-256-GCM Key (hex)", style = MaterialTheme.typography.bodySmall) },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.labelMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showKey = !showKey },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        keyInput = AesGcmCrypto.generateKey()
                        scope.launch { settings.setEncryptionKey(keyInput) }
                        Toast.makeText(context, "Key generated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Generate", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        scope.launch { settings.setEncryptionKey(keyInput) }
                        Toast.makeText(context, "Key saved", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Share / Import from clipboard
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (keyInput.isNotBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MeshSat Key", keyInput))
                            Toast.makeText(context, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Copy", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (clip.length == 64 && clip.all { it in "0123456789abcdefABCDEF" }) {
                            keyInput = clip
                            scope.launch { settings.setEncryptionKey(clip) }
                            Toast.makeText(context, "Key imported from clipboard", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Clipboard doesn't contain a valid 64-char hex key", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Paste", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        if (keyInput.isNotBlank()) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, keyInput)
                                putExtra(Intent.EXTRA_SUBJECT, "MeshSat Encryption Key")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share encryption key"))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Share", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                text = "Fallback key — used when no per-conversation key is set. Per-conversation keys are managed in Messages > tap a conversation > lock icon.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- Per-Channel Compression Section (MESHSAT-203) ---
        SectionCard("Compression") {
            Text(
                text = "Per-channel compression mode. MSVQ-SC is lossy semantic compression. " +
                        "Incoming compressed messages are always auto-detected regardless of these settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )

            Spacer(Modifier.height(8.dp))

            val channels = listOf(
                "sms" to "SMS" to compressSms,
                "mesh" to "Mesh (LoRa)" to compressMesh,
                "iridium" to "Iridium SBD" to compressIridium,
                "mqtt" to "MQTT (Hub)" to compressMqtt,
            )

            channels.forEach { (channelPair, currentMode) ->
                val (channelKey, channelLabel) = channelPair
                val modes = listOf("off" to "Off", "msvqsc" to "MSVQ-SC")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = channelLabel, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        modes.forEach { (modeKey, modeLabel) ->
                            val selected = currentMode == modeKey
                            Text(
                                text = modeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MeshSatTeal else MeshSatTextMuted,
                                modifier = Modifier
                                    .background(
                                        if (selected) MeshSatTeal.copy(alpha = 0.15f)
                                        else MeshSatSurface,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .clickable {
                                        scope.launch { settings.setCompressMode(channelKey, modeKey) }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // MSVQ-SC stages (global, applies to all channels using MSVQ-SC)
            val anyMsvqsc = compressSms == "msvqsc" || compressMesh == "msvqsc" || compressIridium == "msvqsc"
            if (anyMsvqsc) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "MSVQ-SC stages (fewer = smaller, lower fidelity)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                val stageOptions = listOf("2" to "2 (5B)", "3" to "3 (7B)", "4" to "4 (9B)", "6" to "6 (13B)", "8" to "8 (17B)")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    stageOptions.forEach { (value, label) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msvqscStages == value) MeshSatTeal else MeshSatTextMuted,
                            modifier = Modifier
                                .background(
                                    if (msvqscStages == value) MeshSatTeal.copy(alpha = 0.15f)
                                    else MeshSatSurface,
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { scope.launch { settings.setMsvqscStages(value) } }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // --- Dead Man's Switch ---
        SectionCard("Dead Man's Switch") {
            SettingRow("Enabled") {
                Switch(
                    checked = deadmanEnabled,
                    onCheckedChange = {
                        scope.launch {
                            settings.setDeadmanEnabled(it)
                            GatewayService.deadManSwitch?.setEnabled(it)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            if (deadmanEnabled) {
                val timeoutOptions = listOf("30" to "30 min", "60" to "1 hour", "120" to "2 hours", "240" to "4 hours", "480" to "8 hours")
                Text(
                    text = "Timeout (triggers SOS if no activity)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                timeoutOptions.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (deadmanTimeoutMin == value) MeshSatTeal.copy(alpha = 0.15f)
                                else MeshSatSurface,
                                RoundedCornerShape(4.dp),
                            )
                            .clickable {
                                scope.launch {
                                    settings.setDeadmanTimeoutMin(value)
                                    val mins = value.toLongOrNull() ?: 120
                                    GatewayService.deadManSwitch?.setTimeout(
                                        kotlin.time.Duration.parse("${mins}m")
                                    )
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = label, style = MaterialTheme.typography.bodySmall)
                        if (deadmanTimeoutMin == value) {
                            Text(
                                text = "selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshSatTeal,
                            )
                        }
                    }
                }

                // Show triggered state
                val dms = GatewayService.deadManSwitch
                if (dms != null && dms.isTriggered()) {
                    Text(
                        text = "TRIGGERED — SOS was sent. Tap to reset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatRed,
                        modifier = Modifier
                            .clickable { dms.touch() }
                            .padding(vertical = 4.dp),
                    )
                }
            }

            Text(
                text = "Automatically sends SOS if no user activity (message send, button press) within the timeout period.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- Channel Health ---
        SectionCard("Channel Health") {
            val healthScorer = GatewayService.healthScorer
            if (healthScorer == null) {
                Text(
                    text = "Health scorer not available. Connect a transport first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            } else {
                val interfaceStates = GatewayService.meshtasticBle?.let { "mesh_0" } ?: ""
                val channels = listOf("mesh_0", "iridium_0", "sms_0")

                // Compute live health scores
                var healthScores by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
                LaunchedEffect(Unit) {
                    try {
                        val scores = healthScorer.scoreAll()
                        healthScores = scores.associate { it.interfaceId to it.score }
                    } catch (_: Exception) {}
                }

                channels.forEach { ch ->
                    val score = healthScores[ch]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MeshSatSurface, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ch,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                ch.startsWith("mesh") -> ColorMesh
                                ch.startsWith("iridium") -> ColorIridium
                                ch.startsWith("sms") -> ColorCellular
                                else -> MeshSatTextSecondary
                            },
                        )
                        Text(
                            text = if (score != null) "score: $score/100" else "score: --",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                score == null -> MeshSatTextMuted
                                score >= 70 -> MeshSatGreen
                                score >= 40 -> MeshSatAmber
                                else -> MeshSatRed
                            },
                        )
                    }
                }

                Text(
                    text = "Health = Signal(0.3) + SuccessRate(0.3) + Latency(0.2) + Cost(0.2). Scores update in real-time based on 24h delivery history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        // --- Burst Queue Status ---
        SectionCard("Burst Queue (Iridium)") {
            val bq = GatewayService.burstQueue
            if (bq == null) {
                Text(
                    text = "Burst queue not initialized.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            } else {
                val pending = bq.pending()
                InfoRow("Pending messages", pending.toString())
                InfoRow("Max size", "${bq.maxSize} msgs")
                InfoRow("Max age", bq.maxAge.toString())
                InfoRow("Should flush", if (bq.shouldFlush()) "Yes" else "No")

                if (pending > 0) {
                    Button(
                        onClick = {
                            val (payload, count) = bq.flush()
                            Toast.makeText(
                                context,
                                if (count > 0) "Flushed $count messages (${payload?.size ?: 0} bytes)"
                                else "Queue empty",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Flush Now", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                text = "TLV-framed message queue for efficient satellite pass transmission. Messages are priority-sorted and packed into a single SBD payload (max 340 bytes).",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- Canned Messages ---
        SectionCard("Canned Messages") {
            val entries = CannedCodebook.DEFAULT_ENTRIES
            Text(
                text = "${entries.size} brevity codes loaded",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )

            entries.entries.take(10).forEach { (id, text) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "#$id",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextMuted,
                    )
                }
            }

            if (entries.size > 10) {
                Text(
                    text = "... and ${entries.size - 10} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }

            Text(
                text = "Wire format: 2 bytes (0xCA + message ID). Auto-detected on receive.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- MeshSat Pi Section ---
        SectionCard("APRS (APRSDroid KISS TCP)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enable APRS", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = aprsEnabled,
                    onCheckedChange = { scope.launch { settings.setAprsEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            // Connection status
            aprsState?.let { state ->
                val statusText = when (state.value) {
                    com.cubeos.meshsat.aprs.KissClient.State.Connected -> "Connected"
                    com.cubeos.meshsat.aprs.KissClient.State.Connecting -> "Connecting..."
                    com.cubeos.meshsat.aprs.KissClient.State.Error -> "Error"
                    com.cubeos.meshsat.aprs.KissClient.State.Disconnected -> "Disconnected"
                }
                val isOnline = state.value == com.cubeos.meshsat.aprs.KissClient.State.Connected
                ConnectionStatusRow("KISS TNC", isOnline, statusText, MeshSatTeal)
            }

            OutlinedTextField(
                value = aprsCallsignInput,
                onValueChange = { aprsCallsignInput = it.uppercase().take(6) },
                label = { Text("Callsign", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            OutlinedTextField(
                value = aprsSsidInput,
                onValueChange = { aprsSsidInput = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("SSID (0-15)", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = aprsHostInput,
                    onValueChange = { aprsHostInput = it },
                    label = { Text("KISS Host", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )
                OutlinedTextField(
                    value = aprsPortInput,
                    onValueChange = { aprsPortInput = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )
            }

            OutlinedTextField(
                value = aprsFreqInput,
                onValueChange = { aprsFreqInput = it },
                label = { Text("Frequency (MHz)", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Button(
                onClick = {
                    scope.launch {
                        settings.setAprsCallsign(aprsCallsignInput)
                        settings.setAprsSsid(aprsSsidInput)
                        settings.setAprsKissHost(aprsHostInput)
                        settings.setAprsKissPort(aprsPortInput)
                        settings.setAprsFrequency(aprsFreqInput)
                    }
                    Toast.makeText(context, "APRS settings saved", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Save", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Connect to APRSDroid's KISS TCP server for local RF APRS via AIOC + handheld radio. " +
                    "SSID 7 = handheld, 10 = igate. EU: 144.800 MHz, NA: 144.390 MHz.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        SectionCard("MeshSat Pi") {
            OutlinedTextField(
                value = phoneInput,
                onValueChange = { phoneInput = it },
                label = { Text("Pi cellular phone number", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Button(
                onClick = {
                    scope.launch { settings.setMeshsatPiPhone(phoneInput) }
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Save", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Phone number of the SIM card in MeshSat Pi's cellular modem. Used for sending encrypted SMS to the Pi.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- Crypto Tool ---
        Button(
            onClick = { navController?.navigate("decrypt") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Encrypt / Decrypt Tool", style = MaterialTheme.typography.bodyMedium)
        }

        // --- Phase I: Interface Management ---
        Button(
            onClick = { navController?.navigate("interfaces") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Interface Management", style = MaterialTheme.typography.bodyMedium)
        }

        // --- Phase L: Radio Config ---
        Button(
            onClick = { navController?.navigate("radio-config") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Radio Configuration", style = MaterialTheme.typography.bodyMedium)
        }

        // --- Phase J: Satellite Pass Predictor ---
        Button(
            onClick = { navController?.navigate("passes") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Satellite Pass Predictor", style = MaterialTheme.typography.bodyMedium)
        }

        // --- Phase G Screens ---
        Button(
            onClick = { navController?.navigate("topology") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Mesh Topology View", style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { navController?.navigate("deliveries") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delivery Status", style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { navController?.navigate("geofence") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Geofence Zones", style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { navController?.navigate("audit") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Audit Log", style = MaterialTheme.typography.bodyMedium)
        }

        // --- About ---
        Button(
            onClick = { navController?.navigate("about") },
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("About MeshSat Android", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DeviceRow(name: String, address: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = name, style = MaterialTheme.typography.bodyMedium)
            Text(text = address, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        }
        Text(text = "Connect", style = MaterialTheme.typography.bodySmall, color = MeshSatTeal)
    }
}

@Composable
private fun ConnectionStatusRow(
    label: String,
    connected: Boolean,
    statusText: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (connected) MeshSatGreen else MeshSatTextMuted,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        control()
    }
}
