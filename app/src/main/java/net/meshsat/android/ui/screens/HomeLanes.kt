package net.meshsat.android.ui.screens

import net.meshsat.android.ui.components.SkyChart
import net.meshsat.android.ui.components.SkyGeometry
import net.meshsat.android.ui.components.SkySession
import net.meshsat.android.ui.components.SkySignal
import androidx.compose.foundation.clickable
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.meshsat.android.R
import net.meshsat.android.ble.IridiumPipeContract
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.components.LaneState
import net.meshsat.android.ui.components.TransportLane
import net.meshsat.android.ui.theme.ColorCellular
import net.meshsat.android.ui.theme.ColorHub
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

/** A pass this high clears most rooftops; the Passes screen has the full list and other masks. */
private const val HIGH_PASS_DEG = 40.0

/** The top of Home: the MeshSat lockup (the approved artwork, never re-typeset) and Arrange. */
@Composable
fun HomeHeader(onArrange: () -> Unit, nightOn: Boolean = false, onNight: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.brand_lockup),
            contentDescription = "MeshSat",
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.height(26.dp).weight(1f),
        )
        IconButton(onClick = onNight) {
            Icon(
                if (nightOn) Icons.Filled.NightsStay else Icons.Outlined.NightsStay,
                contentDescription = if (nightOn) "Night mode off" else "Night mode on",
                tint = MeshSatTextSecondary,
            )
        }
        IconButton(onClick = onArrange) {
            Icon(Icons.Outlined.SwapVert, contentDescription = "Arrange Home", tint = MeshSatTextSecondary)
        }
    }
}

/**
 * Home's answer to "can my message get out, and how" (MESHSAT-1249): one sentence, then one lane per
 * way out. Each lane says what to do next when it is not working, and opens the screen that fixes it.
 */
@Composable
fun HomeLanes(navigate: (String) -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val settings = remember { SettingsRepository(context) }

    // The transports are created by the service and can appear after this screen, so their state
    // is read on a short tick instead of being collected once from a possibly-null object.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    var iridiumQueue by remember { mutableIntStateOf(0) }
    var meshQueue by remember { mutableIntStateOf(0) }
    var smsQueue by remember { mutableIntStateOf(0) }
    var smsToday by remember { mutableIntStateOf(0) }
    var savedNode by remember { mutableStateOf("") }
    var skySignals by remember { mutableStateOf<List<SkySignal>>(emptyList()) }
    var skySessions by remember { mutableStateOf<List<SkySession>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val dao = db.messageDeliveryDao()
                iridiumQueue = dao.queueDepth("iridium_0")
                meshQueue = dao.queueDepth("mesh_0")
                smsQueue = dao.queueDepth("sms_0")
                smsToday = db.messageDao().countByTransportSince("sms", System.currentTimeMillis() - 86_400_000)
                // The last three hours of signal and sessions, for the chart under the Satellite lane
                val skySince = System.currentTimeMillis() - 3 * 3600_000L
                skySignals = db.signalDao().getSince("iridium", skySince).first().map { SkySignal(it.timestamp / 1000, it.value) }
                skySessions = db.signalDao().getSince("gss", skySince).first().map { SkySession(it.timestamp / 1000, it.value >= 1) }
            }
            savedNode = settings.meshtasticBleAddress.first()
            delay(5_000)
        }
    }

    val ble = GatewayService.meshtasticBle
    val spp = GatewayService.iridiumSpp
    val hub = GatewayService.hubReporter

    val meshState = ble?.state?.value ?: MeshtasticBle.State.Disconnected
    val meshUp = meshState == MeshtasticBle.State.Connected
    val nodes = ble?.nodes?.value.orEmpty()
    val myNum = ble?.myInfo?.value?.myNodeNum ?: 0L
    val myName = nodes.firstOrNull { it.nodeNum == myNum }?.longName.orEmpty()
    val rssi = ble?.rssi?.value ?: 0
    val reconnecting = !meshUp && savedNode.isNotBlank()

    // --- Satellite ---
    val sppState = spp?.state?.value ?: IridiumSpp.State.Disconnected
    val bars = spp?.signal?.value ?: 0
    val silent = spp?.modemSilent?.value == true
    val owner = ble?.iridiumPipe?.value?.owner?.value
    val passes = GatewayService.passes.value
    val passLine = run {
        val nowSec = now / 1000
        val overhead = passes.firstOrNull { it.isActive && it.peakElevDeg >= HIGH_PASS_DEG && it.losUnix > nowSec }
        val next = passes.firstOrNull { it.aosUnix > nowSec && it.peakElevDeg >= HIGH_PASS_DEG }
        when {
            overhead != null -> "A satellite is high overhead now."
            next != null -> "Next high pass ${Words.inTime(next.aosUnix * 1000, now)}."
            else -> null
        }
    }
    val satQueueLine = if (iridiumQueue > 0) "${Words.count(iridiumQueue, "message")} waiting to go out. " else ""
    val bluetoothOff = ble?.bluetoothOn?.value == false
    val (satState, satDetail) = when {
        bluetoothOff && savedNode.isNotBlank() ->
            LaneState.Failed to (satQueueLine + "Bluetooth is off on this phone. Switch it on to reach the node's modem.").trim()
        // Before MESHSAT-1270 a pipe that took no writes still read as Working here, under a
        // line about the satellite overhead. What a person needs to know first is that the
        // phone cannot reach the radio at all; the satellite is not the problem.
        spp?.linkBroken?.value == true ->
            LaneState.Failed to (satQueueLine + "The phone cannot reach the node's modem. Getting the link back.").trim()
        sppState == IridiumSpp.State.Connected ->
            LaneState.Working to (satQueueLine + (passLine ?: "Modem ready.")).trim()
        sppState == IridiumSpp.State.Connecting && silent ->
            LaneState.Failed to "The node's modem does not answer. Check its power and cable."
        sppState == IridiumSpp.State.Connecting -> LaneState.Trying to "Checking the modem."
        owner == IridiumPipeContract.Owner.Node -> LaneState.Trying to "The node is using its modem. The phone takes it next."
        meshUp && ble?.iridiumPipe?.value != null -> LaneState.Trying to "Asking the node for its modem."
        meshUp -> LaneState.Off to "This radio has no satellite modem."
        reconnecting -> LaneState.Trying to (satQueueLine + "Reconnecting to your MeshSat node.").trim()
        else -> LaneState.Off to "Connect a MeshSat node to use its satellite modem."
    }

    // --- Mesh ---
    val others = nodes.count { it.nodeNum != myNum }
    val (meshLane, meshDetail) = when {
        bluetoothOff && savedNode.isNotBlank() ->
            LaneState.Failed to "Bluetooth is off on this phone. Switch it on to reach your node."
        meshUp -> LaneState.Working to buildString {
            append(if (myName.isNotBlank()) "Connected to $myName" else "Connected")
            if (rssi != 0) append(", signal $rssi dBm")
            append('.')
        }
        meshState == MeshtasticBle.State.Connecting || meshState == MeshtasticBle.State.Scanning ->
            LaneState.Trying to "Connecting to your node."
        reconnecting -> LaneState.Trying to "Reconnecting to your node."
        else -> LaneState.Off to "Connect a MeshSat node or a Meshtastic radio."
    }

    // --- SMS ---
    val canText = net.meshsat.android.sms.SmsCapability.canSend(context)
    val smsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    val (smsLane, smsDetail) = when {
        !canText -> LaneState.Off to "This phone cannot send SMS."
        !smsAllowed -> LaneState.Off to "Allow SMS to send and receive texts."
        smsQueue > 0 -> LaneState.Working to "${Words.count(smsQueue, "message")} waiting to go out."
        else -> LaneState.Working to "Ready."
    }

    // --- Hub ---
    val hubState = hub?.state?.value
    val (hubLane, hubDetail) = when (hubState) {
        null -> LaneState.Off to "Scan the Hub's QR code to connect this phone."
        HubReporter.State.Connected -> LaneState.Working to "Connected as ${hub.bridgeId}."
        HubReporter.State.Connecting -> LaneState.Trying to "Connecting to the Hub."
        HubReporter.State.Error -> LaneState.Failed to "Cannot reach the Hub. It keeps trying by itself."
        HubReporter.State.Disconnected -> LaneState.Trying to "Not connected. It keeps trying by itself."
    }

    // --- The sentence ---
    val ways = buildList {
        if (satState == LaneState.Working) add("satellite")
        if (meshLane == LaneState.Working) add("mesh")
        if (smsLane == LaneState.Working) add("SMS")
        if (hubLane == LaneState.Working) add("the Hub")
    }
    val sentence = if (ways.isEmpty()) "Nothing can send yet." else "Messages can go out by ${joinAnd(ways)}."
    val waiting = iridiumQueue + meshQueue + smsQueue
    val next = when {
        waiting > 0 -> "${Words.count(waiting, "message")} on the way."
        ways.isEmpty() -> "Start with your MeshSat node, below."
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = sentence, style = MaterialTheme.typography.headlineSmall)
            if (next != null) {
                Text(text = next, style = MaterialTheme.typography.bodyLarge, color = MeshSatTextSecondary)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
        ) {
            TransportLane(
                icon = ImageVector.vectorResource(R.drawable.ic_transport_satellite),
                name = "Satellite",
                color = ColorIridium,
                state = satState,
                metric = if (sppState == IridiumSpp.State.Connected) "$bars/5" else null,
                detail = satDetail,
                inFlight = iridiumQueue > 0,
                onClick = { navigate(if (sppState == IridiumSpp.State.Connected) "passes" else "setup/satellite") },
            )
            // The Bridge's Iridium widget: three hours of real signal and sessions over the passes
            // that were predicted for them, and the next three hours of passes (MESHSAT-1300).
            val nowSec = now / 1000
            val skyStart = nowSec - 3 * 3600L
            val skyEnd = nowSec + 3 * 3600L
            if (savedNode.isNotBlank() && (skySignals.isNotEmpty() || passes.any { SkyGeometry.overlaps(it, skyStart, skyEnd) })) {
                SkyChart(
                    passes = passes,
                    signals = skySignals,
                    sessions = skySessions,
                    startSec = skyStart,
                    endSec = skyEnd,
                    nowSec = nowSec,
                    compact = true,
                    windowLabel = "6 h window",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navigate("passes") }
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
            HorizontalDivider(color = MeshSatBorder)
            TransportLane(
                icon = ImageVector.vectorResource(R.drawable.ic_transport_mesh),
                name = "Mesh",
                color = ColorMesh,
                state = meshLane,
                metric = if (meshUp) Words.count(others, "node") else null,
                detail = meshDetail,
                inFlight = meshQueue > 0,
                onClick = { navigate(if (meshUp) "people" else "setup/node") },
            )
            HorizontalDivider(color = MeshSatBorder)
            TransportLane(
                icon = Icons.Outlined.Sms,
                name = "SMS",
                color = ColorCellular,
                state = smsLane,
                metric = if (smsLane == LaneState.Working) "$smsToday today" else null,
                detail = smsDetail,
                inFlight = smsQueue > 0,
                onClick = { navigate("setup/sms") },
            )
            HorizontalDivider(color = MeshSatBorder)
            TransportLane(
                icon = Icons.Outlined.Cloud,
                name = "Hub",
                color = ColorHub,
                state = hubLane,
                metric = null,
                detail = hubDetail,
                inFlight = false,
                onClick = { navigate("setup/hub") },
            )
        }
    }
}

/** "a", "a and b", "a, b and c". */
private fun joinAnd(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}
