package com.cubeos.meshsat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary

@Composable
fun MoreScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Features",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // Gateway
        SectionHeader("Gateway")
        FeatureItem("Bridge Rules", "Message routing rules", navController, "rules")
        FeatureItem("Interfaces", "Transport status & health", navController, "interfaces")
        FeatureItem("Deliveries", "Message delivery tracking", navController, "deliveries")

        // Field Ops
        SectionHeader("Field Operations")
        FeatureItem("Mesh Topology", "Node graph with battery & status", navController, "topology")
        FeatureItem("Geofence Zones", "Enter/exit alerts on map", navController, "geofence")
        FeatureItem("Audit Log", "Event trail & chain verify", navController, "audit")

        // Radio
        SectionHeader("Radio")
        FeatureItem("Radio Config", "LoRa, TX power, device admin", navController, "radio-config")

        // App
        SectionHeader("App")
        FeatureItem("Settings", "Theme, encryption, compression", navController, "settings")
        FeatureItem("About", "Version, license, transports", navController, "about")
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MeshSatTeal,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun FeatureItem(title: String, subtitle: String, navController: NavController, route: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .clickable { navController.navigate(route) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        }
        Text(text = "\u203A", style = MaterialTheme.typography.titleLarge, color = MeshSatTextSecondary)
    }
}
