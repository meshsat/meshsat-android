package net.meshsat.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.meshsat.android.ble.MeshtasticProtocol
import net.meshsat.android.data.NodePosition
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.map.DetailedMap
import net.meshsat.android.map.MapTiles
import net.meshsat.android.map.MarkerPainter
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.theme.MeshSatBg
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

// Pieces the Map tab and Zones share (MESHSAT-1249), so both maps draw the same tiles, markers and
// controls.

/** The detailed offline map chosen in Setup > Maps, or null when none is chosen or it cannot be drawn. */
@Composable
fun rememberDetailedMap(): DetailedMap? {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val enabled by settings.offlineMapEnabled.collectAsState(initial = false)
    val filename by settings.offlineMapFile.collectAsState(initial = "")
    val detailed by produceState<DetailedMap?>(null, enabled, filename) {
        value = if (enabled && filename.isNotBlank()) {
            withContext(Dispatchers.IO) { MapTiles.detailedMap(context, filename) }
        } else {
            null
        }
    }
    return detailed
}

/** Keeps [mapView] on the chosen detailed map; switches when Setup > Maps changes. */
@Composable
fun MapTilesEffect(mapView: MapView, detailed: DetailedMap?) {
    val context = LocalContext.current
    LaunchedEffect(mapView, detailed?.file?.path) {
        MapTiles.useDetailed(context, mapView, detailed?.file)
    }
}

/** Passes the screen's resume and pause on to osmdroid, which stops its tile threads while paused. */
@Composable
fun MapLifecycleEffect(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
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
}

/** Marker drawing sized for this screen: labels at 12 sp, so they grow with the user's font size. */
@Composable
fun rememberMarkerPainter(mapView: MapView): MarkerPainter {
    val density = LocalDensity.current
    val labelPx = with(density) { 12.sp.toPx() }
    return remember(mapView, labelPx, density.density) {
        MarkerPainter(mapView.resources, labelPx = labelPx, maxLabelPx = labelPx * 12f, density = density.density)
    }
}

/** A round 48 dp button that sits on the map. */
@Composable
fun MapButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .background(MeshSatSurface, CircleShape)
            .border(1.dp, MeshSatBorder, CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = MeshSatTextPrimary)
    }
}

/**
 * The line in the map's corner: which offline map is showing while online tiles cannot load, and
 * the OpenStreetMap credit while they can.
 */
@Composable
fun MapStatusNote(detailed: DetailedMap?, modifier: Modifier = Modifier, worldOnlyNote: String? = null) {
    val offline by MapTiles.offline.collectAsState()
    val text = when {
        offline && detailed != null -> "Offline map: ${detailed.name}. Outside it, the world overview."
        offline -> worldOnlyNote ?: "Offline map: world overview, country level only. Add a detailed map in Setup > Maps."
        else -> null
    }
    Column(modifier = modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextPrimary,
                modifier = Modifier
                    .background(MeshSatBg.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else {
            Text(
                text = "© OpenStreetMap contributors",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextSecondary,
                modifier = Modifier
                    .background(MeshSatBg.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Names the radio knows, by node number. Position rows only carry the id ("!a1b2c3d4"). */
fun meshNodeNames(): Map<Long, String> =
    GatewayService.meshtasticBle?.nodes?.value.orEmpty()
        .mapNotNull { n -> n.longName.ifBlank { n.shortName }.takeIf { it.isNotBlank() }?.let { n.nodeNum to it } }
        .toMap()

/** What a position is called on the map: the radio's name, else the name it came with, else its id. */
fun positionLabel(node: NodePosition, names: Map<Long, String>): String =
    names[node.nodeId] ?: node.nodeName.ifBlank { MeshtasticProtocol.formatNodeId(node.nodeId) }

/** A zoom level that shows a span of [spanDegrees] (the larger of latitude and longitude spans). */
fun zoomForSpan(spanDegrees: Double): Double = when {
    spanDegrees < 0.002 -> 16.0
    spanDegrees < 0.01 -> 15.0
    spanDegrees < 0.05 -> 13.0
    spanDegrees < 0.2 -> 11.0
    spanDegrees < 1.0 -> 9.0
    spanDegrees < 5.0 -> 7.0
    spanDegrees < 30.0 -> 5.0
    else -> 3.0
}

/**
 * Centres [mapView] on [points] with a zoom that fits them. Done by centre and zoom rather than
 * zoomToBoundingBox, which could stall the UI in osmdroid's projection maths.
 */
fun showPoints(mapView: MapView, points: List<GeoPoint>, animate: Boolean) {
    if (points.isEmpty()) return
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val centre = GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    val zoom = zoomForSpan(maxOf(maxLat - minLat, maxLon - minLon))
    if (animate) {
        mapView.controller.animateTo(centre, zoom, 600L)
    } else {
        mapView.controller.setZoom(zoom)
        mapView.controller.setCenter(centre)
    }
}
