package com.cubeos.meshsat.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cubeos.meshsat.ble.MeshtasticProtocol
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.NodePosition
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatBlue
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.ThemeState
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.map.MBTilesManager
import com.cubeos.meshsat.map.MBTilesReader
import kotlinx.coroutines.flow.first
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val nodes by db.nodePositionDao().getLatestPerNode().collectAsState(initial = emptyList())
    val phoneLocation by GatewayService.phoneLocation.collectAsState()

    // Track line positions (all historical positions per node)
    var trackPositions by remember { mutableStateOf<List<NodePosition>>(emptyList()) }
    LaunchedEffect(Unit) {
        trackPositions = db.nodePositionDao().getAllRecentByNode(500)
    }

    // Offline map settings
    val settings = remember { SettingsRepository(context) }
    val offlineEnabled by settings.offlineMapEnabled.collectAsState(initial = true)
    val offlineFile by settings.offlineMapFile.collectAsState(initial = "")

    // Extract bundled world map from assets on first use
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MBTilesManager.ensureBundledMap(context)
        }
    }

    // Use selected file, or fall back to bundled world map
    val effectiveFile = offlineFile.ifBlank { MBTilesManager.BUNDLED_WORLD_MAP }
    val mbtilesReader = remember(offlineEnabled, effectiveFile) {
        if (offlineEnabled) MBTilesManager.getReader(context, effectiveFile)
        else null
    }

    // Filter out nodeId=0 (phone GPS stored by GatewayService)
    val meshNodes = nodes.filter { it.nodeId != 0L }

    // Layer toggles
    var showGps by remember { mutableStateOf(true) }
    var showMeshNodes by remember { mutableStateOf(true) }
    var showTracks by remember { mutableStateOf(true) }

    // Per-node visibility filters
    var visibleNodeIds by remember(meshNodes) {
        mutableStateOf(meshNodes.map { it.nodeId }.toSet())
    }

    // Filtered nodes for map display
    val filteredMeshNodes = if (showMeshNodes) meshNodes.filter { it.nodeId in visibleNodeIds } else emptyList()
    val filteredTrackPositions = if (showTracks) trackPositions.filter { it.nodeId in visibleNodeIds } else emptyList()
    val effectivePhoneLocation = if (showGps) phoneLocation else null

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

        // Map view using Leaflet in WebView — always show, even without positions
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
        ) {
            val darkModePref by ThemeState.darkMode.collectAsState()
            val isDark = darkModePref ?: true
            LeafletMap(
                nodes = filteredMeshNodes,
                phoneLocation = effectivePhoneLocation,
                darkMode = isDark,
                trackPositions = filteredTrackPositions,
                offlineMapEnabled = offlineEnabled,
                mbtilesReader = mbtilesReader,
                isVectorTiles = mbtilesReader?.isVector == true,
            )
        }

        // Layer controls + node list below map (scrollable)
        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
                // LAYERS card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MeshSatSurface, RoundedCornerShape(8.dp))
                        .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = "LAYERS",
                        style = MaterialTheme.typography.titleSmall,
                        color = MeshSatTeal,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    LayerToggleRow(
                        label = "GPS",
                        dotColor = MeshSatGreen,
                        checked = showGps,
                        onCheckedChange = { showGps = it },
                    )
                    LayerToggleRow(
                        label = "Mesh Nodes",
                        dotColor = ColorMesh,
                        checked = showMeshNodes,
                        onCheckedChange = { showMeshNodes = it },
                    )
                    LayerToggleRow(
                        label = "Tracks",
                        dotColor = MeshSatBlue,
                        checked = showTracks,
                        onCheckedChange = { showTracks = it },
                    )
                }

                // NODE FILTERS card (only if multiple mesh nodes)
                if (meshNodes.size > 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MeshSatSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "NODE FILTERS",
                                style = MaterialTheme.typography.titleSmall,
                                color = MeshSatTeal,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "Show all",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MeshSatTeal,
                                    modifier = Modifier.clickable {
                                        visibleNodeIds = meshNodes.map { it.nodeId }.toSet()
                                    },
                                )
                                Text(
                                    text = "Hide all",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MeshSatTextMuted,
                                    modifier = Modifier.clickable {
                                        visibleNodeIds = emptySet()
                                    },
                                )
                            }
                        }
                        meshNodes.forEach { node ->
                            val name = node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        visibleNodeIds = if (node.nodeId in visibleNodeIds) {
                                            visibleNodeIds - node.nodeId
                                        } else {
                                            visibleNodeIds + node.nodeId
                                        }
                                    },
                            ) {
                                Checkbox(
                                    checked = node.nodeId in visibleNodeIds,
                                    onCheckedChange = { checked ->
                                        visibleNodeIds = if (checked) {
                                            visibleNodeIds + node.nodeId
                                        } else {
                                            visibleNodeIds - node.nodeId
                                        }
                                    },
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ColorMesh,
                                )
                            }
                        }
                    }
                }

                // Phone GPS info
                if (showGps) {
                    phoneLocation?.let { loc ->
                        Text(
                            text = "Phone GPS",
                            style = MaterialTheme.typography.titleMedium,
                            color = MeshSatTeal,
                        )
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
                                Text(text = "This Phone", style = MaterialTheme.typography.bodyMedium, color = MeshSatTeal)
                                Text(
                                    text = "%.5f, %.5f  alt %dm".format(loc.latitude, loc.longitude, loc.altitude.toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MeshSatTextMuted,
                                )
                            }
                            Text(
                                text = "acc ${loc.accuracy.toInt()}m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeshSatTextMuted,
                            )
                        }
                    }
                }

                if (filteredMeshNodes.isNotEmpty()) {
                    Text(
                        text = "Mesh Nodes (${filteredMeshNodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    filteredMeshNodes.forEach { node ->
                        NodeRow(node)
                    }
                }
            }
        }
    }

/** Holds a mutable reader reference so the WebViewClient closure (created once) can access updates. */
private class TileReaderHolder(var reader: MBTilesReader? = null)

/** Minimal transparent 1x1 PNG returned for missing offline tiles. */
private val TRANSPARENT_PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
    0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
    0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(), 0x00, 0x00, 0x00, 0x0A,
    0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x62, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
    0xE5.toByte(), 0x27, 0xDE.toByte(), 0xFC.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
    0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LeafletMap(
    nodes: List<NodePosition>,
    phoneLocation: android.location.Location?,
    darkMode: Boolean = true,
    trackPositions: List<NodePosition> = emptyList(),
    offlineMapEnabled: Boolean = false,
    mbtilesReader: MBTilesReader? = null,
    isVectorTiles: Boolean = false,
) {
    val context = LocalContext.current
    val html = remember(nodes, phoneLocation, darkMode, trackPositions, offlineMapEnabled, isVectorTiles) {
        buildLeafletHtml(nodes, phoneLocation, darkMode, trackPositions, offlineMapEnabled, isVectorTiles)
    }
    val readerHolder = remember { TileReaderHolder() }
    readerHolder.reader = mbtilesReader

    AndroidView(
        factory = { ctx ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()

            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url
                        // Intercept offline tile requests: /mbtiles/{z}/{x}/{y}
                        if (url.host == "appassets.androidplatform.net" &&
                            url.path?.startsWith("/mbtiles/") == true
                        ) {
                            val parts = url.path!!.removePrefix("/mbtiles/").split("/")
                            if (parts.size == 3) {
                                val z = parts[0].toIntOrNull()
                                val x = parts[1].toIntOrNull()
                                val y = parts[2].toIntOrNull()
                                if (z != null && x != null && y != null) {
                                    val reader = readerHolder.reader
                                    if (reader != null) {
                                        val tile = reader.getTile(z, x, y)
                                        if (tile != null) {
                                            return WebResourceResponse(
                                                reader.mimeType, null,
                                                ByteArrayInputStream(tile),
                                            )
                                        }
                                    }
                                    // Missing tile — return transparent PNG so online layer shows through
                                    return WebResourceResponse(
                                        "image/png", null,
                                        ByteArrayInputStream(TRANSPARENT_PNG),
                                    )
                                }
                            }
                        }
                        return assetLoader.shouldInterceptRequest(request.url)
                    }
                }
                setBackgroundColor(android.graphics.Color.parseColor("#111827"))
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "https://appassets.androidplatform.net",
                html, "text/html", "UTF-8", null,
            )
        },
    )
}

private fun buildLeafletHtml(nodes: List<NodePosition>, phoneLocation: android.location.Location?, darkMode: Boolean = true, trackPositions: List<NodePosition> = emptyList(), offlineMapEnabled: Boolean = false, isVectorTiles: Boolean = false): String {
    // Calculate center from all available positions
    val allLats = mutableListOf<Double>()
    val allLons = mutableListOf<Double>()
    nodes.forEach { allLats.add(it.latitude); allLons.add(it.longitude) }
    phoneLocation?.let { allLats.add(it.latitude); allLons.add(it.longitude) }

    val centerLat = if (allLats.isNotEmpty()) allLats.average() else 0.0
    val centerLon = if (allLons.isNotEmpty()) allLons.average() else 0.0

    // Mesh node markers (teal)
    val nodeMarkers = nodes.joinToString("\n") { node ->
        val name = node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(node.timestamp))
        """
        L.circleMarker([${"%.7f".format(node.latitude)}, ${"%.7f".format(node.longitude)}], {
            radius: 8, fillColor: '#06B6D4', color: '#0D9488', weight: 2, fillOpacity: 0.8
        }).addTo(map).bindPopup('<b>${name.replace("'", "\\'")}</b><br>Alt: ${node.altitude}m<br>${time}');
        """.trimIndent()
    }

    // Track lines per node (dashed polylines)
    val nodeColors = listOf("#06B6D4", "#A855F7", "#F97316", "#22C55E", "#F59E0B", "#EF4444")
    val tracksByNode = trackPositions.groupBy { it.nodeId }
    val trackLines = tracksByNode.entries.mapIndexed { index, (_, positions) ->
        if (positions.size < 2) return@mapIndexed ""
        val color = nodeColors[index % nodeColors.size]
        val coords = positions.joinToString(", ") { "[%.7f, %.7f]".format(it.latitude, it.longitude) }
        """
        L.polyline([$coords], {
            color: '$color', weight: 2, opacity: 0.6, dashArray: '6, 4'
        }).addTo(map);
        """.trimIndent()
    }.joinToString("\n")

    // Phone GPS marker (blue, pulsing)
    val phoneMarker = if (phoneLocation != null) {
        """
        L.circleMarker([${"%.7f".format(phoneLocation.latitude)}, ${"%.7f".format(phoneLocation.longitude)}], {
            radius: 10, fillColor: '#3B82F6', color: '#1D4ED8', weight: 3, fillOpacity: 0.9
        }).addTo(map).bindPopup('<b>This Phone</b><br>Alt: ${phoneLocation.altitude.toInt()}m<br>Acc: ${phoneLocation.accuracy.toInt()}m');
        L.circle([${"%.7f".format(phoneLocation.latitude)}, ${"%.7f".format(phoneLocation.longitude)}], {
            radius: ${phoneLocation.accuracy.toInt()}, fillColor: '#3B82F6', color: '#3B82F6', weight: 1, fillOpacity: 0.1, opacity: 0.3
        }).addTo(map);
        """.trimIndent()
    } else ""

    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <link rel="stylesheet" href="https://appassets.androidplatform.net/assets/leaflet.css" />
        <script src="https://appassets.androidplatform.net/assets/leaflet.js"></script>
        ${if (isVectorTiles) """
        <script src="https://appassets.androidplatform.net/assets/vectorgrid.js"></script>
        <script src="https://appassets.androidplatform.net/assets/map-style.js"></script>
        """ else ""}
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
            ${if (offlineMapEnabled && isVectorTiles) """
            // Vector tile layer from MBTiles (OpenMapTiles schema)
            var vectorStyle = meshsatVectorStyle('${if (darkMode) "dark" else "light"}');
            L.vectorGrid.protobuf('https://appassets.androidplatform.net/mbtiles/{z}/{x}/{y}', {
                vectorTileLayerStyles: vectorStyle,
                maxZoom: 19,
                rendererFactory: L.canvas.tile,
            }).addTo(map);
            """ else if (offlineMapEnabled) """
            // Online CDN as base fallback
            L.tileLayer('https://{s}.basemaps.cartocdn.com/${if (darkMode) "dark_all" else "light_all"}/{z}/{x}/{y}{r}.png', {
                maxZoom: 19,
            }).addTo(map);
            // Offline raster MBTiles layer on top
            L.tileLayer('https://appassets.androidplatform.net/mbtiles/{z}/{x}/{y}', {
                maxZoom: 19,
            }).addTo(map);
            """ else """
            L.tileLayer('https://{s}.basemaps.cartocdn.com/${if (darkMode) "dark_all" else "light_all"}/{z}/{x}/{y}{r}.png', {
                maxZoom: 19,
            }).addTo(map);
            """}
            $phoneMarker
            $nodeMarkers
            $trackLines
            var bounds = L.latLngBounds([]);
            map.eachLayer(function(layer) {
                if (layer.getLatLng) bounds.extend(layer.getLatLng());
                else if (layer.getBounds) try { bounds.extend(layer.getBounds()); } catch(e) {}
            });
            if (bounds.isValid()) { map.fitBounds(bounds, {padding: [30, 30]}); }
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

@Composable
private fun LayerToggleRow(
    label: String,
    dotColor: androidx.compose.ui.graphics.Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
