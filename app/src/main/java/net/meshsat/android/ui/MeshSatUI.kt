package net.meshsat.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.components.MapFocus
import net.meshsat.android.ui.components.SubScreen
import net.meshsat.android.ui.screens.AboutScreen
import net.meshsat.android.ui.screens.AdvancedScreen
import net.meshsat.android.ui.screens.AuditScreen
import net.meshsat.android.ui.screens.ConversationChatView
import net.meshsat.android.ui.screens.CredentialsScreen
import net.meshsat.android.ui.screens.DashboardScreen
import net.meshsat.android.ui.screens.DecryptScreen
import net.meshsat.android.ui.screens.DeliveryScreen
import net.meshsat.android.ui.screens.GeofenceScreen
import net.meshsat.android.ui.screens.InterfacesScreen
import net.meshsat.android.ui.screens.MapScreen
import net.meshsat.android.ui.screens.MessagesScreen
import net.meshsat.android.ui.screens.PassPredictorScreen
import net.meshsat.android.ui.screens.PeersScreen
import net.meshsat.android.ui.screens.RadioConfigScreen
import net.meshsat.android.ui.screens.RulesScreen
import net.meshsat.android.ui.screens.SettingsScreen
import net.meshsat.android.ui.screens.SetupPageLinks
import net.meshsat.android.ui.screens.SetupScreen
import net.meshsat.android.ui.screens.SetupSection
import net.meshsat.android.ui.screens.TopologyScreen
import net.meshsat.android.ui.theme.ColorCellular
import net.meshsat.android.ui.theme.ColorHub
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBg
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.OffWhite
import net.meshsat.android.ui.theme.PlexMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The five tabs (MESHSAT-1249): close to the Bridge's operator shell. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Messages("messages", "Messages", Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    Map("map", "Map", Icons.Outlined.Map, Icons.Filled.Map),
    People("people", "People", Icons.Outlined.Group, Icons.Filled.Group),
    Setup("setup", "Setup", Icons.Outlined.Tune, Icons.Filled.Tune),
}

/** The tab a screen belongs to, so the tab stays lit on the screens below it. */
private fun tabOf(route: String?): Tab = when {
    route == null || route == "home" || route == "passes" -> Tab.Home
    route == "messages" || route.startsWith("chat/") -> Tab.Messages
    route == "map" -> Tab.Map
    route == "people" || route == "topology" -> Tab.People
    else -> Tab.Setup
}

@Composable
fun MeshSatUI() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = tabOf(currentRoute)
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val navigate: (String) -> Unit = { route -> navController.navigate(route) }
    val back: () -> Unit = { navController.popBackStack() }

    Scaffold(
        containerColor = MeshSatBg,
        topBar = { StatusStrip() },
        bottomBar = {
            NavigationBar(containerColor = MeshSatSurface) {
                Tab.entries.forEach { tab ->
                    val selected = tab == currentTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        // Selection is Off White on a quiet pill: orange is kept for live traffic.
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OffWhite,
                            selectedTextColor = OffWhite,
                            unselectedIconColor = MeshSatTextMuted,
                            unselectedTextColor = MeshSatTextMuted,
                            indicatorColor = MeshSatSurfaceLight,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Edge to edge (enableEdgeToEdge), the window is no longer resized for the keyboard, so
        // the content makes room for it itself; consuming the bar padding first keeps the bottom
        // bar's height from being counted twice under the keyboard.
        Box(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            // MapScreen lives outside the NavHost: always in the view tree, never destroyed by
            // navigation, shown and hidden by alpha. It is the reliable way to keep osmdroid's
            // MapView alive across tab switches (its tile threads die on detach).
            Box(
                modifier = if (currentRoute == "map") {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().alpha(0f).pointerInput(Unit) {}
                },
            ) {
                MapScreen(visible = currentRoute == "map")
            }

            // The NavHost stays composed on the Map tab too (its map route draws nothing), so Back
            // is handled there instead of leaving the app.
            NavHost(navController = navController, startDestination = "home") {
                composable("home") { DashboardScreen(navigate) }
                composable("messages") { MessagesScreen(openChat = { peer -> navigate("chat/${Uri.encode(peer)}") }) }
                composable("chat/{peer}") { entry ->
                    ConversationChatView(
                        peer = Uri.decode(entry.arguments?.getString("peer").orEmpty()),
                        db = db,
                        onBack = back,
                    )
                }
                composable("map") { }
                composable("people") {
                    PeersScreen(
                        onConnect = { navigate("setup/node") },
                        onMessage = { peer -> navigate("chat/${Uri.encode(peer)}") },
                        onShowOnMap = { nodeNum ->
                            MapFocus.show(nodeNum)
                            navController.navigate(Tab.Map.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable("setup") { SetupScreen(navigate) }
                composable("setup/{section}") { entry ->
                    val name = entry.arguments?.getString("section")
                    if (name == "advanced") {
                        SubScreen("Advanced", back) { AdvancedScreen(navigate) }
                    } else {
                        val section = SetupSection.fromRoute(name)
                        SubScreen(section.title, back) {
                            SetupPageLinks(section, navigate)
                            Box(Modifier.weight(1f)) { SettingsScreen(navController, section) }
                        }
                    }
                }
                composable("passes") { SubScreen("Satellite passes", back) { PassPredictorScreen() } }
                composable("radio-config") { SubScreen("Mesh radio settings", back) { RadioConfigScreen(onConnect = { navigate("setup/node") }) } }
                composable("rules") { SubScreen("Routing rules", back) { RulesScreen() } }
                composable("interfaces") { SubScreen("Links", back) { InterfacesScreen() } }
                composable("deliveries") { SubScreen("Message queue", back) { DeliveryScreen() } }
                composable("topology") { SubScreen("Mesh topology", back) { TopologyScreen() } }
                composable("geofence") { SubScreen("Zones", back) { GeofenceScreen() } }
                composable("audit") { SubScreen("Audit log", back) { AuditScreen() } }
                composable("credentials") { SubScreen("Certificates and keys", back) { CredentialsScreen() } }
                composable("decrypt") { SubScreen("Encrypt or decrypt text", back) { DecryptScreen() } }
                composable("about") { SubScreen("About", back) { AboutScreen() } }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Status strip: the Bridge header's strip, one icon per way out
// ═══════════════════════════════════════════════════════════════════════

/**
 * A slim strip above every screen: satellite bars, mesh nodes, SMS, Hub and GPS, each an icon in its
 * transport colour when working, amber while trying, grey when off, red when failed, and the UTC time
 * that Iridium passes are given in. It used to show 10 sp codes ("IRD", "CELL") and claimed SMS was
 * always up (MESHSAT-1249).
 */
@Composable
private fun StatusStrip() {
    val context = LocalContext.current
    // The transports can appear after this strip is composed, so their state is read on a tick.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val utcFmt = remember { SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    val spp = GatewayService.iridiumSpp
    val ble = GatewayService.meshtasticBle
    val hub = GatewayService.hubReporter

    val satState = spp?.state?.value
    val satColor = when (satState) {
        IridiumSpp.State.Connected -> ColorIridium
        IridiumSpp.State.Connecting -> if (spp?.modemSilent?.value == true) MeshSatRed else MeshSatAmber
        else -> MeshSatTextMuted
    }
    val bars = spp?.signal?.value ?: 0

    val meshState = ble?.state?.value
    val meshColor = when (meshState) {
        MeshtasticBle.State.Connected -> ColorMesh
        MeshtasticBle.State.Connecting, MeshtasticBle.State.Scanning -> MeshSatAmber
        else -> MeshSatTextMuted
    }
    val myNum = ble?.myInfo?.value?.myNodeNum ?: 0L
    val nodeCount = ble?.nodes?.value.orEmpty().count { it.nodeNum != myNum }

    val canText = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    val smsColor = if (canText && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
        ColorCellular
    } else {
        MeshSatTextMuted
    }
    val hubColor = when (hub?.state?.value) {
        HubReporter.State.Connected -> ColorHub
        HubReporter.State.Connecting, HubReporter.State.Disconnected -> MeshSatAmber
        HubReporter.State.Error -> MeshSatRed
        null -> MeshSatTextMuted
    }
    val gpsColor = if (GatewayService.phoneLocation.value != null) MeshSatGreen else MeshSatTextMuted

    // Drawn edge to edge: the background runs behind Android's status bar, the content sits below
    // it. Without the inset the strip was painted under the system clock and icons.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface)
            .statusBarsPadding()
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StripItem(Icons.Outlined.SatelliteAlt, satColor, if (satState == IridiumSpp.State.Connected) "$bars/5" else null,
            "Satellite " + if (satState == IridiumSpp.State.Connected) "signal $bars of 5" else "not connected")
        StripItem(Icons.Outlined.SettingsInputAntenna, meshColor, if (meshState == MeshtasticBle.State.Connected) "$nodeCount" else null,
            "Mesh " + if (meshState == MeshtasticBle.State.Connected) "$nodeCount nodes" else "not connected")
        StripItem(Icons.Outlined.Sms, smsColor, null, "SMS")
        StripItem(Icons.Outlined.Cloud, hubColor, null, "Hub")
        StripItem(Icons.Outlined.MyLocation, gpsColor, null, "Location")
        Spacer(Modifier.weight(1f))
        Text(
            text = utcFmt.format(Date(now)) + " UTC",
            fontSize = 12.sp,
            fontFamily = PlexMono,
            color = MeshSatTextSecondary,
        )
    }
}

@Composable
private fun StripItem(icon: ImageVector, color: Color, value: String?, described: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = described },
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        if (value != null) {
            Text(text = value, fontSize = 12.sp, fontFamily = PlexMono, color = color)
        }
    }
}
