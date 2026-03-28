package com.cubeos.meshsat.ui.screens

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
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
import androidx.compose.runtime.DisposableEffect
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
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.map.MBTilesManager
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatBlue
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.ThemeState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Track color cycle for per-node polylines. */
private val TRACK_COLORS = intArrayOf(
    0xFF06B6D4.toInt(), 0xFFA855F7.toInt(), 0xFFF97316.toInt(),
    0xFF22C55E.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(),
)

/** Tracks the current MBTiles file to avoid re-creating tile provider unnecessarily. */
private class TileState(var configuredFile: String? = null)

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val nodes by db.nodePositionDao().getLatestPerNode().collectAsState(initial = emptyList())
    val phoneLocation by GatewayService.phoneLocation.collectAsState()

    var trackPositions by remember { mutableStateOf<List<NodePosition>>(emptyList()) }
    LaunchedEffect(Unit) {
        trackPositions = db.nodePositionDao().getAllRecentByNode(500)
    }

    val settings = remember { SettingsRepository(context) }
    val offlineEnabled by settings.offlineMapEnabled.collectAsState(initial = true)
    val offlineFile by settings.offlineMapFile.collectAsState(initial = "")

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MBTilesManager.ensureBundledMap(context)
        }
    }

    val effectiveFile = offlineFile.ifBlank { MBTilesManager.BUNDLED_WORLD_MAP }
    val mbtilesFile = remember(offlineEnabled, effectiveFile) {
        if (offlineEnabled) MBTilesManager.getMBTilesFile(context, effectiveFile) else null
    }

    val meshNodes = nodes.filter { it.nodeId != 0L }

    var showGps by remember { mutableStateOf(true) }
    var showMeshNodes by remember { mutableStateOf(true) }
    var showTracks by remember { mutableStateOf(true) }

    var visibleNodeIds by remember(meshNodes) {
        mutableStateOf(meshNodes.map { it.nodeId }.toSet())
    }

    val filteredMeshNodes = if (showMeshNodes) meshNodes.filter { it.nodeId in visibleNodeIds } else emptyList()
    val filteredTrackPositions = if (showTracks) trackPositions.filter { it.nodeId in visibleNodeIds } else emptyList()
    val effectivePhoneLocation = if (showGps) phoneLocation else null

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            text = "Node Map",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
        ) {
            val darkModePref by ThemeState.darkMode.collectAsState()
            val isDark = darkModePref ?: true
            OsmdroidMap(
                nodes = filteredMeshNodes,
                phoneLocation = effectivePhoneLocation,
                trackPositions = filteredTrackPositions,
                mbtilesFile = mbtilesFile,
                offlineEnabled = offlineEnabled,
                darkMode = isDark,
            )
        }

        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(MeshSatSurface, RoundedCornerShape(8.dp))
                        .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text("LAYERS", style = MaterialTheme.typography.titleSmall, color = MeshSatTeal,
                        modifier = Modifier.padding(bottom = 4.dp))
                    LayerToggleRow("GPS", MeshSatGreen, showGps) { showGps = it }
                    LayerToggleRow("Mesh Nodes", ColorMesh, showMeshNodes) { showMeshNodes = it }
                    LayerToggleRow("Tracks", MeshSatBlue, showTracks) { showTracks = it }
                }

                if (meshNodes.size > 1) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MeshSatSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("NODE FILTERS", style = MaterialTheme.typography.titleSmall, color = MeshSatTeal)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Show all", style = MaterialTheme.typography.bodySmall, color = MeshSatTeal,
                                    modifier = Modifier.clickable { visibleNodeIds = meshNodes.map { it.nodeId }.toSet() })
                                Text("Hide all", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted,
                                    modifier = Modifier.clickable { visibleNodeIds = emptySet() })
                            }
                        }
                        meshNodes.forEach { node ->
                            val name = node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    visibleNodeIds = if (node.nodeId in visibleNodeIds)
                                        visibleNodeIds - node.nodeId else visibleNodeIds + node.nodeId
                                },
                            ) {
                                Checkbox(checked = node.nodeId in visibleNodeIds, onCheckedChange = { checked ->
                                    visibleNodeIds = if (checked) visibleNodeIds + node.nodeId else visibleNodeIds - node.nodeId
                                })
                                Text(name, style = MaterialTheme.typography.bodyMedium, color = ColorMesh)
                            }
                        }
                    }
                }

                if (showGps) {
                    phoneLocation?.let { loc ->
                        Text("Phone GPS", style = MaterialTheme.typography.titleMedium, color = MeshSatTeal)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(MeshSatSurface, RoundedCornerShape(6.dp))
                                .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("This Phone", style = MaterialTheme.typography.bodyMedium, color = MeshSatTeal)
                                Text("%.5f, %.5f  alt %dm".format(loc.latitude, loc.longitude, loc.altitude.toInt()),
                                    style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                            }
                            Text("acc ${loc.accuracy.toInt()}m", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        }
                    }
                }

                if (filteredMeshNodes.isNotEmpty()) {
                    Text("Mesh Nodes (${filteredMeshNodes.size})", style = MaterialTheme.typography.titleMedium)
                    filteredMeshNodes.forEach { node -> NodeRow(node) }
                }
            }
        }
    }

// ============================================================================
// osmdroid Native Map — singleton MapView pattern
// ============================================================================

/** Dark mode color inversion matrix. */
private val DARK_INVERT = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)))

@Composable
private fun OsmdroidMap(
    nodes: List<NodePosition>,
    phoneLocation: android.location.Location?,
    trackPositions: List<NodePosition>,
    mbtilesFile: java.io.File?,
    offlineEnabled: Boolean,
    darkMode: Boolean = true,
) {
    val context = LocalContext.current
    val tileState = remember { TileState() }

    // MapView is created once and NEVER destroyed (lives outside NavHost)
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = "MeshSat-Android"
            osmdroidBasePath = java.io.File(context.filesDir, "osmdroid")
            osmdroidTileCache = java.io.File(context.filesDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setDestroyMode(false)
            setMultiTouchControls(true)
            clipToOutline = true
            setBackgroundColor(android.graphics.Color.parseColor("#111827"))
            setTileSource(TileSourceFactory.MAPNIK)
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
            overlayManager.tilesOverlay.loadingBackgroundColor =
                android.graphics.Color.parseColor("#111827")
            overlayManager.tilesOverlay.loadingLineColor =
                android.graphics.Color.parseColor("#1F2937")
        }
    }

    // Wire lifecycle for onResume/onPause
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { mapView },
        update = { mv ->
            // Configure offline tile provider only when file changes
            if (offlineEnabled && mbtilesFile != null) {
                val newFile = mbtilesFile.absolutePath
                if (newFile != tileState.configuredFile) {
                    tileState.configuredFile = newFile
                    try {
                        val archives = org.osmdroid.tileprovider.modules.ArchiveFileFactory
                            .getArchiveFile(mbtilesFile)
                        if (archives != null) {
                            val tileSource = XYTileSource("MBTiles", 0, 19, 256, ".png", arrayOf())
                            val receiver = SimpleRegisterReceiver(mv.context)
                            val archiveProvider = MapTileFileArchiveProvider(receiver, tileSource, arrayOf(archives))
                            mv.tileProvider = MapTileProviderArray(tileSource, receiver, arrayOf(archiveProvider))
                        }
                    } catch (_: Exception) {
                        mv.setTileSource(TileSourceFactory.MAPNIK)
                    }
                }
            } else if (!offlineEnabled && tileState.configuredFile != null) {
                tileState.configuredFile = null
                mv.setTileSource(TileSourceFactory.MAPNIK)
            }

            // Dark mode
            mv.overlayManager.tilesOverlay.setColorFilter(if (darkMode) DARK_INVERT else null)

            // Rebuild marker/track overlays (keep tiles overlay untouched)
            val nonTileOverlays = mv.overlays.filterIsInstance<Marker>() +
                mv.overlays.filterIsInstance<Polyline>() +
                mv.overlays.filterIsInstance<Polygon>()
            nonTileOverlays.forEach { mv.overlays.remove(it) }

            // Track lines
            val tracksByNode = trackPositions.groupBy { it.nodeId }
            tracksByNode.entries.forEachIndexed { index, (_, positions) ->
                if (positions.size >= 2) {
                    val line = Polyline(mv)
                    line.outlinePaint.color = TRACK_COLORS[index % TRACK_COLORS.size]
                    line.outlinePaint.strokeWidth = 4f
                    line.outlinePaint.style = Paint.Style.STROKE
                    line.outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
                    line.outlinePaint.isAntiAlias = true
                    line.setPoints(positions.map { GeoPoint(it.latitude, it.longitude) })
                    line.isGeodesic = false
                    mv.overlays.add(line)
                }
            }

            // Mesh node markers
            nodes.forEach { node ->
                val marker = Marker(mv)
                marker.position = GeoPoint(node.latitude, node.longitude)
                val name = node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(node.timestamp))
                marker.title = name
                marker.snippet = "Alt: ${node.altitude}m  ${time}"
                marker.icon = createDotDrawable(mv, 0xFF06B6D4.toInt(), 0xFF0D9488.toInt(), 16)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mv.overlays.add(marker)
            }

            // Phone GPS marker + accuracy circle
            if (phoneLocation != null) {
                val circle = Polygon(mv)
                circle.points = Polygon.pointsAsCircle(
                    GeoPoint(phoneLocation.latitude, phoneLocation.longitude),
                    phoneLocation.accuracy.toDouble(),
                )
                circle.fillPaint.color = 0x1A3B82F6
                circle.outlinePaint.color = 0x4D3B82F6
                circle.outlinePaint.strokeWidth = 2f
                mv.overlays.add(circle)

                val phoneMarker = Marker(mv)
                phoneMarker.position = GeoPoint(phoneLocation.latitude, phoneLocation.longitude)
                phoneMarker.title = "This Phone"
                phoneMarker.snippet = "Alt: ${phoneLocation.altitude.toInt()}m  Acc: ${phoneLocation.accuracy.toInt()}m"
                phoneMarker.icon = createDotDrawable(mv, 0xFF3B82F6.toInt(), 0xFF1D4ED8.toInt(), 20)
                phoneMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mv.overlays.add(phoneMarker)
            }

            // Auto-fit bounds
            val allPoints = mutableListOf<GeoPoint>()
            nodes.forEach { allPoints.add(GeoPoint(it.latitude, it.longitude)) }
            phoneLocation?.let { allPoints.add(GeoPoint(it.latitude, it.longitude)) }
            if (allPoints.size >= 2) {
                val bbox = BoundingBox.fromGeoPoints(allPoints)
                mv.post { mv.zoomToBoundingBox(bbox, true, 60) }
            } else if (allPoints.size == 1) {
                mv.controller.setCenter(allPoints[0])
                mv.controller.setZoom(13.0)
            }

            mv.invalidate()
        },
    )
}

private fun createDotDrawable(mapView: MapView, fillColor: Int, strokeColor: Int, sizeDp: Int): BitmapDrawable {
    val density = mapView.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = sizePx / 2f
    val r = sizePx / 2f - 2 * density
    paint.style = Paint.Style.FILL; paint.color = fillColor
    canvas.drawCircle(cx, cx, r, paint)
    paint.style = Paint.Style.STROKE; paint.color = strokeColor; paint.strokeWidth = 2 * density
    canvas.drawCircle(cx, cx, r, paint)
    return BitmapDrawable(mapView.resources, bitmap)
}

// ============================================================================
// Shared UI Components
// ============================================================================

@Composable
private fun NodeRow(node: NodePosition) {
    val name = node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(node.timestamp))
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(6.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = ColorMesh)
            Text("%.5f, %.5f  alt %dm".format(node.latitude, node.longitude, node.altitude),
                style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        }
        Text(timeStr, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
    }
}

@Composable
private fun LayerToggleRow(
    label: String, dotColor: androidx.compose.ui.graphics.Color,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
