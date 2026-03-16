package com.cubeos.meshsat.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cubeos.meshsat.ui.screens.AboutScreen
import com.cubeos.meshsat.ui.screens.DashboardScreen
import com.cubeos.meshsat.ui.screens.DeliveryScreen
import com.cubeos.meshsat.ui.screens.GeofenceScreen
import com.cubeos.meshsat.ui.screens.InterfacesScreen
import com.cubeos.meshsat.ui.screens.MapScreen
import com.cubeos.meshsat.ui.screens.MessagesScreen
import com.cubeos.meshsat.ui.screens.RulesScreen
import com.cubeos.meshsat.ui.screens.SettingsScreen
import com.cubeos.meshsat.ui.screens.RadioConfigScreen
import com.cubeos.meshsat.ui.screens.TopologyScreen
import com.cubeos.meshsat.ui.theme.MeshSatBg
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Dashboard", Icons.Default.Dashboard),
    Messages("messages", "Messages", Icons.AutoMirrored.Filled.Message),
    Map("map", "Map", Icons.Default.Map),
    Rules("rules", "Rules", Icons.Default.SwapHoriz),
    Settings("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun MeshSatUI() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = MeshSatBg,
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
            composable("about") { AboutScreen() }
            composable("topology") { TopologyScreen() }
            composable("deliveries") { DeliveryScreen() }
            composable("geofence") { GeofenceScreen() }
            composable("interfaces") { InterfacesScreen() }
            composable("radio-config") { RadioConfigScreen() }
        }
    }
}
