package com.cubeos.meshsat.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
import com.cubeos.meshsat.ui.screens.ConversationsScreen
import com.cubeos.meshsat.ui.screens.DashboardScreen
import com.cubeos.meshsat.ui.screens.DecryptScreen
import com.cubeos.meshsat.ui.screens.MapScreen
import com.cubeos.meshsat.ui.screens.MessagesScreen
import com.cubeos.meshsat.ui.screens.RulesScreen
import com.cubeos.meshsat.ui.screens.SOSScreen
import com.cubeos.meshsat.ui.screens.SettingsScreen
import com.cubeos.meshsat.ui.theme.MeshSatBg
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextPrimary

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Home", Icons.Default.Dashboard),
    Conversations("conversations", "Chat", Icons.Default.ChatBubble),
    Map("map", "Map", Icons.Default.Map),
    SOS("sos", "SOS", Icons.Default.Warning),
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
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                tint = if (tab == Tab.SOS) MeshSatRed.copy(
                                    alpha = if (currentRoute == tab.route) 1f else 0.6f
                                ) else androidx.compose.ui.graphics.Color.Unspecified,
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                color = if (tab == Tab.SOS) MeshSatRed
                                        else androidx.compose.ui.graphics.Color.Unspecified,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MeshSatTeal,
                            selectedTextColor = MeshSatTeal,
                            unselectedIconColor = MeshSatTextMuted,
                            unselectedTextColor = MeshSatTextMuted,
                            indicatorColor = if (tab == Tab.SOS) MeshSatRed.copy(alpha = 0.15f)
                                             else MeshSatTeal.copy(alpha = 0.15f),
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
            composable("conversations") { ConversationsScreen() }
            composable("map") { MapScreen() }
            composable("sos") { SOSScreen() }
            composable("settings") { SettingsScreen(navController) }
            // Sub-routes accessible from settings/dashboard
            composable("messages") { MessagesScreen() }
            composable("rules") { RulesScreen() }
            composable("decrypt") { DecryptScreen() }
            composable("about") { AboutScreen() }
        }
    }
}
