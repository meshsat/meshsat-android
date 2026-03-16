package com.cubeos.meshsat.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.engine.GeofenceEventRecord
import com.cubeos.meshsat.engine.GeofenceMonitor
import com.cubeos.meshsat.engine.GeofenceZone
import com.cubeos.meshsat.engine.LatLon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import com.cubeos.meshsat.ui.theme.ThemeState

/**
 * Geofence Map screen — draw/manage polygon zones on the map,
 * color-coded by alert mode, with enter/exit event log.
 */
@Composable
fun GeofenceScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val phoneLocation by GatewayService.phoneLocation.collectAsState()
    val nodes by db.nodePositionDao().getLatestPerNode().collectAsState(initial = emptyList())

    val geofenceMonitor = remember { GatewayService.geofenceMonitor }

    var zones by remember { mutableStateOf(geofenceMonitor?.getZones() ?: emptyList()) }
    var events by remember { mutableStateOf(geofenceMonitor?.getEvents() ?: emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedZone by remember { mutableStateOf<GeofenceZone?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Geofence Zones",
                style = MaterialTheme.typography.headlineMedium,
            )
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Add Zone", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Map with geofence overlays
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
        ) {
            val darkModePref by ThemeState.darkMode.collectAsState()
                val isDark = darkModePref ?: true
                GeofenceMap(
                    zones = zones,
                    phoneLocation = phoneLocation,
                    meshNodes = nodes.filter { it.nodeId != 0L },
                    darkMode = isDark,
                )
        }

        // Zone list
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (zones.isEmpty()) {
                Text(
                    text = "No geofence zones configured.\nTap \"Add Zone\" to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                zones.forEach { zone ->
                    ZoneCard(
                        zone = zone,
                        onClick = { selectedZone = zone },
                        onDelete = {
                            geofenceMonitor?.removeZone(zone.id)
                            zones = geofenceMonitor?.getZones() ?: emptyList()
                        },
                    )
                }
            }

            // Event log
            if (events.isNotEmpty()) {
                Text(
                    text = "Event Log",
                    style = MaterialTheme.typography.titleSmall,
                    color = MeshSatTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                events.take(20).forEach { ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MeshSatSurface, RoundedCornerShape(4.dp))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (ev.event == "enter") MeshSatRed else MeshSatAmber,
                                    CircleShape,
                                ),
                        )
                        Text(
                            text = "${ev.event.uppercase()} ${ev.zoneName}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = ev.nodeId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTeal,
                        )
                        Text(
                            text = timeFmt.format(Date(ev.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
            }
        }
    }

    // Refresh events when returning from add/delete
    events = geofenceMonitor?.getEvents() ?: emptyList()

    // Add zone dialog
    if (showAddDialog) {
        AddZoneDialog(
            phoneLocation = phoneLocation,
            onDismiss = { showAddDialog = false },
            onAdd = { zone ->
                geofenceMonitor?.addZone(zone)
                zones = geofenceMonitor?.getZones() ?: emptyList()
                showAddDialog = false
                Toast.makeText(context, "Zone '${zone.name}' added", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Zone detail dialog
    selectedZone?.let { zone ->
        ZoneDetailDialog(
            zone = zone,
            onDismiss = { selectedZone = null },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GeofenceMap(
    zones: List<GeofenceZone>,
    phoneLocation: android.location.Location?,
    meshNodes: List<com.cubeos.meshsat.data.NodePosition>,
    darkMode: Boolean = true,
) {
    val html = remember(zones, phoneLocation, meshNodes, darkMode) {
        buildGeofenceMapHtml(zones, phoneLocation, meshNodes, darkMode)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                webViewClient = WebViewClient()
                setBackgroundColor(android.graphics.Color.parseColor("#111827"))
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
    )
}

private fun buildGeofenceMapHtml(
    zones: List<GeofenceZone>,
    phoneLocation: android.location.Location?,
    meshNodes: List<com.cubeos.meshsat.data.NodePosition>,
    darkMode: Boolean = true,
): String {
    // Calculate center from all available positions
    val allLats = mutableListOf<Double>()
    val allLons = mutableListOf<Double>()
    phoneLocation?.let { allLats.add(it.latitude); allLons.add(it.longitude) }
    meshNodes.forEach { allLats.add(it.latitude); allLons.add(it.longitude) }
    zones.forEach { z -> z.polygon.forEach { allLats.add(it.lat); allLons.add(it.lon) } }

    val centerLat = if (allLats.isNotEmpty()) allLats.average() else 0.0
    val centerLon = if (allLons.isNotEmpty()) allLons.average() else 0.0

    // Zone polygons
    val zonePolygons = zones.joinToString("\n") { zone ->
        val coords = zone.polygon.joinToString(",") { "[${it.lat}, ${it.lon}]" }
        val color = when (zone.alertOn) {
            "enter" -> "#EF4444"
            "exit" -> "#F59E0B"
            "both" -> "#A855F7"
            else -> "#6B7280"
        }
        """
        L.polygon([$coords], {
            color: '$color', weight: 2, fillColor: '$color', fillOpacity: 0.15
        }).addTo(map).bindPopup('<b>${zone.name.replace("'", "\\'")}</b><br>Alert: ${zone.alertOn}${if (zone.message.isNotBlank()) "<br>${zone.message.replace("'", "\\'")}" else ""}');
        """.trimIndent()
    }

    // Phone marker
    val phoneMarker = if (phoneLocation != null) {
        """
        L.circleMarker([${"%.7f".format(phoneLocation.latitude)}, ${"%.7f".format(phoneLocation.longitude)}], {
            radius: 8, fillColor: '#3B82F6', color: '#1D4ED8', weight: 2, fillOpacity: 0.9
        }).addTo(map).bindPopup('<b>This Phone</b>');
        """.trimIndent()
    } else ""

    // Mesh node markers
    val nodeMarkers = meshNodes.joinToString("\n") { node ->
        """
        L.circleMarker([${"%.7f".format(node.latitude)}, ${"%.7f".format(node.longitude)}], {
            radius: 6, fillColor: '#06B6D4', color: '#0D9488', weight: 2, fillOpacity: 0.8
        }).addTo(map).bindPopup('<b>${node.nodeName.replace("'", "\\'")}</b>');
        """.trimIndent()
    }

    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>
            body { margin: 0; padding: 0; background: ${if (darkMode) "#111827" else "#F9FAFB"}; }
            #map { width: 100%; height: 100vh; }
            .leaflet-popup-content-wrapper { background: ${if (darkMode) "#1F2937" else "#FFFFFF"}; color: ${if (darkMode) "#E5E7EB" else "#111827"}; border-radius: 8px; }
            .leaflet-popup-tip { background: ${if (darkMode) "#1F2937" else "#FFFFFF"}; }
        </style>
    </head>
    <body>
        <div id="map"></div>
        <script>
            var map = L.map('map').setView([${"%.7f".format(centerLat)}, ${"%.7f".format(centerLon)}], 13);
            L.tileLayer('https://{s}.basemaps.cartocdn.com/${if (darkMode) "dark_all" else "light_all"}/{z}/{x}/{y}{r}.png', {
                maxZoom: 19,
            }).addTo(map);
            $phoneMarker
            $nodeMarkers
            $zonePolygons
        </script>
    </body>
    </html>
    """.trimIndent()
}

@Composable
private fun ZoneCard(
    zone: GeofenceZone,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(6.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Alert mode indicator
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(alertModeColor(zone.alertOn), CircleShape),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = zone.name,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Alert: ${zone.alertOn}",
                    style = MaterialTheme.typography.labelSmall,
                    color = alertModeColor(zone.alertOn),
                )
                Text(
                    text = "${zone.polygon.size} vertices",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }
            if (zone.message.isNotBlank()) {
                Text(
                    text = zone.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextSecondary,
                    maxLines = 1,
                )
            }
        }

        Text(
            text = "Delete",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatRed,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(4.dp),
        )
    }
}

@Composable
private fun AddZoneDialog(
    phoneLocation: android.location.Location?,
    onDismiss: () -> Unit,
    onAdd: (GeofenceZone) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var alertOn by remember { mutableStateOf("enter") }
    var message by remember { mutableStateOf("") }
    var radiusMeters by remember { mutableStateOf("200") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("Add Geofence Zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Zone name", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )

                OutlinedTextField(
                    value = radiusMeters,
                    onValueChange = { radiusMeters = it },
                    label = { Text("Radius (meters)", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )

                Text(
                    text = "Alert mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                val alertModes = listOf("enter", "exit", "both")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    alertModes.forEach { mode ->
                        Text(
                            text = mode,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (alertOn == mode) alertModeColor(mode) else MeshSatTextMuted,
                            modifier = Modifier
                                .background(
                                    if (alertOn == mode) alertModeColor(mode).copy(alpha = 0.15f)
                                    else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .border(
                                    1.dp,
                                    if (alertOn == mode) alertModeColor(mode) else MeshSatBorder,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { alertOn = mode }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Alert message (optional)", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTeal,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )

                if (phoneLocation == null) {
                    Text(
                        text = "GPS not available — zone will be centered at 0,0. Enable GPS for accurate placement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatAmber,
                    )
                } else {
                    Text(
                        text = "Zone will be centered at your current GPS position (%.5f, %.5f).".format(
                            phoneLocation.latitude, phoneLocation.longitude,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    val lat = phoneLocation?.latitude ?: 0.0
                    val lon = phoneLocation?.longitude ?: 0.0
                    val r = radiusMeters.toDoubleOrNull() ?: 200.0
                    val polygon = generateCircularPolygon(lat, lon, r, 16)
                    val zone = GeofenceZone(
                        id = "zone_${System.currentTimeMillis()}",
                        name = name.trim(),
                        polygon = polygon,
                        alertOn = alertOn,
                        message = message.trim(),
                    )
                    onAdd(zone)
                },
            ) {
                Text("Add", color = MeshSatTeal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MeshSatTextMuted)
            }
        },
    )
}

@Composable
private fun ZoneDetailDialog(
    zone: GeofenceZone,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text(zone.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Alert Mode", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                    Text(zone.alertOn, style = MaterialTheme.typography.bodySmall, color = alertModeColor(zone.alertOn))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Vertices", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                    Text(zone.polygon.size.toString(), style = MaterialTheme.typography.bodySmall)
                }
                if (zone.message.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Message", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        Text(zone.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    text = "ID: ${zone.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )

                Text(
                    text = "Polygon Coordinates",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                zone.polygon.forEachIndexed { i, pt ->
                    Text(
                        text = "${i + 1}. ${"%.6f".format(pt.lat)}, ${"%.6f".format(pt.lon)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MeshSatTextMuted)
            }
        },
    )
}

/**
 * Generate an approximate circular polygon from a center point and radius.
 * Uses the equirectangular approximation for small distances.
 */
private fun generateCircularPolygon(
    centerLat: Double,
    centerLon: Double,
    radiusMeters: Double,
    numPoints: Int,
): List<LatLon> {
    val points = mutableListOf<LatLon>()
    val earthRadius = 6371000.0 // meters

    for (i in 0 until numPoints) {
        val angle = 2.0 * Math.PI * i / numPoints
        val dLat = radiusMeters * Math.cos(angle) / earthRadius
        val dLon = radiusMeters * Math.sin(angle) / (earthRadius * Math.cos(Math.toRadians(centerLat)))
        points.add(LatLon(
            centerLat + Math.toDegrees(dLat),
            centerLon + Math.toDegrees(dLon),
        ))
    }

    return points
}

private fun alertModeColor(alertOn: String): Color = when (alertOn) {
    "enter" -> MeshSatRed
    "exit" -> MeshSatAmber
    "both" -> Color(0xFFA855F7) // purple
    else -> Color(0xFF6B7280)
}
