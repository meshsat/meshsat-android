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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.cubeos.meshsat.map.MBTilesManager
import com.cubeos.meshsat.service.GatewayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    // APRS-IS settings (MESHSAT-230)
    val aprsMode by settings.aprsMode.collectAsState(initial = "kiss")
    val aprsIsServer by settings.aprsIsServer.collectAsState(initial = "rotate.aprs2.net")
    val aprsIsPort by settings.aprsIsPort.collectAsState(initial = "14580")
    val aprsIsPasscode by settings.aprsIsPasscode.collectAsState(initial = "-1")
    val aprsIsFilterRange by settings.aprsIsFilterRange.collectAsState(initial = "100")
    val aprsIsBeaconEnabled by settings.aprsIsBeaconEnabled.collectAsState(initial = false)
    val aprsIsBeaconInterval by settings.aprsIsBeaconInterval.collectAsState(initial = "10")

    // TAK settings (MESHSAT-451)
    val takEnabled by settings.takEnabled.collectAsState(initial = false)
    val takCallsignPrefix by settings.takCallsignPrefix.collectAsState(initial = "MESHSAT")
    val takAtakBroadcast by settings.takAtakBroadcast.collectAsState(initial = true)
    val takMqttExport by settings.takMqttExport.collectAsState(initial = true)

    // RNS TCP settings (MESHSAT-268)
    val rnsTcpEnabled by settings.rnsTcpEnabled.collectAsState(initial = false)
    val rnsTcpHost by settings.rnsTcpHost.collectAsState(initial = "")
    val rnsTcpPort by settings.rnsTcpPort.collectAsState(initial = "4242")
    val rnsTcpTls by settings.rnsTcpTls.collectAsState(initial = false)

    // Hub Reporter settings (MESHSAT-292)
    val hubEnabled by settings.hubEnabled.collectAsState(initial = false)
    val hubUrl by settings.hubUrl.collectAsState(initial = "")
    val hubBridgeId by settings.hubBridgeId.collectAsState(initial = "")
    val hubCallsign by settings.hubCallsign.collectAsState(initial = "")
    val hubUsername by settings.hubUsername.collectAsState(initial = "")
    val hubPassword by settings.hubPassword.collectAsState(initial = "")
    val hubHealthInterval by settings.hubHealthInterval.collectAsState(initial = "30")

    var keyInput by remember(encryptionKey) { mutableStateOf(encryptionKey) }
    var phoneInput by remember(piPhone) { mutableStateOf(piPhone) }
    var showKey by remember { mutableStateOf(false) }

    // APRS state
    var aprsCallsignInput by remember(aprsCallsign) { mutableStateOf(aprsCallsign) }
    var aprsSsidInput by remember(aprsSsid) { mutableStateOf(aprsSsid) }
    var aprsHostInput by remember(aprsKissHost) { mutableStateOf(aprsKissHost) }
    var aprsPortInput by remember(aprsKissPort) { mutableStateOf(aprsKissPort) }
    var aprsFreqInput by remember(aprsFrequency) { mutableStateOf(aprsFrequency) }
    val aprsKissState = GatewayService.kissClient?.state?.collectAsState()
    // APRS-IS state (MESHSAT-230)
    var aprsIsServerInput by remember(aprsIsServer) { mutableStateOf(aprsIsServer) }
    var aprsIsPortInput by remember(aprsIsPort) { mutableStateOf(aprsIsPort) }
    var aprsIsPasscodeInput by remember(aprsIsPasscode) { mutableStateOf(aprsIsPasscode) }
    var aprsIsFilterRangeInput by remember(aprsIsFilterRange) { mutableStateOf(aprsIsFilterRange) }
    var aprsIsBeaconIntervalInput by remember(aprsIsBeaconInterval) { mutableStateOf(aprsIsBeaconInterval) }
    val aprsIsState = GatewayService.aprsIsClient?.state?.collectAsState()

    // TAK state (MESHSAT-451)
    var takCallsignPrefixInput by remember(takCallsignPrefix) { mutableStateOf(takCallsignPrefix) }

    // RNS TCP state (MESHSAT-268)
    var rnsTcpHostInput by remember(rnsTcpHost) { mutableStateOf(rnsTcpHost) }
    var rnsTcpPortInput by remember(rnsTcpPort) { mutableStateOf(rnsTcpPort) }
    val rnsTcpState = GatewayService.rnsTcpInterface?.state?.collectAsState()

    // Hub Reporter state (MESHSAT-292)
    var hubUrlInput by remember(hubUrl) { mutableStateOf(hubUrl) }
    var hubBridgeIdInput by remember(hubBridgeId) { mutableStateOf(hubBridgeId) }
    var hubCallsignInput by remember(hubCallsign) { mutableStateOf(hubCallsign) }
    var hubUsernameInput by remember(hubUsername) { mutableStateOf(hubUsername) }
    var hubPasswordInput by remember(hubPassword) { mutableStateOf(hubPassword) }
    var hubHealthIntervalInput by remember(hubHealthInterval) { mutableStateOf(hubHealthInterval) }
    var showHubPassword by remember { mutableStateOf(false) }

    // QR provisioning state
    var provisionBundle by remember { mutableStateOf<com.cubeos.meshsat.crypto.ProvisionImporter.ProvisionBundle?>(null) }
    var showProvisionDialog by remember { mutableStateOf(false) }
    val hubReporterState = GatewayService.hubReporter?.state?.collectAsState()

    // BLE state
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val iridiumState = GatewayService.iridiumSpp?.state?.collectAsState()
    val iridiumSignal = GatewayService.iridiumSpp?.signal?.collectAsState()
    val modemInfo = GatewayService.iridiumSpp?.modemInfo?.collectAsState()

    // Iridium 9704 state
    val iridium9704State = GatewayService.iridium9704Spp?.state?.collectAsState()
    val iridium9704Signal = GatewayService.iridium9704Spp?.signal?.collectAsState()
    val iridium9704ModemInfo = GatewayService.iridium9704Spp?.modemInfo?.collectAsState()

    // QR code scanner for key sync — handles both raw hex keys and meshsat://key/ URL bundles
    val qrScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanResult = com.journeyapps.barcodescanner.ScanIntentResult.parseActivityResult(
            result.resultCode, result.data
        )
        val scanned = scanResult.contents
        if (scanned != null && com.cubeos.meshsat.crypto.ProvisionImporter.isProvisionUrl(scanned)) {
            // Hub provisioning QR — supports both inline (base64) and nonce (HTTPS fetch) formats
            scope.launch {
                try {
                    Toast.makeText(context, "Processing provision QR...", Toast.LENGTH_SHORT).show()
                    val bundle = withContext(Dispatchers.IO) {
                        com.cubeos.meshsat.crypto.ProvisionImporter.processQr(scanned)
                    }
                    provisionBundle = bundle
                    showProvisionDialog = true
                } catch (e: com.cubeos.meshsat.crypto.ProvisionImporter.ProvisionException) {
                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    android.util.Log.e("SettingsScreen", "Provision failed", e)
                    Toast.makeText(context, "Provision failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else if (scanned != null && scanned.startsWith("meshsat://key/")) {
            // MeshSat key bundle URL — contains signed multi-channel key bundle (MESHSAT-495)
            scope.launch {
                val result = com.cubeos.meshsat.crypto.KeyBundleImporter.importFromURL(
                    scanned, context
                )
                when (result) {
                    is com.cubeos.meshsat.crypto.KeyBundleImporter.ImportResult.Success -> {
                        val trustMsg = when (result.status) {
                            com.cubeos.meshsat.crypto.KeyBundleImporter.TrustStatus.NEW_TRUSTED ->
                                "Imported ${result.count} key(s) — new bridge ${result.bridgeHashHex.take(8)} pinned"
                            com.cubeos.meshsat.crypto.KeyBundleImporter.TrustStatus.EXISTING_TRUSTED ->
                                "Imported ${result.count} key(s) — signature verified against pinned bridge"
                            com.cubeos.meshsat.crypto.KeyBundleImporter.TrustStatus.UNVERIFIED_V1 ->
                                "Imported ${result.count} key(s) — UNVERIFIED (legacy v1 bundle, no signature check)"
                        }
                        Toast.makeText(context, trustMsg, Toast.LENGTH_LONG).show()
                    }
                    is com.cubeos.meshsat.crypto.KeyBundleImporter.ImportResult.KeyMismatch -> {
                        Toast.makeText(
                            context,
                            "⚠ Bridge key CHANGED since last scan! Rejected. Bridge: ${result.bridgeHashHex.take(8)} — remove from Settings if this is intentional rotation.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is com.cubeos.meshsat.crypto.KeyBundleImporter.ImportResult.InvalidSignature -> {
                        Toast.makeText(
                            context,
                            "⚠ Bundle signature INVALID — possibly tampered. ${result.reason}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is com.cubeos.meshsat.crypto.KeyBundleImporter.ImportResult.Malformed -> {
                        Toast.makeText(
                            context,
                            "Bundle malformed: ${result.reason}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        } else if (scanned != null && scanned.length == 64 &&
            scanned.all { it in "0123456789abcdefABCDEF" }
        ) {
            // Legacy: raw 64-char hex key
            keyInput = scanned
            scope.launch { settings.setEncryptionKey(scanned) }
            Toast.makeText(context, "Key imported via QR", Toast.LENGTH_SHORT).show()
        } else if (scanned != null) {
            Toast.makeText(context, "QR code doesn't contain a valid key or bundle", Toast.LENGTH_LONG).show()
        }
    }

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

        // --- Iridium RockBLOCK 9704 (JSPR/IMT) Section ---
        SectionCard("Iridium RockBLOCK 9704 (IMT)") {
            val state9704 = iridium9704State?.value ?: com.cubeos.meshsat.bt.Iridium9704Spp.State.Disconnected
            ConnectionStatusRow(
                label = "Status",
                connected = state9704 == com.cubeos.meshsat.bt.Iridium9704Spp.State.Ready,
                statusText = when (state9704) {
                    com.cubeos.meshsat.bt.Iridium9704Spp.State.Ready -> {
                        val sig = iridium9704Signal?.value ?: 0
                        "Ready (Signal: $sig/5)"
                    }
                    com.cubeos.meshsat.bt.Iridium9704Spp.State.Initializing -> "Initializing JSPR..."
                    com.cubeos.meshsat.bt.Iridium9704Spp.State.Connected -> "Connected (init pending)"
                    com.cubeos.meshsat.bt.Iridium9704Spp.State.Connecting -> "Connecting..."
                    com.cubeos.meshsat.bt.Iridium9704Spp.State.Disconnected -> "Disconnected"
                },
                color = ColorIridium,
            )

            if (state9704 == com.cubeos.meshsat.bt.Iridium9704Spp.State.Ready) {
                iridium9704ModemInfo?.value?.let { info ->
                    if (info.imei.isNotBlank()) InfoRow("IMEI", info.imei)
                    if (info.serial.isNotBlank()) InfoRow("Serial", info.serial)
                    if (info.firmwareVersion.isNotBlank()) InfoRow("Firmware", info.firmwareVersion)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val sig = GatewayService.iridium9704Spp?.pollSignal()
                                Toast.makeText(context, "9704 Signal: $sig/5", Toast.LENGTH_SHORT).show()
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
                                    .setAction(GatewayService.ACTION_DISCONNECT_IRIDIUM9704)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Disconnect", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state9704 == com.cubeos.meshsat.bt.Iridium9704Spp.State.Disconnected) {
                val paired9704 = remember {
                    GatewayService.iridium9704Spp?.getPairedDevices() ?: emptyList()
                }
                if (paired9704.isNotEmpty()) {
                    Text(
                        text = "Paired HC-05/06 devices (9704):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                    paired9704.forEach { device ->
                        @Suppress("MissingPermission")
                        DeviceRow(
                            name = device.name ?: "HC-05",
                            address = device.address,
                            onClick = {
                                context.startService(
                                    Intent(context, GatewayService::class.java)
                                        .setAction(GatewayService.ACTION_CONNECT_IRIDIUM9704)
                                        .putExtra(GatewayService.EXTRA_ADDRESS, device.address)
                                )
                            },
                        )
                    }
                } else {
                    Text(
                        text = "No paired HC-05/06 modules found for 9704.",
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

            // Share / Import / QR
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

            // QR code scan for Hub key sync (MESHSAT-205)
            Button(
                onClick = {
                    try {
                        val scanIntent = com.journeyapps.barcodescanner.ScanContract().createIntent(
                            context,
                            com.journeyapps.barcodescanner.ScanOptions().apply {
                                setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                                setPrompt("Scan Hub encryption key QR code")
                                setBeepEnabled(false)
                                setOrientationLocked(true)
                            },
                        )
                        qrScanLauncher.launch(scanIntent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "QR scanner not available: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan QR Code (Hub Key Sync)", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Fallback key — used when no per-conversation key is set. " +
                        "To sync with Hub: go to Hub dashboard > Devices > select device > Generate Key, " +
                        "then scan the QR code or paste the 64-char hex key.",
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
        SectionCard("APRS") {
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

            // Mode selector: KISS TNC or APRS-IS direct
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = aprsMode == "kiss",
                    onClick = { scope.launch { settings.setAprsMode("kiss") } },
                    label = { Text("KISS TNC", style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                    ),
                )
                FilterChip(
                    selected = aprsMode == "is",
                    onClick = { scope.launch { settings.setAprsMode("is") } },
                    label = { Text("APRS-IS Direct", style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                    ),
                )
            }

            // Connection status (mode-aware)
            if (aprsMode == "kiss") {
                aprsKissState?.let { state ->
                    val statusText = when (state.value) {
                        com.cubeos.meshsat.aprs.KissClient.State.Connected -> "Connected"
                        com.cubeos.meshsat.aprs.KissClient.State.Connecting -> "Connecting..."
                        com.cubeos.meshsat.aprs.KissClient.State.Error -> "Error"
                        com.cubeos.meshsat.aprs.KissClient.State.Disconnected -> "Disconnected"
                    }
                    val isOnline = state.value == com.cubeos.meshsat.aprs.KissClient.State.Connected
                    ConnectionStatusRow("KISS TNC", isOnline, statusText, MeshSatTeal)
                }
            } else {
                aprsIsState?.let { state ->
                    val statusText = when (state.value) {
                        com.cubeos.meshsat.aprs.AprsIsClient.State.Connected -> "Connected"
                        com.cubeos.meshsat.aprs.AprsIsClient.State.Connecting -> "Connecting..."
                        com.cubeos.meshsat.aprs.AprsIsClient.State.Error -> "Error"
                        com.cubeos.meshsat.aprs.AprsIsClient.State.Disconnected -> "Disconnected"
                    }
                    val isOnline = state.value == com.cubeos.meshsat.aprs.AprsIsClient.State.Connected
                    val verified = GatewayService.aprsIsClient?.verified == true
                    val label = if (isOnline && verified) "APRS-IS (verified)" else "APRS-IS"
                    ConnectionStatusRow(label, isOnline, statusText, MeshSatTeal)
                }
            }

            // Common: callsign + SSID
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

            // KISS TNC settings
            if (aprsMode == "kiss") {
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
            }

            // APRS-IS Direct settings (MESHSAT-230)
            if (aprsMode == "is") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = aprsIsServerInput,
                        onValueChange = { aprsIsServerInput = it },
                        label = { Text("APRS-IS Server", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MeshSatTeal,
                            unfocusedBorderColor = MeshSatBorder,
                        ),
                    )
                    OutlinedTextField(
                        value = aprsIsPortInput,
                        onValueChange = { aprsIsPortInput = it.filter { c -> c.isDigit() }.take(5) },
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

                // Passcode with auto-calculate button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = aprsIsPasscodeInput,
                        onValueChange = { aprsIsPasscodeInput = it.filter { c -> c.isDigit() || c == '-' }.take(6) },
                        label = { Text("Passcode", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MeshSatTeal,
                            unfocusedBorderColor = MeshSatBorder,
                        ),
                    )
                    OutlinedButton(
                        onClick = {
                            if (aprsCallsignInput.isNotBlank()) {
                                aprsIsPasscodeInput = com.cubeos.meshsat.aprs.AprsIsPasscode.calculate(aprsCallsignInput)
                            }
                        },
                        border = BorderStroke(1.dp, MeshSatTeal),
                    ) {
                        Text("Auto", style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = aprsIsFilterRangeInput,
                    onValueChange = { aprsIsFilterRangeInput = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Filter radius (km)", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )

                // Position beaconing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Position beacon", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = aprsIsBeaconEnabled,
                        onCheckedChange = { scope.launch { settings.setAprsIsBeaconEnabled(it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }

                if (aprsIsBeaconEnabled) {
                    OutlinedTextField(
                        value = aprsIsBeaconIntervalInput,
                        onValueChange = { aprsIsBeaconIntervalInput = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Beacon interval (min)", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MeshSatTeal,
                            unfocusedBorderColor = MeshSatBorder,
                        ),
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        settings.setAprsCallsign(aprsCallsignInput)
                        settings.setAprsSsid(aprsSsidInput)
                        if (aprsMode == "kiss") {
                            settings.setAprsKissHost(aprsHostInput)
                            settings.setAprsKissPort(aprsPortInput)
                            settings.setAprsFrequency(aprsFreqInput)
                        } else {
                            settings.setAprsIsServer(aprsIsServerInput)
                            settings.setAprsIsPort(aprsIsPortInput)
                            settings.setAprsIsPasscode(aprsIsPasscodeInput)
                            settings.setAprsIsFilterRange(aprsIsFilterRangeInput)
                            settings.setAprsIsBeaconInterval(aprsIsBeaconIntervalInput)
                        }
                    }
                    Toast.makeText(context, "APRS settings saved", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Save", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = if (aprsMode == "kiss") {
                    "Connect to APRSDroid's KISS TCP server for local RF APRS via AIOC + handheld radio. " +
                        "SSID 7 = handheld, 10 = igate. EU: 144.800 MHz, NA: 144.390 MHz."
                } else {
                    "Connect directly to APRS-IS (rotate.aprs2.net) over the internet. " +
                        "No APRSDroid or radio needed. Use passcode -1 for receive-only, or Auto to calculate from callsign. " +
                        "Position beacon sends GPS location at the configured interval."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- TAK / CoT Section (MESHSAT-451) ---
        SectionCard("TAK / CoT") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enable TAK", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = takEnabled,
                    onCheckedChange = { scope.launch { settings.setTakEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            OutlinedTextField(
                value = takCallsignPrefixInput,
                onValueChange = { takCallsignPrefixInput = it.uppercase().take(10) },
                label = { Text("Callsign Prefix", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ATAK Broadcast", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = takAtakBroadcast,
                    onCheckedChange = { scope.launch { settings.setTakAtakBroadcast(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("MQTT Export to Hub", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = takMqttExport,
                    onCheckedChange = { scope.launch { settings.setTakMqttExport(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        settings.setTakCallsignPrefix(takCallsignPrefixInput)
                    }
                    GatewayService.takIntegration?.updateOutputFlags(takAtakBroadcast, takMqttExport)
                    android.widget.Toast.makeText(context, "TAK settings saved", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Save", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Generates CoT (Cursor on Target) events for positions, SOS, telemetry, and chat. " +
                    "ATAK Broadcast sends locally to ATAK if installed. MQTT Export sends to Hub for relay to a TAK server. " +
                    "Callsign format: PREFIX-XXXX (last 4 hex of device ID). Restart service after changing prefix.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- Reticulum TCP Section (MESHSAT-268) ---
        SectionCard("Reticulum TCP") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enable RNS TCP", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = rnsTcpEnabled,
                    onCheckedChange = { scope.launch { settings.setRnsTcpEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            // Connection status
            rnsTcpState?.let { state ->
                val statusText = when (state.value) {
                    com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Connected -> "Connected"
                    com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Connecting -> "Connecting..."
                    com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Error -> "Error"
                    com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Disconnected -> "Disconnected"
                }
                val isOnline = state.value == com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Connected
                ConnectionStatusRow("RNS TCP", isOnline, statusText, MeshSatTeal)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rnsTcpHostInput,
                    onValueChange = { rnsTcpHostInput = it },
                    label = { Text("Host", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )
                OutlinedTextField(
                    value = rnsTcpPortInput,
                    onValueChange = { rnsTcpPortInput = it.filter { c -> c.isDigit() }.take(5) },
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TLS", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = rnsTcpTls,
                    onCheckedChange = { scope.launch { settings.setRnsTcpTls(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        settings.setRnsTcpHost(rnsTcpHostInput)
                        settings.setRnsTcpPort(rnsTcpPortInput)
                    }
                    Toast.makeText(context, "RNS TCP settings saved", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Save", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Connect to a stock Reticulum (Python RNS) node over TCP/IP. " +
                    "Enable TLS for public endpoints (e.g. port 443 via HAProxy/stunnel). " +
                    "Default port 4242. Uses HDLC framing for wire compatibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // Provision confirmation dialog
        if (showProvisionDialog && provisionBundle != null) {
            val bundle = provisionBundle!!
            AlertDialog(
                onDismissRequest = { showProvisionDialog = false },
                title = { Text("Provision Hub Connection") },
                text = {
                    Column {
                        Text(
                            "Provision Hub connection for bridge \"${bundle.bridgeId}\"?\n\n" +
                                "This will overwrite existing Hub settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Hub: ${bundle.mqttUrl}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        if (bundle.certExpiry.isNotBlank()) {
                            Text("Cert expires: ${bundle.certExpiry}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        }
                        if (bundle.reticulumTcp.isNotBlank()) {
                            Text("Reticulum: ${bundle.reticulumTcp}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showProvisionDialog = false
                            scope.launch {
                                try {
                                    val msg = com.cubeos.meshsat.crypto.ProvisionImporter.apply(bundle, context)
                                    // Update ALL local UI state vars immediately
                                    hubUrlInput = bundle.mqttUrl
                                    hubBridgeIdInput = bundle.bridgeId
                                    hubUsernameInput = bundle.username
                                    hubPasswordInput = bundle.password
                                    hubCallsignInput = "" // reset callsign
                                    hubHealthIntervalInput = "30"
                                    Toast.makeText(context, "$msg — restart app to connect", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Provision failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    ) { Text("Provision") }
                },
                dismissButton = {
                    TextButton(onClick = { showProvisionDialog = false }) { Text("Cancel") }
                },
            )
        }

        SectionCard("Hub Reporter") {
            // --- Health LED + Status ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val hubState = hubReporterState?.value
                    val ledColor = when (hubState) {
                        com.cubeos.meshsat.hub.HubReporter.State.Connected -> MeshSatGreen
                        com.cubeos.meshsat.hub.HubReporter.State.Connecting -> MeshSatAmber
                        com.cubeos.meshsat.hub.HubReporter.State.Error -> MeshSatRed
                        else -> MeshSatTextMuted
                    }
                    val statusLabel = when (hubState) {
                        com.cubeos.meshsat.hub.HubReporter.State.Connected -> "Connected"
                        com.cubeos.meshsat.hub.HubReporter.State.Connecting -> "Connecting..."
                        com.cubeos.meshsat.hub.HubReporter.State.Error -> "Error"
                        com.cubeos.meshsat.hub.HubReporter.State.Disconnected -> "Disconnected"
                        else -> "Disabled"
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(ledColor, CircleShape),
                    )
                    Text(statusLabel, style = MaterialTheme.typography.bodyMedium, color = ledColor)
                }
                Switch(
                    checked = hubEnabled,
                    onCheckedChange = { scope.launch { settings.setHubEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            // --- Ping Button ---
            var pingResult by remember { mutableStateOf("") }
            var pinging by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val hub = GatewayService.hubReporter
                        if (hub == null || !hub.isConnected) {
                            pingResult = "Not connected"
                            return@OutlinedButton
                        }
                        pinging = true
                        pingResult = "..."
                        scope.launch {
                            try {
                                val elapsed = withContext(Dispatchers.IO) { hub.ping() }
                                pingResult = "${elapsed}ms"
                            } catch (e: Exception) {
                                pingResult = "failed: ${e.message?.take(30)}"
                            }
                            pinging = false
                        }
                    },
                    enabled = !pinging,
                ) {
                    Text("Ping Hub")
                }
                Text(
                    text = if (pingResult.isNotBlank()) pingResult else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pingResult.endsWith("ms")) MeshSatGreen else MeshSatTextMuted,
                )
            }

            // --- Scan Provision QR ---
            OutlinedButton(
                onClick = {
                    val scanIntent = com.journeyapps.barcodescanner.ScanContract().createIntent(
                        context,
                        com.journeyapps.barcodescanner.ScanOptions().apply {
                            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                            setPrompt("Scan Hub Provision QR code")
                            setBeepEnabled(false)
                            setOrientationLocked(true)
                        },
                    )
                    qrScanLauncher.launch(scanIntent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan Hub Provision QR")
            }

            // --- Fields ---
            OutlinedTextField(
                value = hubUrlInput,
                onValueChange = { hubUrlInput = it },
                label = { Text("Hub MQTT URL", style = MaterialTheme.typography.bodySmall) },
                placeholder = { Text("wss://mqtt-hub.meshsat.net/mqtt", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hubBridgeIdInput,
                    onValueChange = { hubBridgeIdInput = it },
                    label = { Text("Bridge ID", style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text("auto (Android ID)", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) },
                    singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                )
                OutlinedTextField(
                    value = hubCallsignInput,
                    onValueChange = { hubCallsignInput = it },
                    label = { Text("Callsign", style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text("TAK callsign", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) },
                    singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hubUsernameInput,
                    onValueChange = { hubUsernameInput = it },
                    label = { Text("Username", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                )
                OutlinedTextField(
                    value = hubPasswordInput,
                    onValueChange = { hubPasswordInput = it },
                    label = { Text("Password", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    visualTransformation = if (showHubPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                )
            }

            OutlinedTextField(
                value = hubHealthIntervalInput,
                onValueChange = { hubHealthIntervalInput = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Health interval (seconds)", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
            )

            Button(
                onClick = {
                    scope.launch {
                        settings.setHubUrl(hubUrlInput)
                        settings.setHubBridgeId(hubBridgeIdInput)
                        settings.setHubCallsign(hubCallsignInput)
                        settings.setHubUsername(hubUsernameInput)
                        settings.setHubPassword(hubPasswordInput)
                        settings.setHubHealthInterval(hubHealthIntervalInput)
                    }
                    Toast.makeText(context, "Hub Reporter settings saved (restart to apply)", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Save", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Connect to MeshSat Hub as a mobile field node. " +
                    "Publishes birth/health/position to the fleet dashboard and TAK map. " +
                    "Leave Bridge ID blank to use Android device ID.",
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

        // --- Offline Maps ---
        SectionCard("Offline Maps") {
            var offlineEnabled by remember { mutableStateOf(false) }
            var offlineFile by remember { mutableStateOf("") }
            var mapFiles by remember { mutableStateOf<List<MBTilesManager.MBTilesInfo>>(emptyList()) }
            var importing by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                offlineEnabled = settings.offlineMapEnabled.first()
                offlineFile = settings.offlineMapFile.first()
                mapFiles = withContext(Dispatchers.IO) { MBTilesManager.listFiles(context) }
            }

            val mbtilesPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    try {
                        importing = true
                        val filename = withContext(Dispatchers.IO) {
                            MBTilesManager.import(context, uri)
                        }
                        settings.setOfflineMapFile(filename)
                        settings.setOfflineMapEnabled(true)
                        offlineFile = filename
                        offlineEnabled = true
                        mapFiles = withContext(Dispatchers.IO) { MBTilesManager.listFiles(context) }
                        Toast.makeText(context, "Map imported: $filename", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        importing = false
                    }
                }
            }

            // Enable/disable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use Offline Tiles", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Serve map tiles from imported MBTiles file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
                Switch(
                    checked = offlineEnabled,
                    onCheckedChange = { enabled ->
                        offlineEnabled = enabled
                        scope.launch { settings.setOfflineMapEnabled(enabled) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Import button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        mbtilesPickerLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*"))
                    },
                    enabled = !importing,
                ) {
                    Text("Import MBTiles")
                }
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Importing...", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                }
            }

            // Imported map list
            if (mapFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                mapFiles.forEach { info ->
                    val isActive = info.filename == offlineFile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isActive) MeshSatTeal.copy(alpha = 0.1f) else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .border(
                                1.dp,
                                if (isActive) MeshSatTeal.copy(alpha = 0.4f) else MeshSatBorder,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable {
                                offlineFile = info.filename
                                offlineEnabled = true
                                scope.launch {
                                    settings.setOfflineMapFile(info.filename)
                                    settings.setOfflineMapEnabled(true)
                                }
                            }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = info.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isActive) MeshSatTeal else Color.Unspecified,
                            )
                            val sizeMb = "%.1f MB".format(info.sizeBytes / 1_048_576.0)
                            val zoomRange = when {
                                info.minZoom != null && info.maxZoom != null -> "z${info.minZoom}-${info.maxZoom}"
                                else -> ""
                            }
                            Text(
                                text = listOf(sizeMb, info.format.uppercase(), zoomRange)
                                    .filter { it.isNotBlank() }.joinToString(" \u00B7 "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshSatTextMuted,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { MBTilesManager.delete(context, info.filename) }
                                mapFiles = withContext(Dispatchers.IO) { MBTilesManager.listFiles(context) }
                                if (offlineFile == info.filename) {
                                    offlineFile = ""
                                    offlineEnabled = false
                                    settings.setOfflineMapFile("")
                                    settings.setOfflineMapEnabled(false)
                                }
                                Toast.makeText(context, "Deleted: ${info.name}", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MeshSatTextMuted)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            } else if (!importing) {
                Text(
                    "No offline maps imported. Use MBTiles files from OpenStreetMap or OpenMapTiles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // --- Telemetry (MESHSAT-494) ---
        SectionCard("Release Telemetry") {
            val telemetryEnabled by settings.telemetryEnabled.collectAsState(initial = true)

            SettingRow("Enable local telemetry") {
                Switch(
                    checked = telemetryEnabled,
                    onCheckedChange = { scope.launch { settings.setTelemetryEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            Text(
                "Captures crashes, heap samples, and health heartbeats locally on this device. " +
                    "Nothing is sent externally. Retrievable via localhost:6051/api/telemetry.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- Service ---
        SectionCard("Service") {
            var showRestartDialog by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Restart Gateway Service", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Stop and restart all transports",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
                OutlinedButton(
                    onClick = { showRestartDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE57373)),
                ) {
                    Text("Restart")
                }
            }

            if (showRestartDialog) {
                AlertDialog(
                    onDismissRequest = { showRestartDialog = false },
                    title = { Text("Restart Service?") },
                    text = {
                        Text(
                            "This will disconnect all transports and restart the gateway service. " +
                                "It should take a few seconds.",
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showRestartDialog = false
                                GatewayService.scheduleRestart(context)
                                Toast.makeText(context, "Service restarting...", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                        ) {
                            Text("Restart")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestartDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
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
