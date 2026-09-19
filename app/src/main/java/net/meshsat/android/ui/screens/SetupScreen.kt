package net.meshsat.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Fence
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.meshsat.android.BuildConfig
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.components.GroupTitle
import net.meshsat.android.ui.components.NavRow
import net.meshsat.android.ui.theme.ColorCellular
import net.meshsat.android.ui.theme.ColorHub
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatTextMuted

/**
 * The Setup tab (MESHSAT-1249): what used to be More plus one 2,276-line Settings scroll, grouped by
 * what the user is trying to do. Every row says where that part stands and opens a short page.
 */
@Composable
fun SetupScreen(navigate: (String) -> Unit) {
    val context = LocalContext.current
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()?.value ?: MeshtasticBle.State.Disconnected
    val sppState = GatewayService.iridiumSpp?.state?.collectAsState()?.value ?: IridiumSpp.State.Disconnected
    val signal = GatewayService.iridiumSpp?.signal?.collectAsState()?.value ?: 0
    val hubState = GatewayService.hubReporter?.state?.collectAsState()?.value
    val smsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = "Setup",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )

        GroupTitle("Get connected")
        NavRow(
            icon = Icons.Outlined.Bluetooth,
            title = "Your MeshSat node",
            detail = when (meshState) {
                MeshtasticBle.State.Connected -> "Connected"
                MeshtasticBle.State.Connecting, MeshtasticBle.State.Scanning -> "Connecting"
                else -> "Not connected. Pair it here."
            },
            iconTint = ColorMesh,
            stateColor = when (meshState) {
                MeshtasticBle.State.Connected -> MeshSatGreen
                MeshtasticBle.State.Connecting, MeshtasticBle.State.Scanning -> MeshSatAmber
                else -> MeshSatTextMuted
            },
            onClick = { navigate("setup/node") },
        )
        NavRow(
            icon = Icons.Outlined.SatelliteAlt,
            title = "Satellite",
            detail = when (sppState) {
                IridiumSpp.State.Connected -> "Modem ready, signal $signal of 5"
                IridiumSpp.State.Connecting -> "Checking the modem"
                else -> if (meshState == MeshtasticBle.State.Connected) "No modem on this radio" else "Connect your node first"
            },
            iconTint = ColorIridium,
            stateColor = when (sppState) {
                IridiumSpp.State.Connected -> MeshSatGreen
                IridiumSpp.State.Connecting -> MeshSatAmber
                else -> MeshSatTextMuted
            },
            onClick = { navigate("setup/satellite") },
        )
        NavRow(
            icon = Icons.Outlined.Cloud,
            title = "Hub",
            detail = when (hubState) {
                null -> "Not set up. Scan the Hub's QR code."
                HubReporter.State.Connected -> "Connected"
                HubReporter.State.Connecting -> "Connecting"
                HubReporter.State.Error -> "Cannot reach the Hub"
                HubReporter.State.Disconnected -> "Not connected"
            },
            iconTint = ColorHub,
            stateColor = when (hubState) {
                HubReporter.State.Connected -> MeshSatGreen
                HubReporter.State.Connecting, HubReporter.State.Disconnected -> MeshSatAmber
                HubReporter.State.Error -> MeshSatRed
                null -> MeshSatTextMuted
            },
            onClick = { navigate("setup/hub") },
        )
        NavRow(
            icon = Icons.Outlined.Sms,
            title = "SMS",
            detail = if (smsAllowed) "Allowed" else "Not allowed yet",
            iconTint = ColorCellular,
            stateColor = if (smsAllowed) MeshSatGreen else MeshSatTextMuted,
            onClick = { navigate("setup/sms") },
        )

        GroupTitle("Using MeshSat")
        NavRow(Icons.Outlined.HealthAndSafety, "Safety", "SOS, check-in timer, zones", { navigate("setup/safety") })
        NavRow(Icons.Outlined.Lock, "Messaging", "Encryption, compression, quick messages", { navigate("setup/messaging") })
        NavRow(Icons.Outlined.Map, "Maps", "Offline maps for when there is no internet", { navigate("setup/maps") })
        NavRow(Icons.Outlined.Radio, "Ham radio, TAK and Reticulum", "Other networks MeshSat can bridge", { navigate("setup/integrations") })
        NavRow(Icons.Outlined.Tune, "Mesh radio settings", "Region, channels, transmit power", { navigate("radio-config") })

        GroupTitle("For experts")
        NavRow(Icons.Outlined.Build, "Advanced", "Routing, links, queue, logs, diagnostics", { navigate("setup/advanced") })
        NavRow(Icons.Outlined.Info, "About", "MeshSat Android ${BuildConfig.VERSION_NAME}", { navigate("about") })
    }
}

/** Setup > Advanced: the tools an operator or a developer needs, kept out of everyone else's way. */
@Composable
fun AdvancedScreen(navigate: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavRow(Icons.AutoMirrored.Outlined.AltRoute, "Routing rules", "Which messages go where, automatically", { navigate("rules") })
        NavRow(Icons.Outlined.Link, "Links", "Every way out, its state and its health", { navigate("interfaces") })
        NavRow(Icons.Outlined.Outbox, "Message queue", "Everything waiting, sent or given up", { navigate("deliveries") })
        NavRow(Icons.Outlined.Hub, "Mesh topology", "How the nodes you hear are linked", { navigate("topology") })
        NavRow(Icons.Outlined.History, "Audit log", "A signed record of what the gateway did", { navigate("audit") })
        NavRow(Icons.Outlined.Key, "Certificates and keys", "The Hub certificate and imported keys", { navigate("credentials") })
        NavRow(Icons.Outlined.LockOpen, "Encrypt or decrypt text", "By hand, with a conversation key", { navigate("decrypt") })
        NavRow(Icons.Outlined.MonitorHeart, "Diagnostics", "Link health, batch queue, crash reports, service", { navigate("setup/diagnostics") })
        HorizontalDivider(color = MeshSatBorder)
    }
}

/** Extra rows at the top of some Setup pages, for screens that belong with those settings. */
@Composable
fun SetupPageLinks(section: SetupSection, navigate: (String) -> Unit) {
    when (section) {
        SetupSection.Satellite -> NavRow(Icons.Outlined.Schedule, "Satellite passes", "When satellites are high overhead", { navigate("passes") })
        SetupSection.Safety -> NavRow(Icons.Outlined.Fence, "Zones", "Alerts when someone enters or leaves an area", { navigate("geofence") })
        else -> Unit
    }
}
