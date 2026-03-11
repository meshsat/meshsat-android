package com.cubeos.meshsat.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cubeos.meshsat.ble.MeshtasticProtocol
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.NodePosition
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val nodes by db.nodePositionDao().getLatestPerNode().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Node Map",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No node positions received yet.\nConnect to a Meshtastic radio to see nodes.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            // Map view using Leaflet in WebView
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
            ) {
                LeafletMap(nodes = nodes)
            }

            // Node list below map
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Nodes (${nodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                nodes.forEach { node ->
                    NodeRow(node)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LeafletMap(nodes: List<NodePosition>) {
    val html = remember(nodes) { buildLeafletHtml(nodes) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                setBackgroundColor(android.graphics.Color.parseColor("#111827"))
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
    )
}

private fun buildLeafletHtml(nodes: List<NodePosition>): String {
    val centerLat = nodes.map { it.latitude }.average()
    val centerLon = nodes.map { it.longitude }.average()

    val markers = nodes.joinToString("\n") { node ->
        val name = node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(node.timestamp))
        """
        L.circleMarker([${"%.7f".format(node.latitude)}, ${"%.7f".format(node.longitude)}], {
            radius: 8, fillColor: '#06B6D4', color: '#0D9488', weight: 2, fillOpacity: 0.8
        }).addTo(map).bindPopup('<b>${name.replace("'", "\\'")}</b><br>Alt: ${node.altitude}m<br>${time}');
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
            body { margin: 0; padding: 0; background: #111827; }
            #map { width: 100%; height: 100vh; }
            .leaflet-popup-content-wrapper { background: #1F2937; color: #E5E7EB; border-radius: 8px; }
            .leaflet-popup-tip { background: #1F2937; }
        </style>
    </head>
    <body>
        <div id="map"></div>
        <script>
            var map = L.map('map').setView([${"%.7f".format(centerLat)}, ${"%.7f".format(centerLon)}], 13);
            L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                maxZoom: 19,
            }).addTo(map);
            $markers
        </script>
    </body>
    </html>
    """.trimIndent()
}

@Composable
private fun NodeRow(node: NodePosition) {
    val name = node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(node.timestamp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(6.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = name, style = MaterialTheme.typography.bodyMedium, color = ColorMesh)
            Text(
                text = "%.5f, %.5f  alt %dm".format(node.latitude, node.longitude, node.altitude),
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }
        Text(text = timeStr, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
    }
}
