package net.meshsat.android.ui.screens

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.location.Location
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.NodePosition
import net.meshsat.android.map.MapTiles
import net.meshsat.android.map.loadRecentTracks
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.components.MapButton
import net.meshsat.android.ui.components.MapLifecycleEffect
import net.meshsat.android.ui.components.MapStatusNote
import net.meshsat.android.ui.components.MapTilesEffect
import net.meshsat.android.ui.components.meshNodeNames
import net.meshsat.android.ui.components.positionLabel
import net.meshsat.android.ui.components.rememberDetailedMap
import net.meshsat.android.ui.components.rememberMarkerPainter
import net.meshsat.android.ui.components.showPoints
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/** A node not heard from for this long is drawn faded; People and Topology use the same 15 minutes. */
private const val STALE_AFTER_MS = 15 * 60_000L

/** How often tracks are read again while the map is on screen (B17: they were read once per process). */
private const val TRACK_REFRESH_MS = 30_000L

/** How far back a track reaches, and how many points all tracks together may hold. */
private const val TRACK_WINDOW_MS = 24 * 60 * 60_000L
private const val TRACK_MAX_POINTS = 5_000

/**
 * The Map tab. It lives outside the NavHost (MeshSatUI) and is never destroyed, so the osmdroid view
 * is made once and kept; [visible] says whether it is on screen, so tracks are only re-read while it
 * is. The map takes the space; layers and the node list sit in a panel below that opens to at most
 * half the height, so a long node list can no longer squeeze the map to nothing.
 */
@OptIn(FlowPreview::class)
@Composable
fun MapScreen(visible: Boolean = true) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    // Debounced: a burst of TAK position inserts would otherwise rebuild the markers many times over.
    val nodes by remember { db.nodePositionDao().getLatestPerNode().debounce(500) }
        .collectAsState(initial = emptyList())
    val phoneLocation by GatewayService.phoneLocation.collectAsState()
    val detailed = rememberDetailedMap()

    var tracks by remember { mutableStateOf<List<NodePosition>>(emptyList()) }
    var names by remember { mutableStateOf(meshNodeNames()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(visible, lifecycle) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    tracks = loadRecentTracks(db, System.currentTimeMillis() - TRACK_WINDOW_MS, TRACK_MAX_POINTS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("MapScreen", "Could not read tracks: ${e.message}")
                }
                names = meshNodeNames()
                delay(TRACK_REFRESH_MS)
            }
        }
    }
    LaunchedEffect(nodes) { names = meshNodeNames() }
    // Staleness moves on with the clock, not only when a position arrives.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    val mapNodes = remember(nodes) { nodes.filter { it.nodeId != 0L } }

    var showPhone by remember { mutableStateOf(true) }
    var showNodes by remember { mutableStateOf(true) }
    var showTracks by remember { mutableStateOf(true) }
    // Hidden rather than shown, so a node that appears later is on the map by default and a new
    // position no longer resets the user's choices.
    var hidden by remember { mutableStateOf(emptySet<Long>()) }
    var panelOpen by remember { mutableStateOf(false) }

    val shownNodes = remember(mapNodes, hidden, showNodes) {
        if (showNodes) mapNodes.filter { it.nodeId !in hidden } else emptyList()
    }
    val shownTracks = remember(tracks, hidden, showTracks) {
        if (showTracks) tracks.filter { it.nodeId !in hidden } else emptyList()
    }
    val shownPhone = if (showPhone) phoneLocation else null

    // Made once and never destroyed: MapScreen stays composed outside the NavHost.
    val mapView = remember {
        MapTiles.newMapView(context).apply {
            setDestroyMode(false)
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }
    MapLifecycleEffect(mapView)
    MapTilesEffect(mapView, detailed)
    val painter = rememberMarkerPainter(mapView)
    val density = LocalDensity.current.density

    // Three layers, bottom to top: tracks, nodes, this phone. Each is refilled on its own, so a
    // phone fix no longer rebuilds every node marker and track.
    val trackLayer = remember { FolderOverlay().also { mapView.overlays.add(it) } }
    val nodeLayer = remember { FolderOverlay().also { mapView.overlays.add(it) } }
    val phoneLayer = remember { FolderOverlay().also { mapView.overlays.add(it) } }
    val nodeMarkers = remember { HashMap<Long, Marker>() }
    val accuracyCircle = remember {
        Polygon(mapView).also { circle ->
            circle.fillPaint.color = MeshSatTeal.copy(alpha = 0.12f).toArgb()
            circle.outlinePaint.color = MeshSatTeal.copy(alpha = 0.5f).toArgb()
            circle.outlinePaint.strokeWidth = 1.5f * density
            // A tap inside the accuracy circle is a tap on the map, not on an empty bubble.
            circle.setOnClickListener { _, _, _ -> false }
            circle.setVisible(false)
            phoneLayer.add(circle)
        }
    }
    val phoneMarker = remember {
        Marker(mapView).also { marker ->
            marker.title = "This phone"
            marker.setVisible(false)
            phoneLayer.add(marker)
        }
    }

    LaunchedEffect(shownTracks, shownNodes, names) {
        trackLayer.items.toList().forEach { trackLayer.remove(it) }
        val latest = shownNodes.associateBy { it.nodeId }
        shownTracks.groupBy { it.nodeId }.forEach { (id, points) ->
            val path = points.map { GeoPoint(it.latitude, it.longitude) }.toMutableList()
            // Tracks are re-read every 30 s; the newest position joins the line straight away.
            val last = latest[id]
            if (last != null && last.timestamp > points.last().timestamp) {
                path.add(GeoPoint(last.latitude, last.longitude))
            }
            if (path.size < 2) return@forEach
            val line = Polyline(mapView)
            line.outlinePaint.color = ColorMesh.copy(alpha = 0.75f).toArgb()
            line.outlinePaint.strokeWidth = 3f * density
            line.outlinePaint.style = Paint.Style.STROKE
            line.outlinePaint.strokeCap = Paint.Cap.ROUND
            line.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
            line.outlinePaint.isAntiAlias = true
            line.isGeodesic = false
            line.setPoints(path)
            line.title = "Track of " + positionLabel(last ?: points.last(), names)
            trackLayer.add(line)
        }
        mapView.invalidate()
    }

    LaunchedEffect(shownNodes, names, painter, now) {
        val current = System.currentTimeMillis()
        val keep = shownNodes.mapTo(HashSet()) { it.nodeId }
        nodeMarkers.keys.filter { it !in keep }.forEach { id ->
            nodeMarkers.remove(id)?.let { gone ->
                gone.closeInfoWindow()
                nodeLayer.remove(gone)
            }
        }
        shownNodes.forEach { node ->
            val label = positionLabel(node, names)
            val stale = current - node.timestamp > STALE_AFTER_MS
            // Markers are kept per node, so an open bubble stays with its node when it moves.
            val marker = nodeMarkers.getOrPut(node.nodeId) { Marker(mapView).also { nodeLayer.add(it) } }
            marker.position = GeoPoint(node.latitude, node.longitude)
            marker.title = label
            marker.snippet = heardLine(node.timestamp, current) +
                if (node.altitude != 0) ", altitude ${node.altitude} m" else ""
            painter.node(label, ColorMesh.toArgb(), stale).applyTo(marker)
        }
        mapView.invalidate()
    }

    LaunchedEffect(shownPhone, painter) {
        val loc = shownPhone
        if (loc == null) {
            phoneMarker.closeInfoWindow()
            phoneMarker.setVisible(false)
            accuracyCircle.setVisible(false)
        } else {
            val here = GeoPoint(loc.latitude, loc.longitude)
            phoneMarker.position = here
            phoneMarker.snippet = accuracyLine(loc)
            painter.dot(MeshSatTeal.toArgb(), 18).applyTo(phoneMarker)
            phoneMarker.setVisible(true)
            if (loc.hasAccuracy() && loc.accuracy >= 1f) {
                accuracyCircle.setPoints(Polygon.pointsAsCircle(here, loc.accuracy.toDouble()))
                accuracyCircle.setVisible(true)
            } else {
                accuracyCircle.setVisible(false)
            }
        }
        mapView.invalidate()
    }

    // First view: everyone once positions exist; until then this phone, if its position is known.
    var fittedNodes by remember { mutableStateOf(false) }
    var centredPhone by remember { mutableStateOf(false) }
    LaunchedEffect(mapNodes.isNotEmpty(), phoneLocation != null) {
        val phone = phoneLocation
        if (!fittedNodes && mapNodes.isNotEmpty()) {
            fittedNodes = true
            val points = mapNodes.map { GeoPoint(it.latitude, it.longitude) } +
                listOfNotNull(phone?.let { GeoPoint(it.latitude, it.longitude) })
            showPoints(mapView, points, animate = false)
        } else if (!fittedNodes && !centredPhone && phone != null) {
            centredPhone = true
            mapView.controller.setZoom(14.0)
            mapView.controller.setCenter(GeoPoint(phone.latitude, phone.longitude))
        }
    }

    val centreOnMe: () -> Unit = {
        val loc = phoneLocation
        if (loc == null) {
            Toast.makeText(context, "Your position is not known yet.", Toast.LENGTH_SHORT).show()
        } else {
            showPhone = true
            mapView.controller.animateTo(
                GeoPoint(loc.latitude, loc.longitude),
                maxOf(mapView.zoomLevelDouble, 15.0),
                600L,
            )
        }
    }
    val showEveryone: () -> Unit = {
        val points = shownNodes.map { GeoPoint(it.latitude, it.longitude) } +
            listOfNotNull(shownPhone?.let { GeoPoint(it.latitude, it.longitude) })
        if (points.isEmpty()) {
            Toast.makeText(context, "No positions to show yet.", Toast.LENGTH_SHORT).show()
        } else {
            showPoints(mapView, points, animate = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Map",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val panelMax = maxHeight * 0.5f
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
                ) {
                    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MapButton(Icons.Outlined.MyLocation, "Centre on me", centreOnMe)
                        MapButton(Icons.Outlined.ZoomOutMap, "Show everyone on the map", showEveryone)
                    }
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MapButton(Icons.Outlined.Add, "Zoom in", { mapView.controller.zoomIn() })
                        MapButton(Icons.Outlined.Remove, "Zoom out", { mapView.controller.zoomOut() })
                    }
                    MapStatusNote(
                        detailed = detailed,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 8.dp, end = 64.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))

                MapPanel(
                    open = panelOpen,
                    onToggle = { panelOpen = !panelOpen },
                    maxListHeight = panelMax,
                    showPhone = showPhone,
                    onShowPhone = { showPhone = it },
                    showNodes = showNodes,
                    onShowNodes = { showNodes = it },
                    showTracks = showTracks,
                    onShowTracks = { showTracks = it },
                    phone = phoneLocation,
                    nodes = mapNodes,
                    names = names,
                    hidden = hidden,
                    onHiddenChange = { hidden = it },
                    onCentreNode = { node ->
                        hidden = hidden - node.nodeId
                        showNodes = true
                        mapView.controller.animateTo(
                            GeoPoint(node.latitude, node.longitude),
                            maxOf(mapView.zoomLevelDouble, 14.0),
                            600L,
                        )
                    },
                    now = now,
                )
            }
        }
    }
}

/** The panel under the map: a 56 dp bar that opens to the layers and the node list. */
@Composable
private fun MapPanel(
    open: Boolean,
    onToggle: () -> Unit,
    maxListHeight: Dp,
    showPhone: Boolean,
    onShowPhone: (Boolean) -> Unit,
    showNodes: Boolean,
    onShowNodes: (Boolean) -> Unit,
    showTracks: Boolean,
    onShowTracks: (Boolean) -> Unit,
    phone: Location?,
    nodes: List<NodePosition>,
    names: Map<Long, String>,
    hidden: Set<Long>,
    onHiddenChange: (Set<Long>) -> Unit,
    onCentreNode: (NodePosition) -> Unit,
    now: Long,
) {
    val shownCount = if (showNodes) nodes.count { it.nodeId !in hidden } else 0
    val summary = when {
        nodes.isEmpty() -> "No node positions yet"
        else -> "$shownCount of ${Words.count(nodes.size, "node")} shown"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(
                    onClickLabel = if (open) "Close layers and nodes" else "Open layers and nodes",
                    onClick = onToggle,
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Layers, contentDescription = null, tint = MeshSatTextSecondary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Layers and nodes", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = null,
                tint = MeshSatTextSecondary,
            )
        }

        if (open) {
            HorizontalDivider(color = MeshSatBorder)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
                item { PanelHeading("Layers") }
                item { LayerRow("This phone", MeshSatTeal, showPhone, onShowPhone) }
                item { LayerRow("Nodes", ColorMesh, showNodes, onShowNodes) }
                item { LayerRow("Tracks from the last 24 hours", ColorMesh, showTracks, onShowTracks) }
                if (phone != null) {
                    item { PhoneRow(phone) }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Nodes",
                            style = MaterialTheme.typography.titleSmall,
                            color = MeshSatTextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (nodes.isNotEmpty()) {
                            TextButton(
                                onClick = { onHiddenChange(emptySet()) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Show all") }
                            TextButton(
                                onClick = { onHiddenChange(nodes.mapTo(HashSet()) { it.nodeId }) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Hide all") }
                        }
                    }
                }
                if (nodes.isEmpty()) {
                    item {
                        Text(
                            text = "Nodes appear here when they send a position.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatTextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                items(nodes, key = { it.nodeId }) { node ->
                    NodeListRow(
                        label = positionLabel(node, names),
                        node = node,
                        shown = node.nodeId !in hidden,
                        stale = now - node.timestamp > STALE_AFTER_MS,
                        onShownChange = { show ->
                            onHiddenChange(if (show) hidden - node.nodeId else hidden + node.nodeId)
                        },
                        onCentre = { onCentreNode(node) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MeshSatTextSecondary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** One layer switch: the whole 48 dp row toggles it. */
@Composable
private fun LayerRow(label: String, dot: Color, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
        Box(modifier = Modifier.size(10.dp).background(dot, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun PhoneRow(loc: Location) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("This phone", style = MaterialTheme.typography.bodyMedium, color = MeshSatTextPrimary)
        Text(
            text = "%.5f, %.5f".format(loc.latitude, loc.longitude) + ", " + accuracyLine(loc).replaceFirstChar { it.lowercase() },
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

/**
 * One node: the checkbox shows or hides it on the map, the rest of the row centres the map on it.
 * The full name is shown, cut with an ellipsis only when it does not fit.
 */
@Composable
private fun NodeListRow(
    label: String,
    node: NodePosition,
    shown: Boolean,
    stale: Boolean,
    onShownChange: (Boolean) -> Unit,
    onCentre: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Centre the map on $label", onClick = onCentre)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = shown,
            onCheckedChange = onShownChange,
            modifier = Modifier.semantics { contentDescription = "Show $label on the map" },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (stale) MeshSatTextSecondary else MeshSatTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = heardLine(node.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                maxLines = 1,
            )
        }
    }
}

/** "Heard 3 min ago", or "Last heard 2 h ago" once the node is stale. */
private fun heardLine(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val ago = Words.ago(timestamp, now)
    return if (now - timestamp > STALE_AFTER_MS) "Last heard $ago" else "Heard $ago"
}

/** "Within about 12 m", or "Accuracy unknown". */
private fun accuracyLine(loc: Location): String =
    if (loc.hasAccuracy()) "Within about ${loc.accuracy.toInt()} m" else "Accuracy unknown"
