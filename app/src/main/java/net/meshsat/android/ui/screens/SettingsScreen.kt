package net.meshsat.android.ui.screens

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
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import net.meshsat.android.ui.components.goToSettingsText
import net.meshsat.android.ui.components.rememberPermissionAsk
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.IridiumPipeContract
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.map.MBTilesManager
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.theme.OffWhite
import net.meshsat.android.ui.theme.MeshSatInk
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.ColorCellular
import net.meshsat.android.codec.CannedCodebook
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import net.meshsat.android.ui.components.collectOrNull

/**
 * The pages of Setup (MESHSAT-1249). Settings used to be one scroll of 18 cards; each page now shows
 * only its own cards from the same code, so nothing a card does has changed.
 */
enum class SetupSection(val title: String) {
    Node("Your MeshSat node"),
    Satellite("Satellite"),
    Hub("Hub"),
    Sms("SMS"),
    Safety("Safety"),
    Messaging("Messaging"),
    Maps("Maps"),
    Integrations("Ham radio, TAK and Reticulum"),
    Diagnostics("Diagnostics"),
    All("All settings"),
    ;

    fun shows(card: SetupSection): Boolean = this == All || this == card

    companion object {
        fun fromRoute(name: String?): SetupSection = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: All
    }
}

@Composable
fun SettingsScreen(navController: NavController? = null, section: SetupSection = SetupSection.All) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val encryptionKey by settings.encryptionKey.collectAsState(initial = "")
    val encryptionEnabled by settings.encryptionEnabled.collectAsState(initial = false)
    val autoDecrypt by settings.autoDecryptSms.collectAsState(initial = true)
    val piPhone by settings.meshsatPiPhone.collectAsState(initial = "")
    val msvqscEnabled by settings.msvqscEnabled.collectAsState(initial = false)
    val msvqscStages by settings.msvqscStages.collectAsState(initial = "3")
    val compressIridium by settings.compressIridium.collectAsState(initial = "off")
    val compressSms by settings.compressSms.collectAsState(initial = "off")
    val compressMqtt by settings.compressMqtt.collectAsState(initial = "off")

    val deadmanEnabled by settings.deadmanEnabled.collectAsState(initial = false)
    val deadmanTimeoutMin by settings.deadmanTimeoutMin.collectAsState(initial = "120")

    // APRS settings
    val aprsEnabled by settings.aprsEnabled.collectAsState(initial = false)
    val aprsCallsign by settings.aprsCallsign.collectAsState(initial = "")
    val aprsSsid by settings.aprsSsid.collectAsState(initial = "10")
    val aprsKissHost by settings.aprsKissHost.collectAsState(initial = "localhost")
    val aprsKissPort by settings.aprsKissPort.collectAsState(initial = "8001")
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
    // Reticulum as the LXMF clients speak it (MESHSAT-1553)
    val lxmfDisplayName by settings.lxmfDisplayName.collectAsState(initial = "")
    val rnsPaidDests by settings.rnsPaidDests.collectAsState(initial = "")
    val rnsPaidExtraHops by settings.rnsPaidExtraHops.collectAsState(initial = "20")
    val lxmfPropagationNode by settings.lxmfPropagationNode.collectAsState(initial = "")
    val rnsAutoEnabled by settings.rnsAutoEnabled.collectAsState(initial = false)
    val rnodeEnabled by settings.rnodeEnabled.collectAsState(initial = false)
    val rnodePort by settings.rnodePort.collectAsState(initial = "usb")
    val rnodePreset by settings.rnodePreset.collectAsState(initial = "eu-868")
    val rnodeCustomParams by settings.rnodeCustomParams.collectAsState(initial = "867200000,125000,14,8,5")
    val rnodeAirtimeLimits by settings.rnodeAirtimeLimits.collectAsState(initial = "0,0")
    var rnodePortInput by remember(rnodePort) { mutableStateOf(rnodePort) }
    var rnodePresetInput by remember(rnodePreset) { mutableStateOf(rnodePreset) }
    var rnodeCustomInput by remember(rnodeCustomParams) { mutableStateOf(rnodeCustomParams) }
    var rnodeAirtimeInput by remember(rnodeAirtimeLimits) { mutableStateOf(rnodeAirtimeLimits) }
    val rnodeScanResults = remember { mutableStateListOf<net.meshsat.android.rnode.RnodeBleScanner.Found>() }
    var rnodeScanning by remember { mutableStateOf(false) }
    val lxmfSyncIntervalMin by settings.lxmfSyncIntervalMin.collectAsState(initial = "0")
    val rnsTcpListenEnabled by settings.rnsTcpListenEnabled.collectAsState(initial = false)
    val rnsTcpListenPort by settings.rnsTcpListenPort.collectAsState(initial = "4242")

    // Hub Reporter settings (MESHSAT-292)
    val hubEnabled by settings.hubEnabled.collectAsState(initial = false)
    val hubUrl by settings.hubUrl.collectAsState(initial = "")
    val hubBridgeId by settings.hubBridgeId.collectAsState(initial = "")
    val hubCallsign by settings.hubCallsign.collectAsState(initial = "")
    val hubUsername by settings.hubUsername.collectAsState(initial = "")
    val hubPassword by settings.hubPassword.collectAsState(initial = "")
    val hubHealthInterval by settings.hubHealthInterval.collectAsState(initial = "30")
    // Texts to other MeshSat apps through the Hub (hub_msg, MESHSAT-1584)
    val hubMsgEnabled by settings.hubMsgEnabled.collectAsState(initial = false)
    // Hub relay client (MESHSAT-1157)
    val hubRelayEnabled by settings.hubRelayEnabled.collectAsState(initial = true)
    val hubRelayTarget by settings.hubRelayTarget.collectAsState(initial = "")
    val hubRelayUrl by settings.hubRelayUrl.collectAsState(initial = "")

    var keyInput by remember(encryptionKey) { mutableStateOf(encryptionKey) }
    var phoneInput by remember(piPhone) { mutableStateOf(piPhone) }
    var showKey by remember { mutableStateOf(false) }

    // APRS state
    var aprsCallsignInput by remember(aprsCallsign) { mutableStateOf(aprsCallsign) }
    var aprsSsidInput by remember(aprsSsid) { mutableStateOf(aprsSsid) }
    var aprsHostInput by remember(aprsKissHost) { mutableStateOf(aprsKissHost) }
    var aprsPortInput by remember(aprsKissPort) { mutableStateOf(aprsKissPort) }
    val aprsKissState = GatewayService.kissClient?.state.collectOrNull()
    // APRS-IS state (MESHSAT-230)
    var aprsIsServerInput by remember(aprsIsServer) { mutableStateOf(aprsIsServer) }
    var aprsIsPortInput by remember(aprsIsPort) { mutableStateOf(aprsIsPort) }
    var aprsIsPasscodeInput by remember(aprsIsPasscode) { mutableStateOf(aprsIsPasscode) }
    var aprsIsFilterRangeInput by remember(aprsIsFilterRange) { mutableStateOf(aprsIsFilterRange) }
    var aprsIsBeaconIntervalInput by remember(aprsIsBeaconInterval) { mutableStateOf(aprsIsBeaconInterval) }
    val aprsIsState = GatewayService.aprsIsClient?.state.collectOrNull()

    // TAK state (MESHSAT-451)
    var takCallsignPrefixInput by remember(takCallsignPrefix) { mutableStateOf(takCallsignPrefix) }

    // RNS TCP state (MESHSAT-268)
    var rnsTcpHostInput by remember(rnsTcpHost) { mutableStateOf(rnsTcpHost) }
    var rnsTcpPortInput by remember(rnsTcpPort) { mutableStateOf(rnsTcpPort) }
    var lxmfNameInput by remember(lxmfDisplayName) { mutableStateOf(lxmfDisplayName) }
    var paidDestsInput by remember(rnsPaidDests) { mutableStateOf(rnsPaidDests) }
    var paidHopsInput by remember(rnsPaidExtraHops) { mutableStateOf(rnsPaidExtraHops) }
    var syncMinInput by remember(lxmfSyncIntervalMin) { mutableStateOf(lxmfSyncIntervalMin) }
    var pnTick by remember { mutableStateOf(0) }
    val lxmfAddress = GatewayService.rnsStack?.lxmfAddress
        ?: GatewayService.routingIdentity?.let { net.meshsat.android.rns.RnsStack.lxmfAddressOf(it) } ?: ""
    val rnsTcpState = GatewayService.rnsTcpInterface?.state.collectOrNull()

    // Hub Reporter state (MESHSAT-292)
    var hubUrlInput by remember(hubUrl) { mutableStateOf(hubUrl) }
    var hubBridgeIdInput by remember(hubBridgeId) { mutableStateOf(hubBridgeId) }
    var hubCallsignInput by remember(hubCallsign) { mutableStateOf(hubCallsign) }
    var hubUsernameInput by remember(hubUsername) { mutableStateOf(hubUsername) }
    var hubPasswordInput by remember(hubPassword) { mutableStateOf(hubPassword) }
    var hubHealthIntervalInput by remember(hubHealthInterval) { mutableStateOf(hubHealthInterval) }
    var hubRelayTargetInput by remember(hubRelayTarget) { mutableStateOf(hubRelayTarget) }
    var hubRelayUrlInput by remember(hubRelayUrl) { mutableStateOf(hubRelayUrl) }
    var showHubPassword by remember { mutableStateOf(false) }
    // A scanned key bundle whose kit key differs from the pinned one: (url, kit id).
    var keyMismatch by remember { mutableStateOf<Pair<String, String>?>(null) }
    keyMismatch?.let { (url, kit) ->
        AlertDialog(
            onDismissRequest = { keyMismatch = null },
            containerColor = MeshSatSurface,
            title = { Text("This kit's key has changed") },
            text = {
                Text(
                    "Kit ${kit.take(8)} signed these keys with a different key from the one this phone saved " +
                        "the first time. That is expected after the kit was reinstalled or its key was renewed. " +
                        "It is also what an impostor looks like. Trust the new key only if you know the kit's key changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    keyMismatch = null
                    scope.launch {
                        val r = net.meshsat.android.crypto.KeyBundleImporter.importFromURLForceRepin(url, context)
                        GatewayService.signingServiceRef?.auditEvent(
                            eventType = "bridge_key_repinned",
                            detail = "kit=${kit.take(16)} result=${r::class.simpleName}",
                        )
                        val msg = if (r is net.meshsat.android.crypto.KeyBundleImporter.ImportResult.Success) {
                            "New kit key saved, ${r.count} key(s) imported"
                        } else {
                            "Not imported: $r"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Trust the new key", color = MeshSatRed) }
            },
            dismissButton = {
                TextButton(onClick = { keyMismatch = null }) { Text("Keep the old key") }
            },
        )
    }

    // QR provisioning state
    val hubReporterNow by GatewayService.hubReporterNow.collectAsState()
    val hubReporterState = hubReporterNow?.state.collectOrNull()

    // BLE state
    val meshState = GatewayService.meshtasticBle?.state.collectOrNull()
    val iridiumState = GatewayService.iridiumSpp?.state.collectOrNull()
    val iridiumSignal = GatewayService.iridiumSpp?.signal.collectOrNull()
    val iridiumSilent = GatewayService.iridiumSpp?.modemSilent.collectOrNull()
    val modemInfo = GatewayService.iridiumSpp?.modemInfo.collectOrNull()
    // The 9603 lives on the MeshSat node, behind its BLE pipe (MESHSAT-1236).
    val iridiumPipe by (GatewayService.meshtasticBle?.iridiumPipe ?: MutableStateFlow(null)).collectAsState()
    val pipeOwner by remember(iridiumPipe) {
        iridiumPipe?.owner ?: MutableStateFlow(IridiumPipeContract.Owner.Unknown)
    }.collectAsState()
    val nodePipeEnabled by settings.iridiumNodePipeEnabled.collectAsState(initial = true)

    // Iridium 9704 state
    val iridium9704State = GatewayService.iridium9704Spp?.state.collectOrNull()
    val iridium9704ModemInfo = GatewayService.iridium9704Spp?.modemInfo.collectOrNull()
    // A reading carries its time; the screen marks it stale as it ages (MESHSAT-1670).
    val iridium9704Reading = GatewayService.iridium9704Spp?.reading.collectOrNull()
    val iridium9704PollStats = GatewayService.iridium9704Spp?.pollStats.collectOrNull()
    val iridium9704Segment = GatewayService.iridium9704Spp?.segmentTiming.collectOrNull()
    val signalNow by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            value = System.currentTimeMillis()
        }
    }

    // QR code scanner for key sync — handles both raw hex keys and meshsat://key/ URL bundles
    val qrScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanResult = com.journeyapps.barcodescanner.ScanIntentResult.parseActivityResult(
            result.resultCode, result.data
        )
        val scanned = scanResult.contents
        if (scanned != null && net.meshsat.android.crypto.ProvisionImporter.isProvisionUrl(scanned)) {
            // Hub provisioning QR: claimed by the app, not by this screen, so leaving the screen
            // does not drop it, and ProvisionClaimHost shows the wait (MESHSAT-1306).
            net.meshsat.android.crypto.ProvisionClaim.fromQr(scanned)
        } else if (scanned != null && scanned.startsWith("meshsat://key/")) {
            // MeshSat key bundle URL — contains signed multi-channel key bundle (MESHSAT-495)
            scope.launch {
                val result = net.meshsat.android.crypto.KeyBundleImporter.importFromURL(
                    scanned, context
                )
                when (result) {
                    is net.meshsat.android.crypto.KeyBundleImporter.ImportResult.Success -> {
                        val trustMsg = when (result.status) {
                            net.meshsat.android.crypto.KeyBundleImporter.TrustStatus.NEW_TRUSTED ->
                                "Imported ${result.count} key(s) — new bridge ${result.bridgeHashHex.take(8)} pinned"
                            net.meshsat.android.crypto.KeyBundleImporter.TrustStatus.EXISTING_TRUSTED ->
                                "Imported ${result.count} key(s) — signature verified against pinned bridge"
                            net.meshsat.android.crypto.KeyBundleImporter.TrustStatus.UNVERIFIED_V1 ->
                                "Imported ${result.count} key(s) — UNVERIFIED (legacy v1 bundle, no signature check)"
                        }
                        Toast.makeText(context, trustMsg, Toast.LENGTH_LONG).show()
                    }
                    is net.meshsat.android.crypto.KeyBundleImporter.ImportResult.KeyMismatch -> {
                        // The advice used to be "remove from Settings", with nothing there to remove it:
                        // ask instead, and re-pin only on an explicit yes (MESHSAT-1249).
                        keyMismatch = scanned to result.bridgeHashHex
                    }
                    is net.meshsat.android.crypto.KeyBundleImporter.ImportResult.InvalidSignature -> {
                        Toast.makeText(
                            context,
                            "⚠ Bundle signature INVALID — possibly tampered. ${result.reason}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is net.meshsat.android.crypto.KeyBundleImporter.ImportResult.Malformed -> {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // --- Meshtastic BLE Section ---
        if (section.shows(SetupSection.Node)) {
            SectionCard("Bluetooth connection") {
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

                // Collected every time, never inside a branch or a ?. chain (MESHSAT-1249).
                val nodeBattery by GatewayService.nodeBattery.collectAsState()
                if (state == MeshtasticBle.State.Connected) {
                    // Show device info when connected
                    val myInfo = GatewayService.meshtasticBle?.myInfo.collectOrNull()
                    val meshNodes = GatewayService.meshtasticBle?.nodes.collectOrNull()

                    myInfo?.value?.let { info ->
                        if (info.firmwareVersion.isNotBlank()) {
                            InfoRow("Firmware", info.firmwareVersion)
                        }
                        InfoRow("Node ID", "!%08x".format(info.myNodeNum))
                        // The node's own battery (MESHSAT-1315)
                        nodeBattery?.takeIf { it.nodeNum == info.myNodeNum }?.let { b ->
                            net.meshsat.android.ble.NodeBattery.describe(b.level, b.voltage, b.hoursLeft)
                                ?.let { InfoRow("Battery", it) }
                        }
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
        }

        // --- Iridium 9603 on the MeshSat node (MESHSAT-1236) ---
        if (section.shows(SetupSection.Satellite)) {
            SectionCard("Satellite modem on the node") {
                val state = iridiumState?.value ?: IridiumSpp.State.Disconnected
                // The pipe is transparent, so the phone is told which RockBLOCK the node carries (MESHSAT-1507).
                val nodeModem by settings.nodeModem.collectAsState(initial = SettingsRepository.NODE_MODEM_AUTO)
                val pipeStatus = iridiumPipe?.status.collectOrNull()
                val nodeSaysJspr = pipeStatus?.value?.flags?.modemIsJspr == true
                val node9704 = when (nodeModem) {
                    SettingsRepository.NODE_MODEM_9704 -> true
                    SettingsRepository.NODE_MODEM_9603 -> false
                    else -> nodeSaysJspr
                }
                val state9704 = iridium9704State?.value ?: net.meshsat.android.bt.Iridium9704Spp.State.Disconnected
                val pipeWords = when {
                    !nodePipeEnabled -> "Off: the node keeps its modem"
                    iridiumPipe == null -> "No MeshSat node connected"
                    pipeOwner == IridiumPipeContract.Owner.Node -> "The node is using its modem"
                    else -> "Waiting for the node"
                }
                ConnectionStatusRow(
                    label = "Status",
                    connected = if (node9704) state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Ready else state == IridiumSpp.State.Connected,
                    statusText = when {
                        node9704 && state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Ready -> "Connected (9704)"
                        node9704 && state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Initializing -> "Bringing the 9704 up..."
                        node9704 && state9704 != net.meshsat.android.bt.Iridium9704Spp.State.Disconnected -> "Checking the modem..."
                        node9704 -> pipeWords
                        state == IridiumSpp.State.Connected -> "Connected (Signal: ${iridiumSignal?.value ?: 0}/5)"
                        state == IridiumSpp.State.Connecting && iridiumSilent?.value == true ->
                            "The node's modem does not answer (still trying)"
                        state == IridiumSpp.State.Connecting -> "Checking the modem..."
                        else -> pipeWords
                    },
                    color = ColorIridium,
                )
                if (node9704 && state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Ready) {
                    SignalReadingRow(iridium9704Reading?.value, signalNow)
                    Imt9704PipeTimings(iridium9704PollStats?.value, iridium9704Segment?.value)
                }

                SettingRow("Modem on the node") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = nodeModem == SettingsRepository.NODE_MODEM_AUTO,
                            onClick = { scope.launch { settings.setNodeModem(SettingsRepository.NODE_MODEM_AUTO) } },
                            label = { Text("Auto", style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                            ),
                        )
                        FilterChip(
                            selected = nodeModem == SettingsRepository.NODE_MODEM_9603,
                            onClick = { scope.launch { settings.setNodeModem(SettingsRepository.NODE_MODEM_9603) } },
                            label = { Text("9603", style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                            ),
                        )
                        FilterChip(
                            selected = nodeModem == SettingsRepository.NODE_MODEM_9704,
                            onClick = { scope.launch { settings.setNodeModem(SettingsRepository.NODE_MODEM_9704) } },
                            label = { Text("9704", style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                            ),
                        )
                    }
                }

                if (iridiumPipe != null) {
                    InfoRow("The node says", if (pipeStatus?.value?.flags == null) "nothing yet" else if (nodeSaysJspr) "RockBLOCK 9704 (JSPR)" else "RockBLOCK 9603 (AT)")
                }
                if (node9704) {
                    // The kits' encrypted satellite chain (MESHSAT-1623): what the 9704 sends, and
                    // what this phone tells the Hub, which keeps an encrypted modem's messages opaque.
                    val chain by settings.satelliteChain.collectAsState(initial = net.meshsat.android.engine.SatelliteChain.MODE_UNDECLARED)
                    SettingRow("Satellite chain") {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                net.meshsat.android.engine.SatelliteChain.MODE_UNDECLARED to "Not set",
                                net.meshsat.android.engine.SatelliteChain.MODE_PLAIN to "Plain",
                                net.meshsat.android.engine.SatelliteChain.MODE_SEALED to "Encrypted",
                                net.meshsat.android.engine.SatelliteChain.MODE_LEGACY to "Encrypted (old)",
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = chain == mode,
                                    onClick = { scope.launch { settings.setSatelliteChain(mode) } },
                                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                                    ),
                                )
                            }
                        }
                    }
                    val chainHint = when (chain) {
                        net.meshsat.android.engine.SatelliteChain.MODE_SEALED ->
                            "Texts go out as the kits send them: compressed and encrypted with the key from the kit's key bundle. Without that key nothing is sent."
                        net.meshsat.android.engine.SatelliteChain.MODE_LEGACY ->
                            "The kits' chain from before, for a kit that has not switched: compressed, encrypted, base64, with the same key."
                        else -> null
                    }
                    if (chainHint != null) {
                        Text(
                            chainHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The key the chain seals with, by its check value: the same check in the
                    // kit's key list means both ends hold the same key (MESHSAT-1623).
                    var satKeyCheck by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(chain) {
                        satKeyCheck = withContext(Dispatchers.IO) {
                            try {
                                val keys = net.meshsat.android.data.ConversationKeyRepository(
                                    net.meshsat.android.data.AppDatabase.getInstance(context).conversationKeyDao(),
                                    net.meshsat.android.crypto.SecureKeyStore.getInstance(context),
                                )
                                net.meshsat.android.engine.SatelliteChain.key { keys.getBySender(it)?.hexKey }
                                    ?.let { net.meshsat.android.engine.SatelliteChain.keyCheck(it) }.orEmpty()
                            } catch (e: Exception) {
                                ""
                            }
                        }
                    }
                    InfoRow(
                        "Key ${net.meshsat.android.engine.SatelliteChain.KEY_REF}",
                        when (val c = satKeyCheck) {
                            null -> "reading"
                            "" -> "none, import the kit's key bundle"
                            else -> "check $c"
                        },
                    )
                }
                if (node9704 && state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Ready) {
                    iridium9704ModemInfo?.value?.let { info ->
                        if (info.imei.isNotBlank()) InfoRow("IMEI", info.imei)
                        if (info.serial.isNotBlank()) InfoRow("Serial", info.serial)
                    }
                }

                SettingRow("Use the node's modem") {
                    Switch(
                        checked = nodePipeEnabled,
                        onCheckedChange = { scope.launch { settings.setIridiumNodePipeEnabled(it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }

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

                    Button(
                        onClick = {
                            scope.launch {
                                // A fresh reading (AT+CSQ) can take up to a minute: off the main thread.
                                val sig = withContext(Dispatchers.IO) { GatewayService.iridiumSpp?.pollSignal(fresh = true) }
                                Toast.makeText(context, "Signal: $sig/5", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Poll Signal", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    net.meshsat.android.ui.components.CheckMailboxButton()
                } else if (iridiumPipe == null) {
                    Text(
                        text = "The node's RockBLOCK is reached through your MeshSat node. Connect the node under Your MeshSat node; its modem appears here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
            }
        }

        // --- The node's own view of its modem (contract v2 STATS, MESHSAT-1378) ---
        if (section.shows(SetupSection.Satellite)) {
            val pipe = iridiumPipe
            if (pipe != null && pipe.hasStats) {
                val stats by pipe.stats.collectAsState()
                // The node notifies STATS on a change of substance, not on the clock: while the
                // card is on screen the uptime and the ages are read again every 10 s (seen on
                // the Pixel, 27 Sep 2026: "uptime 19 s" four minutes after boot).
                LaunchedEffect(pipe) {
                    while (true) {
                        kotlinx.coroutines.delay(10_000)
                        pipe.readStats()
                    }
                }
                SectionCard("Node health") {
                    val s = stats
                    if (s == null) {
                        Text(
                            text = "Waiting for the node's report.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    } else {
                        for ((label, value) in net.meshsat.android.ble.NodeStatsText.rows(s)) InfoRow(label, value)
                        net.meshsat.android.ble.NodeStatsText.warning(s)?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MeshSatAmber)
                        }
                        Text(
                            text = "What the node reports about its own modem, whoever holds it. The signal here is information, never a reason to hold a send.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
            }
            // The owner's switches for the node's light and screen (MESHSAT-1671).
            if (pipe != null) {
                val nodeStatus by pipe.status.collectAsState()
                val flags = nodeStatus?.flags
                SectionCard("Node light and screen") {
                    if (!pipe.hasControl) {
                        Text(
                            text = "This node's firmware has no switches for its light and screen. Update the node's firmware to use them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    } else {
                        SettingRow("Status LED") {
                            Switch(
                                checked = flags?.ledOff != true,
                                onCheckedChange = { on -> scope.launch { pipe.writeControl(IridiumPipeContract.CONTROL_LED, off = !on) } },
                                colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                            )
                        }
                        SettingRow("Screen") {
                            Switch(
                                checked = flags?.screenOff != true,
                                onCheckedChange = { on -> scope.launch { pipe.writeControl(IridiumPipeContract.CONTROL_SCREEN, off = !on) } },
                                colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                            )
                        }
                        Text(
                            text = "The node keeps these after a restart. A press on the node's button always brings the screen back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
            }
            // The node's firmware, updated from a file over Bluetooth (MESHSAT-1672).
            if (pipe != null) {
                val nodeInfo = GatewayService.meshtasticBle?.myInfo.collectOrNull()?.value
                val nodeMeta = GatewayService.meshtasticBle?.deviceMetadata.collectOrNull()?.value
                val updateState by net.meshsat.android.ble.NodeFirmwareUpdater.state.collectAsState()
                var picked by remember { mutableStateOf<net.meshsat.android.ble.NodeFirmwareImage?>(null) }
                var refusal by remember { mutableStateOf<String?>(null) }
                var release by remember { mutableStateOf<ReleaseCheck>(ReleaseCheck.None) }
                val nodeEnv = nodeInfo?.pioEnv.orEmpty()
                val runningVersion = nodeMeta?.firmwareVersion.orEmpty()
                // The newest release, asked only on this tap (MESHSAT-1672); the app is offline-first.
                val checkRelease: () -> Unit = {
                    picked = null
                    refusal = null
                    release = ReleaseCheck.Checking
                    scope.launch {
                        val fetcher = net.meshsat.android.ble.NodeFirmwareRelease.Fetcher()
                        val parsed = withContext(Dispatchers.IO) { fetcher.manifest() }
                        release = when (parsed) {
                            is net.meshsat.android.ble.NodeFirmwareRelease.Parsed.Refused -> ReleaseCheck.Refused(parsed.reason)
                            is net.meshsat.android.ble.NodeFirmwareRelease.Parsed.Ok ->
                                when (val pick = net.meshsat.android.ble.NodeFirmwareRelease.pick(parsed.manifest, nodeEnv)) {
                                    is net.meshsat.android.ble.NodeFirmwareRelease.Pick.Refused -> ReleaseCheck.Refused(pick.reason)
                                    is net.meshsat.android.ble.NodeFirmwareRelease.Pick.Found ->
                                        if (net.meshsat.android.ble.NodeFirmwareRelease.isCurrent(pick.manifest, runningVersion)) {
                                            ReleaseCheck.UpToDate(pick.manifest.version)
                                        } else {
                                            ReleaseCheck.Available(pick.manifest, pick.image)
                                        }
                                }
                        }
                    }
                }
                val downloadRelease: (net.meshsat.android.ble.NodeFirmwareRelease.Manifest, net.meshsat.android.ble.NodeFirmwareRelease.Image) -> Unit = { manifest, image ->
                    release = ReleaseCheck.Downloading(0f)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val bytes = net.meshsat.android.ble.NodeFirmwareRelease.Fetcher().download(manifest, image) { f ->
                                    release = ReleaseCheck.Downloading(f.coerceIn(0f, 1f))
                                }
                                net.meshsat.android.ble.NodeFirmwareRelease.verify(bytes, image)?.let { Result.failure(Exception(it)) }
                                    ?: Result.success(bytes)
                            } catch (e: java.io.IOException) {
                                Result.failure(Exception("The download failed: ${e.message ?: "no answer"}."))
                            }
                        }
                        val bytes = result.getOrNull()
                        if (bytes == null) {
                            release = ReleaseCheck.Refused(result.exceptionOrNull()?.message ?: "The download failed.")
                            return@launch
                        }
                        when (val c = net.meshsat.android.ble.NodeFirmwareImage.check(bytes, nodeEnv)) {
                            is net.meshsat.android.ble.NodeFirmwareImage.Check.Refused -> release = ReleaseCheck.Refused(c.reason)
                            is net.meshsat.android.ble.NodeFirmwareImage.Check.Ok -> {
                                release = ReleaseCheck.None
                                picked = c.image
                            }
                        }
                    }
                }
                val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            } catch (e: Exception) {
                                null
                            }
                        }
                        picked = null
                        refusal = null
                        when (val c = if (bytes == null) null else net.meshsat.android.ble.NodeFirmwareImage.check(bytes, nodeEnv)) {
                            null -> refusal = "The file could not be read."
                            is net.meshsat.android.ble.NodeFirmwareImage.Check.Refused -> refusal = c.reason
                            is net.meshsat.android.ble.NodeFirmwareImage.Check.Ok -> picked = c.image
                        }
                    }
                }
                SectionCard("Node firmware") {
                    InfoRow("Running", nodeMeta?.firmwareVersion?.ifBlank { null } ?: "unknown")
                    InfoRow("Build", nodeEnv.ifBlank { "unknown" })
                    when (val st = updateState) {
                        net.meshsat.android.ble.NodeFirmwareUpdater.State.Idle -> {
                            val image = picked
                            if (image == null) {
                                when (val r = release) {
                                    ReleaseCheck.Checking -> {
                                        Text("Asking for the newest firmware...", style = MaterialTheme.typography.bodySmall, color = OffWhite)
                                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                    is ReleaseCheck.Downloading -> {
                                        Text("Downloading the firmware: ${(r.fraction * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, color = OffWhite)
                                        androidx.compose.material3.LinearProgressIndicator(progress = { r.fraction }, modifier = Modifier.fillMaxWidth())
                                    }
                                    is ReleaseCheck.Available -> {
                                        InfoRow("Available", r.manifest.version)
                                        Button(
                                            onClick = { downloadRelease(r.manifest, r.image) },
                                            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Download ${r.manifest.version} (${r.image.size / 1024} KB)", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    is ReleaseCheck.UpToDate -> {
                                        Text("The node runs the newest firmware (${r.version}).", style = MaterialTheme.typography.bodySmall, color = MeshSatTeal)
                                    }
                                    is ReleaseCheck.Refused -> {
                                        Text(r.reason, style = MaterialTheme.typography.bodySmall, color = MeshSatAmber)
                                    }
                                    ReleaseCheck.None -> {}
                                }
                                if (release != ReleaseCheck.Checking && release !is ReleaseCheck.Downloading) {
                                    OutlinedButton(onClick = checkRelease, modifier = Modifier.fillMaxWidth()) {
                                        Text("Check for update", style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(onClick = { pickFirmware.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Update from a file...", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            } else {
                                InfoRow("New firmware", image.version.ifBlank { "unnamed" })
                                InfoRow("Size", "${image.size / 1024} KB, SHA-256 ${image.sha256Hex.take(8)}")
                                Button(
                                    onClick = {
                                        GatewayService.meshtasticBle?.let { ble ->
                                            net.meshsat.android.ble.NodeFirmwareUpdater.start(context.applicationContext, ble, image)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Update the node now", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { picked = null }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Choose another file", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            refusal?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MeshSatAmber) }
                            Text(
                                text = "Check for update asks GitHub for the newest MeshSat release for this node. A file: pick firmware-$nodeEnv-<version>.bin " +
                                    "(not the .factory.bin). The node restarts twice; keep the phone close. A few minutes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshSatTextMuted,
                            )
                        }
                        net.meshsat.android.ble.NodeFirmwareUpdater.State.AskingNode -> {
                            Text("Asking the node to get ready...", style = MaterialTheme.typography.bodySmall, color = OffWhite)
                            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is net.meshsat.android.ble.NodeFirmwareUpdater.State.Working -> {
                            Text(st.text, style = MaterialTheme.typography.bodySmall, color = OffWhite)
                            val f = st.fraction
                            if (f == null) {
                                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                androidx.compose.material3.LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        is net.meshsat.android.ble.NodeFirmwareUpdater.State.Done -> {
                            Text("Updated to ${st.version}.", style = MaterialTheme.typography.bodySmall, color = MeshSatTeal)
                            OutlinedButton(onClick = { picked = null; net.meshsat.android.ble.NodeFirmwareUpdater.reset() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Done", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is net.meshsat.android.ble.NodeFirmwareUpdater.State.Failed -> {
                            Text(st.reason, style = MaterialTheme.typography.bodySmall, color = MeshSatAmber)
                            if (st.canRetry) {
                                Button(
                                    onClick = {
                                        GatewayService.meshtasticBle?.let { ble -> net.meshsat.android.ble.NodeFirmwareUpdater.retry(context.applicationContext, ble) }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Retry with the same file", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            OutlinedButton(onClick = { net.meshsat.android.ble.NodeFirmwareUpdater.reset() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Close", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // --- Iridium RockBLOCK 9704 (JSPR/IMT) Section ---
        // Few people have a 9704 on an HC-05: the card stays folded away until one has been
        // connected, or someone asks for it (MESHSAT-1249).
        val saved9704 by settings.iridium9704BtAddress.collectAsState(initial = "")
        var show9704 by remember { mutableStateOf(false) }
        val using9704 = saved9704.isNotBlank() ||
            (iridium9704State?.value ?: net.meshsat.android.bt.Iridium9704Spp.State.Disconnected) != net.meshsat.android.bt.Iridium9704Spp.State.Disconnected
        if (section.shows(SetupSection.Satellite) && !using9704 && !show9704) {
            TextButton(onClick = { show9704 = true }) {
                Text("Using a RockBLOCK 9704 on an HC-05 instead? Set it up", color = OffWhite)
            }
        }
        if (section.shows(SetupSection.Satellite) && (using9704 || show9704)) {
            SectionCard("RockBLOCK 9704 (separate modem)") {
                val state9704 = iridium9704State?.value ?: net.meshsat.android.bt.Iridium9704Spp.State.Disconnected
                ConnectionStatusRow(
                    label = "Status",
                    connected = state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Ready,
                    statusText = when (state9704) {
                        net.meshsat.android.bt.Iridium9704Spp.State.Ready -> "Ready"
                        net.meshsat.android.bt.Iridium9704Spp.State.Initializing -> "Initializing JSPR..."
                        net.meshsat.android.bt.Iridium9704Spp.State.Connected -> "Connected (init pending)"
                        net.meshsat.android.bt.Iridium9704Spp.State.Connecting -> "Connecting..."
                        net.meshsat.android.bt.Iridium9704Spp.State.Disconnected -> "Disconnected"
                    },
                    color = ColorIridium,
                )

                if (state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Ready) {
                    SignalReadingRow(iridium9704Reading?.value, signalNow)
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

                if (state9704 == net.meshsat.android.bt.Iridium9704Spp.State.Disconnected) {
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
        }

        // --- Encryption Section ---
        if (section.shows(SetupSection.Messaging)) {
            SectionCard("Encryption") {
                SettingRow("Encryption enabled") {
                    Switch(
                        checked = encryptionEnabled,
                        onCheckedChange = { scope.launch { settings.setEncryptionEnabled(it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }

                if (net.meshsat.android.sms.SmsCapability.included) {
                    SettingRow("Auto-decrypt incoming SMS") {
                        Switch(
                            checked = autoDecrypt,
                            onCheckedChange = { scope.launch { settings.setAutoDecryptSms(it) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                        )
                    }
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
        }

        // --- Per-Channel Compression Section (MESHSAT-203) ---
        if (section.shows(SetupSection.Messaging)) {
            SectionCard("Message compression") {
                Text(
                    text = "Only for links where the other end is MeshSat too: anyone else sees a line of " +
                            "letters. MSVQ-SC keeps the meaning, not the exact words. Mesh messages always go " +
                            "out as typed, because a mesh channel is shared with other radios. Compressed " +
                            "messages coming in are always read, whatever is set here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                Spacer(Modifier.height(8.dp))

                val channels = listOf(
                    "sms" to "SMS" to compressSms,
                    "iridium" to "Iridium SBD" to compressIridium,
                    "mqtt" to "MQTT (Hub)" to compressMqtt,
                ).filter { it.first.first != "sms" || net.meshsat.android.sms.SmsCapability.included }

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
                val anyMsvqsc = compressSms == "msvqsc" || compressIridium == "msvqsc"
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
        }

        // --- SOS: emergency contacts, name, test (MESHSAT-1249) ---
        if (section.shows(SetupSection.Safety)) {
            SosSettingsCard()
        }

        // --- Dead Man's Switch ---
        if (section.shows(SetupSection.Safety)) {
            SectionCard("Check-in timer (dead man's switch)") {
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
        }

        // --- Channel Health ---
        if (section.shows(SetupSection.Diagnostics)) {
            SectionCard("Link health") {
                val healthScorer = GatewayService.healthScorer
                if (healthScorer == null) {
                    Text(
                        text = "Health scorer not available. Connect a transport first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                } else {
                    val interfaceStates = GatewayService.meshtasticBle?.let { "mesh_0" } ?: ""
                    val channels = listOfNotNull("mesh_0", "iridium_0", "sms_0".takeIf { net.meshsat.android.sms.SmsCapability.included })

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
        }

        // --- Canned Messages ---
        if (section.shows(SetupSection.Messaging)) {
            SectionCard("Quick messages") {
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
        }

        // --- MeshSat Pi Section ---
        if (section.shows(SetupSection.Integrations)) {
            SectionCard("Ham radio (APRS)") {
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
                            net.meshsat.android.aprs.KissClient.State.Connected -> "Connected"
                            net.meshsat.android.aprs.KissClient.State.Connecting -> "Connecting..."
                            net.meshsat.android.aprs.KissClient.State.Error -> "Error"
                            net.meshsat.android.aprs.KissClient.State.Disconnected -> "Disconnected"
                        }
                        val isOnline = state.value == net.meshsat.android.aprs.KissClient.State.Connected
                        ConnectionStatusRow("KISS TNC", isOnline, statusText, MeshSatTeal)
                    }
                } else {
                    aprsIsState?.let { state ->
                        val statusText = when (state.value) {
                            net.meshsat.android.aprs.AprsIsClient.State.Connected -> "Connected"
                            net.meshsat.android.aprs.AprsIsClient.State.Connecting -> "Connecting..."
                            net.meshsat.android.aprs.AprsIsClient.State.Error -> "Error"
                            net.meshsat.android.aprs.AprsIsClient.State.Disconnected -> "Disconnected"
                        }
                        val isOnline = state.value == net.meshsat.android.aprs.AprsIsClient.State.Connected
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

                    // A KISS TNC has no command for the radio's frequency, so a field here set
                    // nothing (MESHSAT-1249): say where it is set instead.
                    Text(
                        text = "The frequency is set on the radio itself: 144.800 MHz in Europe, 144.390 MHz in North America.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
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
                                    aprsIsPasscodeInput = net.meshsat.android.aprs.AprsIsPasscode.calculate(aprsCallsignInput)
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
        }

        // --- TAK / CoT Section (MESHSAT-451) ---
        if (section.shows(SetupSection.Integrations)) {
            SectionCard("TAK") {
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
        }

        // --- Reticulum: the LXMF address, name and announce; the satellite allow-list (MESHSAT-1553) ---
        if (section.shows(SetupSection.Integrations)) {
            SectionCard("Reticulum") {
                Text("Your LXMF address", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = lxmfAddress.ifBlank { "Starts with the gateway" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lxmfAddress.isBlank()) MeshSatTextMuted else MeshSatTeal,
                        modifier = Modifier.weight(1f),
                    )
                    if (lxmfAddress.isNotBlank()) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LXMF address", lxmfAddress))
                            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy the address", tint = MeshSatTeal)
                        }
                    }
                }
                Text(
                    text = "Sideband, CrossTalk, NomadNet and MeshChat reach this phone at this address. " +
                        "The name below is what they show for it; it takes effect when the gateway restarts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = lxmfNameInput,
                        onValueChange = { lxmfNameInput = it.take(40) },
                        label = { Text("Display name", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                    )
                    Button(
                        onClick = {
                            scope.launch { settings.setLxmfDisplayName(lxmfNameInput.trim()) }
                            Toast.makeText(context, "Name saved", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    ) { Text("Save", style = MaterialTheme.typography.bodySmall) }
                }
                Button(
                    onClick = {
                        val sent = GatewayService.rnsStack?.announce() == true
                        Toast.makeText(context, if (sent) "Announced" else "Nothing to announce on: no Reticulum link is up", Toast.LENGTH_SHORT).show()
                    },
                    enabled = GatewayService.rnsStack != null,
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                ) { Text("Announce now", style = MaterialTheme.typography.bodySmall) }

                HorizontalDivider(color = MeshSatBorder, modifier = Modifier.padding(vertical = 8.dp))
                Text("Propagation node", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "A message sent as Propagated waits at an LXMF propagation node until the other side syncs, " +
                        "and Sync fetches what a node holds for this phone. Automatic takes the nearest node heard announcing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                val pnStack = GatewayService.rnsStack
                val pnNodes = remember(pnStack, pnTick) { pnStack?.lxmf?.propagationNodes().orEmpty() }
                val pnCurrent = remember(pnStack, pnTick, lxmfPropagationNode) { pnStack?.lxmf?.propagationNodeInfo() }
                LaunchedEffect(pnStack) { while (true) { kotlinx.coroutines.delay(2000); pnTick++ } }
                Text(
                    text = when {
                        lxmfPropagationNode == "none" -> "Off"
                        pnStack == null -> "Starts with the gateway"
                        pnCurrent != null -> "Using ${pnCurrent.name ?: pnCurrent.destHex.take(8)}, " +
                            "${net.meshsat.android.ui.Words.count(pnCurrent.hops, "hop")} away, stamp cost ${pnCurrent.stampCost}" +
                            (if (lxmfPropagationNode.isBlank()) " (automatic)" else "")
                        else -> "No propagation node heard yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pnCurrent != null && lxmfPropagationNode != "none") MeshSatTeal else MeshSatTextMuted,
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = lxmfPropagationNode.isBlank(),
                        onClick = { scope.launch { settings.setLxmfPropagationNode(""); pnStack?.setPropagationNode("") } },
                        label = { Text("Automatic", style = MaterialTheme.typography.bodySmall) },
                    )
                    FilterChip(
                        selected = lxmfPropagationNode == "none",
                        onClick = { scope.launch { settings.setLxmfPropagationNode("none"); pnStack?.setPropagationNode("none") } },
                        label = { Text("Off", style = MaterialTheme.typography.bodySmall) },
                    )
                    pnNodes.forEach { n ->
                        FilterChip(
                            selected = lxmfPropagationNode == n.destHex,
                            onClick = { scope.launch { settings.setLxmfPropagationNode(n.destHex); pnStack?.setPropagationNode(n.destHex) } },
                            label = { Text(n.name ?: n.destHex.take(8), style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = syncMinInput,
                        onValueChange = { v ->
                            syncMinInput = v.filter { c -> c.isDigit() }.take(4)
                            val minutes = syncMinInput.ifBlank { "0" }
                            scope.launch { settings.setLxmfSyncIntervalMin(minutes); pnStack?.startSyncLoop((minutes.toLongOrNull() ?: 0L) * 60_000L) }
                        },
                        label = { Text("Sync every (min, 0 = by hand)", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                    )
                    Button(
                        onClick = {
                            pnStack?.lxmf?.sync(); pnTick++
                            Toast.makeText(context, "Syncing from the propagation node", Toast.LENGTH_SHORT).show()
                        },
                        enabled = pnCurrent != null && lxmfPropagationNode != "none" && pnStack?.lxmf?.syncState?.busy != true,
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    ) { Text("Sync now", style = MaterialTheme.typography.bodySmall) }
                }
                pnStack?.lxmf?.let { r ->
                    val last = r.lastSync
                    Text(
                        text = when {
                            r.syncState.busy -> "Syncing: ${r.syncState.name.lowercase().replace('_', ' ')}"
                            last == null -> "Not synced yet"
                            last.state == net.meshsat.android.lxmf.SyncState.COMPLETE ->
                                "Last sync: ${net.meshsat.android.ui.Words.count(last.received, "new message")}" + (if (last.duplicates > 0) ", ${last.duplicates} already here" else "")
                            else -> "Last sync failed: ${last.error}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }

                HorizontalDivider(color = MeshSatBorder, modifier = Modifier.padding(vertical = 8.dp))
                Text("Reticulum by satellite", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "The satellite carries announces and path requests only for the addresses listed here (yours is " +
                        "always included), and every packet arriving over it counts as this many extra hops, so the far " +
                        "end waits for the satellite round trip: 20 covers a minute each way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                OutlinedTextField(
                    value = paidDestsInput,
                    onValueChange = { paidDestsInput = it },
                    label = { Text("Addresses across the satellite, comma separated", style = MaterialTheme.typography.bodySmall) },
                    minLines = 1, maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = paidHopsInput,
                        onValueChange = { paidHopsInput = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Extra hops", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                    )
                    Button(
                        onClick = {
                            val dests = paidDestsInput.split(",", " ", "\n").map { it.trim().lowercase() }.filter { net.meshsat.android.ui.Peers.isLxmf(it) }
                            val hops = paidHopsInput.toIntOrNull()?.coerceIn(0, 32) ?: 20
                            scope.launch {
                                settings.setRnsPaidDests(dests.joinToString(","))
                                settings.setRnsPaidExtraHops(hops.toString())
                                GatewayService.rnsStack?.setPaidPolicy(
                                    dests.map { h -> ByteArray(16) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() } },
                                    hops, (settings.rnsPaidMinIntervalMin.first().toLongOrNull() ?: 5L) * 60_000L,
                                )
                            }
                            Toast.makeText(context, "Satellite list saved: ${dests.size} address(es), $hops extra hops", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    ) { Text("Save", style = MaterialTheme.typography.bodySmall) }
                }

                HorizontalDivider(color = MeshSatBorder, modifier = Modifier.padding(vertical = 8.dp))
                Text("Peers on this WiFi", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Find Reticulum apps by themselves (AutoInterface)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = rnsAutoEnabled,
                        onCheckedChange = { scope.launch { settings.setRnsAutoEnabled(it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }
                val auto = GatewayService.rnsAutoInterface
                Text(
                    text = when {
                        !rnsAutoEnabled -> "Off. Takes effect when the gateway restarts. Sideband and rnsd with their AutoInterface on then find this phone with nothing typed."
                        auto == null -> "Starts with the gateway."
                        !auto.isOnline -> "Not running: no WiFi link-local address."
                        else -> "Running on ${auto.devices().joinToString()}; ${net.meshsat.android.ui.Words.count(auto.peerCount(), "peer")} discovered."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Listen on TCP port $rnsTcpListenPort", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = rnsTcpListenEnabled,
                        onCheckedChange = { scope.launch { settings.setRnsTcpListenEnabled(it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }
                val wifiIp = remember { net.meshsat.android.reticulum.RnsTcpServerInterface.wifiAddress() }
                val listener = GatewayService.rnsTcpServer
                Text(
                    text = when {
                        !rnsTcpListenEnabled -> "Off. Takes effect when the gateway restarts."
                        listener == null -> "Starts with the gateway."
                        !listener.isOnline -> "Not listening (the port may be taken)."
                        else -> "Listening; ${net.meshsat.android.ui.Words.count(listener.peerCount(), "peer")} connected. In Sideband, add a TCP client interface to " +
                            (wifiIp ?: "this phone's WiFi address") + " port ${listener.boundPort}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                HorizontalDivider(color = MeshSatBorder, modifier = Modifier.padding(vertical = 8.dp))
                Text("Reticulum over TCP", style = MaterialTheme.typography.bodyMedium)
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
                        net.meshsat.android.reticulum.RnsTcpInterface.State.Connected -> "Connected"
                        net.meshsat.android.reticulum.RnsTcpInterface.State.Connecting -> "Connecting..."
                        net.meshsat.android.reticulum.RnsTcpInterface.State.Error -> "Error"
                        net.meshsat.android.reticulum.RnsTcpInterface.State.Disconnected -> "Disconnected"
                    }
                    val isOnline = state.value == net.meshsat.android.reticulum.RnsTcpInterface.State.Connected
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
        }

        if (section.shows(SetupSection.Hub)) {
            // --- An RNode (LoRa radio) on USB OTG or Bluetooth LE (MESHSAT-1553 P4) ---
            SectionCard("RNode (LoRa radio)") {
                Text(
                    text = "A Reticulum LoRa radio (RNode firmware 1.52 or newer) on a USB OTG cable or over Bluetooth LE puts this " +
                        "phone on the same mesh as Sideband and the kits' radios. Peers must match frequency, bandwidth, spreading " +
                        "factor and coding rate exactly; the presets are CrossTalk's starting points, not legal requirements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Use an RNode", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = rnodeEnabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                settings.setRnodeEnabled(on)
                                context.startService(Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_RESTART_RNODE))
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = rnodePortInput == "usb", onClick = { rnodePortInput = "usb" }, label = { Text("USB OTG", style = MaterialTheme.typography.bodySmall) })
                    FilterChip(selected = rnodePortInput != "usb", onClick = { if (rnodePortInput == "usb") rnodePortInput = "ble:" }, label = { Text("Bluetooth LE", style = MaterialTheme.typography.bodySmall) })
                }
                if (rnodePortInput != "usb") {
                    OutlinedTextField(
                        value = rnodePortInput.removePrefix("ble:"),
                        onValueChange = { rnodePortInput = "ble:" + it.uppercase().take(17) },
                        label = { Text("RNode Bluetooth address", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                    )
                    Button(
                        onClick = {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            if (perms.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
                                Toast.makeText(context, "Grant the Bluetooth permissions on the Setup tab first", Toast.LENGTH_SHORT).show()
                            } else {
                                rnodeScanResults.clear(); rnodeScanning = true
                                val ok = net.meshsat.android.rnode.RnodeBleScanner.scan(context, 10_000,
                                    onFound = { f -> if (rnodeScanResults.none { it.address == f.address }) rnodeScanResults.add(f) },
                                    onDone = { rnodeScanning = false })
                                if (!ok) { rnodeScanning = false; Toast.makeText(context, "Bluetooth is off", Toast.LENGTH_SHORT).show() }
                            }
                        },
                        enabled = !rnodeScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    ) { Text(if (rnodeScanning) "Scanning..." else "Scan for RNodes", style = MaterialTheme.typography.bodySmall) }
                    rnodeScanResults.forEach { f ->
                        TextButton(onClick = { rnodePortInput = "ble:" + f.address }) {
                            Text("${f.name}  ${f.address}  ${f.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Radio parameters", style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    net.meshsat.android.rnode.RnodeProtocol.PRESETS.forEach { pr ->
                        FilterChip(selected = rnodePresetInput == pr.id, onClick = { rnodePresetInput = pr.id }, label = { Text(pr.label.removeSuffix(" (starter)"), style = MaterialTheme.typography.bodySmall) })
                    }
                    FilterChip(selected = rnodePresetInput == "custom", onClick = { rnodePresetInput = "custom" }, label = { Text("Custom", style = MaterialTheme.typography.bodySmall) })
                }
                val shownParams = if (rnodePresetInput == "custom") net.meshsat.android.data.parseRnodeParams(rnodeCustomInput)
                    else net.meshsat.android.rnode.RnodeProtocol.preset(rnodePresetInput)?.params
                if (rnodePresetInput == "custom") {
                    OutlinedTextField(
                        value = rnodeCustomInput,
                        onValueChange = { rnodeCustomInput = it.filter { c -> c.isDigit() || c == ',' }.take(40) },
                        label = { Text("frequency Hz, bandwidth Hz, TX power dBm, SF, CR", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        isError = shownParams == null || shownParams.validate() != null,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                    )
                } else {
                    Text(net.meshsat.android.rnode.RnodeProtocol.preset(rnodePresetInput)?.description ?: "", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                }
                shownParams?.let { p ->
                    Text(
                        text = "%.4f MHz, %d kHz, SF %d, CR 4/%d, %d dBm, about %.0f bit/s on air".format(p.frequency / 1e6, p.bandwidth / 1000, p.sf, p.cr, p.txPower, p.bitrate()) +
                            (p.validate()?.let { "; $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.validate() == null) MeshSatTextMuted else MeshSatRed,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = rnodeAirtimeInput,
                        onValueChange = { rnodeAirtimeInput = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(12) },
                        label = { Text("Airtime limits % short,long (0 = none)", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                    )
                    Button(
                        onClick = {
                            val bad = rnodePresetInput == "custom" && (shownParams == null || shownParams.validate() != null)
                            if (bad) { Toast.makeText(context, "The custom parameters are not valid", Toast.LENGTH_SHORT).show() }
                            else if (rnodePortInput != "usb" && rnodePortInput.removePrefix("ble:").length != 17) { Toast.makeText(context, "Pick or type the RNode's Bluetooth address", Toast.LENGTH_SHORT).show() }
                            else scope.launch {
                                settings.setRnodePort(rnodePortInput)
                                settings.setRnodePreset(rnodePresetInput)
                                settings.setRnodeCustomParams(rnodeCustomInput)
                                settings.setRnodeAirtimeLimits(rnodeAirtimeInput.ifBlank { "0,0" })
                                context.startService(Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_RESTART_RNODE))
                                Toast.makeText(context, if (rnodeEnabled) "RNode settings applied, reconnecting" else "RNode settings saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    ) { Text("Apply", style = MaterialTheme.typography.bodySmall) }
                }
                val rn = GatewayService.rnsRnodeInterface
                val rs = rn?.stats()
                Text(
                    text = when {
                        !rnodeEnabled -> "Off."
                        rn == null -> "Starts with the gateway (or the settings do not parse: see the log)."
                        !rn.isOnline -> rn.status
                        rs == null -> rn.status
                        else -> "Online: ${rn.deviceDescription}, ${net.meshsat.android.rnode.RnodeProtocol.platformName(rs.platform)} firmware ${rs.firmware}, " +
                            "RSSI ${rs.rssi} dBm, SNR ${rs.snr} dB, ${rs.rxPackets} received, ${rs.txPackets} sent" +
                            (if (rs.airtimeShort > 0) ", airtime ${rs.airtimeShort}%" else "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rn?.isOnline == true) MeshSatTeal else MeshSatTextMuted,
                )
            }

            SectionCard("Hub connection") {
                // --- Where the Hub link stands, and the switch for it ---
                val hubState = hubReporterState?.value
                val ledColor = when (hubState) {
                    net.meshsat.android.hub.HubReporter.State.Connected -> MeshSatGreen
                    net.meshsat.android.hub.HubReporter.State.Connecting -> MeshSatAmber
                    net.meshsat.android.hub.HubReporter.State.Error -> MeshSatRed
                    else -> MeshSatTextMuted
                }
                val statusLabel = when {
                    !hubEnabled -> "Switched off"
                    hubState == net.meshsat.android.hub.HubReporter.State.Connected ->
                        "Connected as ${hubReporterNow?.bridgeId ?: hubBridgeIdInput}"
                    hubState == net.meshsat.android.hub.HubReporter.State.Connecting -> "Connecting"
                    hubState == net.meshsat.android.hub.HubReporter.State.Error -> "Cannot reach the Hub"
                    hubState == net.meshsat.android.hub.HubReporter.State.Disconnected -> "Not connected"
                    else -> "Not set up: scan the Hub's QR code"
                }
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
                        Box(modifier = Modifier.size(10.dp).background(ledColor, CircleShape))
                        Text(statusLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = hubEnabled,
                        onCheckedChange = { scope.launch { settings.setHubEnabled(it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                        modifier = Modifier.semantics { contentDescription = "Use the Hub" },
                    )
                }
                // A provisioning claim still waiting for the Hub (MESHSAT-1306): shown here too,
                // for when its dialog was hidden.
                val claim = net.meshsat.android.crypto.ProvisionClaim.state.collectAsState().value
                if (claim is net.meshsat.android.crypto.ProvisionClaim.State.Waiting) {
                    val waited = net.meshsat.android.ui.components.waitedSeconds(claim.startedMs)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MeshSatTeal)
                        Text(
                            "Getting the Hub's settings, $waited s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
                // Why, when it failed: the library's own words, not only "Cannot reach the Hub"
                // (MESHSAT-749; the SNI fault sat in logcat for a day).
                val hubWhy = hubReporterNow?.lastError.collectOrNull()?.value.orEmpty()
                if (hubEnabled && hubState == net.meshsat.android.hub.HubReporter.State.Error && hubWhy.isNotBlank()) {
                    Text(
                        text = "Why: $hubWhy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatRed,
                    )
                }

                // --- The way to set it up: the Hub's QR code (MESHSAT-1249: first, not below Ping) ---
                Button(
                    onClick = {
                        val scanIntent = com.journeyapps.barcodescanner.ScanContract().createIntent(
                            context,
                            com.journeyapps.barcodescanner.ScanOptions().apply {
                                setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                                setPrompt("Scan the Hub's QR code")
                                setBeepEnabled(false)
                                setOrientationLocked(true)
                            },
                        )
                        qrScanLauncher.launch(scanIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal, contentColor = MeshSatInk),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Scan the Hub's QR code")
                }
                Text(
                    text = "On the Hub, open Fleet and add a bridge for this phone: the QR code it shows fills in everything, certificates included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

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
                        Text("Test the connection")
                    }
                    Text(
                        text = if (pingResult.isNotBlank()) pingResult else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pingResult.endsWith("ms")) MeshSatGreen else MeshSatTextMuted,
                    )
                }

                // --- Everything the QR code fills in, for people who set it up by hand ---
                var showHubDetails by remember { mutableStateOf(false) }
                TextButton(onClick = { showHubDetails = !showHubDetails }) {
                    Text(if (showHubDetails) "Hide connection details" else "Connection details", color = OffWhite)
                }
                if (showHubDetails) {
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
                            // The eye was missing: the flag existed but nothing could change it (MESHSAT-1249).
                            trailingIcon = {
                                IconButton(onClick = { showHubPassword = !showHubPassword }) {
                                    Icon(
                                        if (showHubPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (showHubPassword) "Hide password" else "Show password",
                                    )
                                }
                            },
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

                    // --- Texts to other MeshSat apps and kits through the Hub (hub_msg, MESHSAT-1584) ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Messages through the Hub", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = hubMsgEnabled,
                            onCheckedChange = { scope.launch { settings.setHubMsgEnabled(it) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                            modifier = Modifier.semantics { contentDescription = "Messages through the Hub" },
                        )
                    }
                    Text(
                        text = (if (hubMsgEnabled == (GatewayService.hubMsgRoute != null)) "" else "Takes effect when the gateway restarts. ") +
                            "Texts to another MeshSat app or kit in your Hub account, by its bridge id. Both ends need the internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )

                    // --- Hub relay client (MESHSAT-1157): fallback tunnel to one kit through the Hub ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Reach a kit through the Hub", style = MaterialTheme.typography.bodyMedium)
                        // Off, and not switchable, until there is a kit to relay to: it used to
                        // show ON with no target, when the service starts nothing (MESHSAT-1249).
                        Switch(
                            checked = hubRelayEnabled && hubRelayTargetInput.isNotBlank(),
                            onCheckedChange = { scope.launch { settings.setHubRelayEnabled(it) } },
                            enabled = hubRelayTargetInput.isNotBlank(),
                            colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hubRelayTargetInput,
                            onValueChange = { hubRelayTargetInput = it },
                            label = { Text("Kit's bridge ID", style = MaterialTheme.typography.bodySmall) },
                            placeholder = { Text("kit-a", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) },
                            singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                        )
                        OutlinedTextField(
                            value = hubRelayUrlInput,
                            onValueChange = { hubRelayUrlInput = it },
                            label = { Text("Hub API URL (optional)", style = MaterialTheme.typography.bodySmall) },
                            placeholder = { Text("derived from MQTT URL", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) },
                            singleLine = true, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder),
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                settings.setHubUrl(hubUrlInput)
                                settings.setHubBridgeId(hubBridgeIdInput)
                                settings.setHubCallsign(hubCallsignInput)
                                settings.setHubUsername(hubUsernameInput)
                                settings.setHubPassword(hubPasswordInput)
                                settings.setHubHealthInterval(hubHealthIntervalInput)
                                settings.setHubRelayTarget(hubRelayTargetInput)
                                settings.setHubRelayUrl(hubRelayUrlInput)
                            }
                            Toast.makeText(context, "Saved. Restart the app to use them.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    ) {
                        Text("Save", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    text = "The Hub is the control room: with it, this phone shows in the fleet and on the map, " +
                        "and SOS alerts reach it over the internet as well as by satellite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        if (section.shows(SetupSection.Sms) && net.meshsat.android.sms.SmsCapability.included) {
            // SMS was only ever offered from a banner in Messages; Setup is where people look for it.
            // Only what the app actually uses, and what the manifest declares. READ_SMS was in
            // this list and in neither: nothing reads the inbox, the manifest never asked for it,
            // and a permission that is not declared can never be granted - so this card said
            // "Not allowed yet" for ever on a phone that sends and receives texts perfectly well,
            // and its button asked for something Android would not give (MESHSAT-1261 session).
            val sms = rememberPermissionAsk(arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
            ))
            val smsGranted = sms.granted
            SectionCard("Text messages") {
                ConnectionStatusRow(
                    label = "SMS",
                    connected = smsGranted,
                    statusText = if (smsGranted) "Allowed" else "Not allowed yet",
                    color = MeshSatTeal,
                )
                Text(
                    text = "MeshSat sends and receives texts through this phone's SIM when the network works. " +
                        "Your carrier's normal rates apply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextSecondary,
                )
                if (!smsGranted) {
                    if (sms.needsSettings) {
                        Text(
                            text = goToSettingsText("SMS"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatAmber,
                        )
                    }
                    Button(
                        onClick = sms.ask,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (sms.needsSettings) "Open MeshSat settings" else "Allow SMS")
                    }
                }
            }
        }
        if (section.shows(SetupSection.Sms) && net.meshsat.android.sms.SmsCapability.included) {
            // Was "Kit phone number": one global number, from when this app was a companion to
            // a single Raspberry Pi kit. There is never just one other device, so it is named for
            // what it actually does - the fallback when a text has no recipient of its own.
            SectionCard("Where a text goes with no recipient") {
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("Optional number, e.g. +31612345678", style = MaterialTheme.typography.bodySmall) },
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
                    text = "Only used when a message has no recipient of its own: a routing rule that " +
                        "forwards to SMS without naming a number. Messages you write carry their own " +
                        "recipient, and SOS texts go to your emergency contacts under Safety. Leave it " +
                        "empty and a text with no recipient is not sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        // --- Offline Maps ---
        if (section.shows(SetupSection.Maps)) {
            SectionCard("Offline maps") {
                var offlineEnabled by remember { mutableStateOf(false) }
                var offlineFile by remember { mutableStateOf("") }
                var mapFiles by remember { mutableStateOf<List<MBTilesManager.MBTilesInfo>>(emptyList()) }
                var importing by remember { mutableStateOf(false) }
                var confirmDelete by remember { mutableStateOf<MBTilesManager.MBTilesInfo?>(null) }

                LaunchedEffect(Unit) {
                    offlineEnabled = settings.offlineMapEnabled.first()
                    offlineFile = settings.offlineMapFile.first()
                    mapFiles = withContext(Dispatchers.IO) { MBTilesManager.listFiles(context) }
                }

                // The world overview ships inside the app and is copied next to the added maps, so
                // it is shown on its own line and can never be deleted from the list.
                val detailedMaps = mapFiles.filter { it.filename != MBTilesManager.BUNDLED_WORLD_MAP }
                val usableMaps = detailedMaps.filter { !it.isVector }
                val inUse = offlineEnabled && usableMaps.any { it.filename == offlineFile }

                fun useMap(filename: String) {
                    offlineFile = filename
                    offlineEnabled = true
                    scope.launch {
                        settings.setOfflineMapFile(filename)
                        settings.setOfflineMapEnabled(true)
                    }
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
                            mapFiles = withContext(Dispatchers.IO) { MBTilesManager.listFiles(context) }
                            val added = mapFiles.firstOrNull { it.filename == filename }
                            if (added != null && added.isVector) {
                                Toast.makeText(
                                    context,
                                    "Added, but this file has vector tiles, which the map cannot show.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                useMap(filename)
                                Toast.makeText(context, "Map added: ${added?.name ?: filename}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not add this map: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            importing = false
                        }
                    }
                }

                Text(
                    "The map downloads its detail from the internet. Without internet it shows what is installed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextSecondary,
                )

                Text("Installed", style = MaterialTheme.typography.titleSmall, color = MeshSatTextSecondary)

                // Always present: the bundled Natural Earth overview (assets/world.mbtiles).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                ) {
                    Text("World overview", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Built in and always installed. Countries and coastlines at a zoomed-out scale, " +
                            "shown when there is no internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }

                detailedMaps.forEach { info ->
                    val isActive = inUse && info.filename == offlineFile
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
                            .then(
                                if (info.isVector) {
                                    Modifier
                                } else {
                                    Modifier.clickable(onClickLabel = "Use ${info.name}") { useMap(info.filename) }
                                },
                            )
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = isActive,
                            onClick = null,
                            enabled = !info.isVector,
                        )
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                text = info.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            val sizeMb = "%.1f MB".format(info.sizeBytes / 1_048_576.0)
                            val zoomRange = if (info.minZoom != null && info.maxZoom != null) {
                                "zoom ${info.minZoom} to ${info.maxZoom}"
                            } else {
                                ""
                            }
                            Text(
                                text = listOf(sizeMb, zoomRange).filter { it.isNotBlank() }.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshSatTextMuted,
                            )
                            when {
                                info.isVector -> Text(
                                    "Vector tiles: the map cannot show this file.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MeshSatAmber,
                                )
                                isActive -> Text(
                                    "In use",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MeshSatTeal,
                                )
                            }
                        }
                        IconButton(onClick = { confirmDelete = info }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${info.name}", tint = MeshSatTextMuted)
                        }
                    }
                }

                if (usableMaps.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Use my detailed map", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Shown first. Outside it the map uses online tiles, or the world overview without internet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshSatTextMuted,
                            )
                        }
                        Switch(
                            checked = inUse,
                            onCheckedChange = { on ->
                                if (on) {
                                    val pick = usableMaps.firstOrNull { it.filename == offlineFile } ?: usableMaps.first()
                                    useMap(pick.filename)
                                } else {
                                    offlineEnabled = false
                                    scope.launch { settings.setOfflineMapEnabled(false) }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                        )
                    }
                }

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
                        Text("Add a detailed map")
                    }
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Adding the map", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                    }
                }
                Text(
                    "Use an MBTiles file with PNG or JPEG tiles, for example one exported from OpenStreetMap " +
                        "for your area. Files with vector tiles cannot be shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                confirmDelete?.let { info ->
                    AlertDialog(
                        onDismissRequest = { confirmDelete = null },
                        containerColor = MeshSatSurface,
                        title = { Text("Delete this map?") },
                        text = {
                            Text("${info.name} is removed from this phone. You can add it again from a file.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = null
                                scope.launch {
                                    withContext(Dispatchers.IO) { MBTilesManager.delete(context, info.filename) }
                                    mapFiles = withContext(Dispatchers.IO) { MBTilesManager.listFiles(context) }
                                    if (offlineFile == info.filename) {
                                        offlineFile = ""
                                        offlineEnabled = false
                                        settings.setOfflineMapFile("")
                                        settings.setOfflineMapEnabled(false)
                                    }
                                    Toast.makeText(context, "Map deleted: ${info.name}", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("Delete", color = MeshSatRed)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = null }) {
                                Text("Keep it")
                            }
                        },
                    )
                }
            }
        }

        // --- Telemetry (MESHSAT-494) ---
        if (section.shows(SetupSection.Diagnostics)) {
            SectionCard("Crash reports") {
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
                        "Nothing is sent externally. Readable over adb through the local API (/api/telemetry).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        // --- Service ---
        if (section.shows(SetupSection.Diagnostics)) {
            SectionCard("Background service") {
                var showRestartDialog by remember { mutableStateOf(false) }

                // Off unless chosen (owner ruling, 19 Sep 2026, MESHSAT-1249).
                val startOnBoot by settings.startOnBoot.collectAsState(initial = false)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start after a phone restart", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "The gateway starts by itself after the phone restarts or the app is updated, and reconnects to your node. " +
                                "Android gives it your position only once you open the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }
                    Switch(
                        checked = startOnBoot,
                        onCheckedChange = { scope.launch { settings.setStartOnBoot(it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                    )
                }

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
private fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

/**
 * For the bench (MESHSAT-1670, MESHSAT-1674): the signal reads' round trips over the link and how
 * fast the app answered the modem's last segment request. Nothing until the first of each.
 */
@Composable
private fun Imt9704PipeTimings(stats: net.meshsat.android.bt.SignalPollStats?, segment: net.meshsat.android.bt.SegmentTiming?) {
    stats?.describe()?.takeIf { it.isNotEmpty() }?.let { InfoRow("Signal read (min / median / max)", it) }
    segment?.let {
        InfoRow("Last segment request", "${it.askedAfterMs} ms after the send, answered in ${it.answeredInMs} ms")
    }
}

/** The 9704's signal as the screens show it (MESHSAT-1670): the bars, their age once stale, "?" before the first reading. */
@Composable
private fun SignalReadingRow(reading: net.meshsat.android.bt.SignalReading?, nowMs: Long) {
    val kind = net.meshsat.android.bt.SignalFreshness.kind(reading, nowMs)
    InfoRow(
        "Signal",
        net.meshsat.android.bt.SignalFreshness.describe(reading, nowMs),
        valueColor = if (kind == net.meshsat.android.bt.SignalFreshness.Kind.Fresh) androidx.compose.ui.graphics.Color.Unspecified else MeshSatTextMuted,
    )
}

/** Where the Node firmware card's "Check for update" stands (MESHSAT-1672). */
private sealed class ReleaseCheck {
    object None : ReleaseCheck()
    object Checking : ReleaseCheck()
    data class Available(
        val manifest: net.meshsat.android.ble.NodeFirmwareRelease.Manifest,
        val image: net.meshsat.android.ble.NodeFirmwareRelease.Image,
    ) : ReleaseCheck()
    data class UpToDate(val version: String) : ReleaseCheck()
    data class Downloading(val fraction: Float) : ReleaseCheck()
    data class Refused(val reason: String) : ReleaseCheck()
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
