package com.cubeos.meshsat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.screens.AboutScreen
import com.cubeos.meshsat.ui.screens.AuditScreen
import com.cubeos.meshsat.ui.screens.DashboardScreen
import com.cubeos.meshsat.ui.screens.DeliveryScreen
import com.cubeos.meshsat.ui.screens.GeofenceScreen
import com.cubeos.meshsat.ui.screens.InterfacesScreen
import com.cubeos.meshsat.ui.screens.MapScreen
import com.cubeos.meshsat.ui.screens.MessagesScreen
import com.cubeos.meshsat.ui.screens.MoreScreen
import com.cubeos.meshsat.ui.screens.RulesScreen
import com.cubeos.meshsat.ui.screens.SettingsScreen
import com.cubeos.meshsat.ui.screens.PassPredictorScreen
import com.cubeos.meshsat.ui.screens.PeersScreen
import com.cubeos.meshsat.ui.screens.RadioConfigScreen
import com.cubeos.meshsat.ui.screens.TopologyScreen
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatBg
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Home", Icons.Default.Dashboard),
    Messages("messages", "Messages", Icons.AutoMirrored.Filled.Message),
    Map("map", "Map", Icons.Default.Map),
    Passes("passes", "Passes", Icons.Default.DateRange),
    More("more", "More", Icons.Default.Menu),
}

@Composable
fun MeshSatUI() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = MeshSatBg,
        topBar = { StatusBar() },
        bottomBar = {
            NavigationBar(containerColor = MeshSatSurface) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MeshSatTeal,
                            selectedTextColor = MeshSatTeal,
                            unselectedIconColor = MeshSatTextMuted,
                            unselectedTextColor = MeshSatTextMuted,
                            indicatorColor = MeshSatTeal.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") { DashboardScreen() }
            composable("messages") { MessagesScreen() }
            composable("map") { MapScreen() }
            composable("rules") { RulesScreen() }
            composable("settings") { SettingsScreen(navController) }
            composable("more") { MoreScreen(navController) }
            composable("about") { AboutScreen() }
            composable("topology") { TopologyScreen() }
            composable("deliveries") { DeliveryScreen() }
            composable("geofence") { GeofenceScreen() }
            composable("interfaces") { InterfacesScreen() }
            composable("radio-config") { RadioConfigScreen() }
            composable("passes") { PassPredictorScreen() }
            composable("peers") { PeersScreen() }
            composable("audit") { AuditScreen() }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Live Status Bar — compact top bar mirroring the Pi dashboard status
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun StatusBar() {
    // Iridium state
    val irdState = GatewayService.iridiumSpp?.state?.collectAsState()
    val irdSignal = GatewayService.iridiumSpp?.signal?.collectAsState()
    val irdConnected = irdState?.value == IridiumSpp.State.Connected
    val irdBars = irdSignal?.value ?: 0

    // Mesh state
    val meshState = GatewayService.meshtasticBle?.state?.collectAsState()
    val meshNodes = GatewayService.meshtasticBle?.nodes?.collectAsState()
    val meshRssi = GatewayService.meshtasticBle?.rssi?.collectAsState()
    val meshConnected = meshState?.value == MeshtasticBle.State.Connected
    val nodeCount = meshNodes?.value?.size ?: 0
    val rssi = meshRssi?.value ?: 0

    // GPS state
    val gpsLocation = GatewayService.phoneLocation.collectAsState()
    val hasGps = gpsLocation.value != null

    // Clock — updates every second
    var utcTime by remember { mutableStateOf("") }
    val utcFmt = remember {
        SimpleDateFormat("HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            utcTime = utcFmt.format(Date()) + "Z"
            delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MeshSatSurface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // IRD section: signal bars + dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "IRD",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ColorIridium,
            )
            Text(
                text = signalBars(irdBars),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (irdConnected) ColorIridium else MeshSatTextMuted,
            )
            StatusDot(if (irdConnected) ColorIridium else MeshSatTextMuted)
        }

        // MESH section: dot + RSSI/SNR + node count
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "MESH",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ColorMesh,
            )
            StatusDot(if (meshConnected) ColorMesh else MeshSatTextMuted)
            if (meshConnected) {
                val rssiText = if (rssi != 0) "${rssi}dB" else "---"
                Text(
                    text = "$rssiText $nodeCount",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MeshSatTextSecondary,
                )
            } else {
                Text(
                    text = "---",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MeshSatTextMuted,
                )
            }
        }

        // CELL section: always available on Android
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "CELL",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ColorCellular,
            )
            StatusDot(ColorCellular)
        }

        // GPS section
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "GPS",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (hasGps) MeshSatGreen else MeshSatTextMuted,
            )
            StatusDot(if (hasGps) MeshSatGreen else MeshSatTextMuted)
        }

        Spacer(modifier = Modifier.weight(1f))

        // UTC clock
        Text(
            text = utcTime,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MeshSatTextSecondary,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color, CircleShape),
    )
}

private fun signalBars(level: Int): String {
    val filled = level.coerceIn(0, 5)
    val empty = 5 - filled
    return "\u25A0".repeat(filled) + "\u25A1".repeat(empty)
}
