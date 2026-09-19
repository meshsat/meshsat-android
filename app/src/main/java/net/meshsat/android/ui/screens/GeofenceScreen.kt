package net.meshsat.android.ui.screens

import android.location.Location
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.engine.GeofenceZone
import net.meshsat.android.engine.LatLon
import net.meshsat.android.map.MapTiles
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
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBg
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.OverlayWithIW
import org.osmdroid.views.overlay.Polygon
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

private const val STALE_AFTER_MS = 15 * 60_000L
private const val MIN_RADIUS_M = 10
private const val MAX_RADIUS_M = 50_000
private const val SLIDER_MIN_M = 50.0
private const val SLIDER_MAX_M = 5_000.0

/**
 * What an alert does, from GeofenceMonitor and GatewayService as they are: only Meshtastic position
 * reports are checked, a crossing is recorded in the list on this screen and nowhere else (the
 * monitor's callback is not connected), and zones are held in memory by the service.
 */
private const val ALERT_EXPLAINER =
    "When a mesh node reports a position that crosses a zone's edge, the alert is listed here. " +
        "It does not send a message or a notification, and zones are kept only until the MeshSat service restarts."

/**
 * Zones (MESHSAT-1249, B4). The map was a Leaflet page loading leaflet.js from assets that were never
 * shipped, so it stayed blank; it is now the same osmdroid map as the Map tab, with its offline
 * fallback. A zone is a circle placed by long-pressing the map (or at this phone's position), sized
 * with a radius slider, and never at 0,0.
 */
@Composable
fun GeofenceScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val phoneLocation by GatewayService.phoneLocation.collectAsState()
    val positions by remember { db.nodePositionDao().getLatestPerNode() }.collectAsState(initial = emptyList())
    val detailed = rememberDetailedMap()

    // The monitor lives in the service and can appear after this screen opens, and it has no change
    // signal, so zones and alerts are read again every few seconds.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    var changes by remember { mutableStateOf(0) }
    val monitor = GatewayService.geofenceMonitor
    val zones = remember(monitor, changes, now) { monitor?.getZones().orEmpty() }
    val events = remember(monitor, changes, now) { monitor?.getEvents().orEmpty() }
    val nodes = remember(positions) { positions.filter { it.nodeId != 0L } }
    val names = remember(positions, now / 60_000) { meshNodeNames() }

    // The zone being placed.
    var placing by remember { mutableStateOf(false) }
    var centre by remember { mutableStateOf<GeoPoint?>(null) }
    var radius by remember { mutableStateOf(200.0) }
    var radiusText by remember { mutableStateOf("200") }
    var name by remember { mutableStateOf("") }
    var alertOn by remember { mutableStateOf("enter") }
    var note by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var centreError by remember { mutableStateOf(false) }
    var radiusError by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<GeofenceZone?>(null) }

    fun resetDraft() {
        placing = false
        centre = null
        radius = 200.0
        radiusText = "200"
        name = ""
        alertOn = "enter"
        note = ""
        nameError = false
        centreError = false
        radiusError = false
    }

    // The map is made once per visit, so a location update no longer rebuilds it and resets the zoom.
    val mapView = remember {
        MapTiles.newMapView(context).apply {
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }
    MapLifecycleEffect(mapView)
    MapTilesEffect(mapView, detailed)
    val painter = rememberMarkerPainter(mapView)
    val density = LocalDensity.current.density

    val onLongPress = rememberUpdatedState<(GeoPoint) -> Unit> { p ->
        if (monitor != null) {
            placing = true
            centre = GeoPoint(p.latitude, p.longitude)
            centreError = false
            mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // Bottom to top: long-press catcher, zones, nodes and this phone, the zone being placed.
    remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                onLongPress.value(p)
                return true
            }
        }).also { mapView.overlays.add(0, it) }
    }
    val zoneLayer = remember { FolderOverlay().also { mapView.overlays.add(it) } }
    val nodeLayer = remember { FolderOverlay().also { mapView.overlays.add(it) } }
    val draftLayer = remember { FolderOverlay().also { mapView.overlays.add(it) } }
    val nodeMarkers = remember { HashMap<Long, Marker>() }
    val phoneMarker = remember {
        Marker(mapView).also { marker ->
            marker.title = "This phone"
            marker.setVisible(false)
            nodeLayer.add(marker)
        }
    }
    val draftCircle = remember {
        Polygon(mapView).also { circle ->
            circle.setOnClickListener { _, _, _ -> false }
            circle.setVisible(false)
            draftLayer.add(circle)
        }
    }
    val draftDot = remember {
        Marker(mapView).also { dot ->
            dot.setOnMarkerClickListener { _, _ -> true }
            dot.setVisible(false)
            draftLayer.add(dot)
        }
    }

    LaunchedEffect(zones) {
        zoneLayer.items.toList().forEach { overlay ->
            (overlay as? OverlayWithIW)?.closeInfoWindow()
            zoneLayer.remove(overlay)
        }
        zones.forEach { zone ->
            val shape = Polygon(mapView)
            shape.setPoints(zone.polygon.map { GeoPoint(it.lat, it.lon) })
            shape.fillPaint.color = MeshSatAmber.copy(alpha = 0.12f).toArgb()
            shape.outlinePaint.color = MeshSatAmber.toArgb()
            shape.outlinePaint.strokeWidth = 2f * density
            shape.title = zone.name
            shape.snippet = "Alerts when a node ${alertWhen(zone.alertOn)}"
            zoneLayer.add(shape)
        }
        mapView.invalidate()
    }

    LaunchedEffect(nodes, names, painter, now / 60_000) {
        val current = System.currentTimeMillis()
        val keep = nodes.mapTo(HashSet()) { it.nodeId }
        nodeMarkers.keys.filter { it !in keep }.forEach { id ->
            nodeMarkers.remove(id)?.let { gone ->
                gone.closeInfoWindow()
                nodeLayer.remove(gone)
            }
        }
        nodes.forEach { node ->
            val label = positionLabel(node, names)
            val marker = nodeMarkers.getOrPut(node.nodeId) { Marker(mapView).also { nodeLayer.add(it) } }
            marker.position = GeoPoint(node.latitude, node.longitude)
            marker.title = label
            marker.snippet = "Heard " + Words.ago(node.timestamp, current)
            painter.node(label, ColorMesh.toArgb(), current - node.timestamp > STALE_AFTER_MS).applyTo(marker)
        }
        mapView.invalidate()
    }

    LaunchedEffect(phoneLocation, painter) {
        val loc = phoneLocation
        if (loc == null) {
            phoneMarker.setVisible(false)
        } else {
            phoneMarker.position = GeoPoint(loc.latitude, loc.longitude)
            painter.dot(MeshSatTeal.toArgb(), 18).applyTo(phoneMarker)
            phoneMarker.setVisible(true)
        }
        mapView.invalidate()
    }

    LaunchedEffect(placing, centre, radius, painter) {
        val c = centre
        if (placing && c != null) {
            draftCircle.setPoints(Polygon.pointsAsCircle(c, radius))
            draftCircle.fillPaint.color = MeshSatTeal.copy(alpha = 0.15f).toArgb()
            draftCircle.outlinePaint.color = MeshSatTeal.toArgb()
            draftCircle.outlinePaint.strokeWidth = 2f * density
            draftCircle.setVisible(true)
            draftDot.position = c
            painter.dot(MeshSatTeal.toArgb(), 14).applyTo(draftDot)
            draftDot.setVisible(true)
        } else {
            draftCircle.setVisible(false)
            draftDot.setVisible(false)
        }
        mapView.invalidate()
    }

    // First view, once: the zones if there are any, else this phone. Never again after that, so the
    // user's own zoom and position stay put.
    var fitted by remember { mutableStateOf(false) }
    LaunchedEffect(zones.isNotEmpty(), phoneLocation != null) {
        if (fitted) return@LaunchedEffect
        val zonePoints = zones.flatMap { zone -> zone.polygon.map { GeoPoint(it.lat, it.lon) } }
        val phone = phoneLocation
        if (zonePoints.isNotEmpty()) {
            fitted = true
            showPoints(mapView, zonePoints, animate = false)
        } else if (phone != null) {
            fitted = true
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(GeoPoint(phone.latitude, phone.longitude))
        }
    }

    val centreOnMe: () -> Unit = {
        val loc = phoneLocation
        if (loc == null) {
            Toast.makeText(context, "Your position is not known yet.", Toast.LENGTH_SHORT).show()
        } else {
            mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), maxOf(mapView.zoomLevelDouble, 15.0), 600L)
        }
    }
    val startZone: () -> Unit = {
        placing = true
        val loc = phoneLocation
        if (loc != null && centre == null) {
            val here = GeoPoint(loc.latitude, loc.longitude)
            centre = here
            mapView.controller.animateTo(here, zoomForRadius(radius), 600L)
        }
    }
    val saveZone: () -> Unit = save@{
        val c = centre
        val r = radiusText.toIntOrNull()?.takeIf { it in MIN_RADIUS_M..MAX_RADIUS_M }
        val m = monitor
        nameError = name.isBlank()
        centreError = c == null
        radiusError = r == null
        if (nameError || c == null || r == null || m == null) return@save
        val zone = GeofenceZone(
            id = "zone_${System.currentTimeMillis()}",
            name = name.trim(),
            polygon = circlePolygon(c.latitude, c.longitude, r.toDouble(), 32),
            alertOn = alertOn,
            message = note.trim(),
        )
        m.addZone(zone)
        changes++
        Toast.makeText(context, "Zone added: ${zone.name}", Toast.LENGTH_SHORT).show()
        resetDraft()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            val hint = when {
                monitor == null -> null
                placing && centre == null -> "Long-press the map where the zone should be."
                placing -> "Long-press the map to move the zone."
                else -> "Long-press the map to place a zone."
            }
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp, end = 64.dp)
                        .widthIn(max = 280.dp)
                        .background(MeshSatBg.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            MapButton(
                icon = Icons.Outlined.MyLocation,
                description = "Centre on me",
                onClick = centreOnMe,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (placing) {
                ZoneEditor(
                    hasCentre = centre != null,
                    centreError = centreError,
                    name = name,
                    onName = {
                        name = it
                        if (it.isNotBlank()) nameError = false
                    },
                    nameError = nameError,
                    radius = radius,
                    onRadiusSlider = {
                        radius = it
                        radiusText = it.roundToInt().toString()
                        radiusError = false
                    },
                    radiusText = radiusText,
                    onRadiusText = { text ->
                        val digits = text.filter { it.isDigit() }.take(5)
                        radiusText = digits
                        val value = digits.toIntOrNull()
                        if (value != null && value in MIN_RADIUS_M..MAX_RADIUS_M) {
                            radius = value.toDouble()
                            radiusError = false
                        }
                    },
                    radiusError = radiusError,
                    alertOn = alertOn,
                    onAlertOn = { alertOn = it },
                    note = note,
                    onNote = { note = it },
                    onSave = saveZone,
                    onCancel = { resetDraft() },
                )
            } else {
                Text(
                    text = ALERT_EXPLAINER,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextSecondary,
                )
                if (monitor == null) {
                    Text(
                        text = "Zones are not available until the MeshSat service is running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatAmber,
                    )
                } else {
                    Button(
                        onClick = startZone,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add zone")
                    }
                    if (zones.isEmpty()) {
                        Text(
                            text = "No zones yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatTextMuted,
                        )
                    }
                }

                zones.forEach { zone ->
                    ZoneRow(
                        zone = zone,
                        onShow = {
                            showPoints(mapView, zone.polygon.map { GeoPoint(it.lat, it.lon) }, animate = true)
                        },
                        onDelete = { toDelete = zone },
                    )
                }

                if (zones.isNotEmpty() || events.isNotEmpty()) {
                    Text(
                        text = "Recent alerts",
                        style = MaterialTheme.typography.titleSmall,
                        color = MeshSatTextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (events.isEmpty()) {
                        Text(
                            text = "No alerts yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatTextMuted,
                        )
                    }
                    events.take(20).forEach { ev ->
                        val who = eventNodeName(ev.nodeId, names)
                        val did = if (ev.event == "enter") "entered" else "left"
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(MeshSatAmber, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "$who $did ${ev.zoneName}, ${Words.ago(ev.timestamp, now)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { zone ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = MeshSatSurface,
            title = { Text("Delete ${zone.name}?") },
            text = {
                Text("MeshSat stops watching this zone. Alerts it already raised stay in the list.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        monitor?.removeZone(zone.id)
                        changes++
                        toDelete = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Delete zone", color = MeshSatRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Keep it")
                }
            },
        )
    }
}

/** The form for a new zone, shown under the map while it is being placed. */
@Composable
private fun ZoneEditor(
    hasCentre: Boolean,
    centreError: Boolean,
    name: String,
    onName: (String) -> Unit,
    nameError: Boolean,
    radius: Double,
    onRadiusSlider: (Double) -> Unit,
    radiusText: String,
    onRadiusText: (String) -> Unit,
    radiusError: Boolean,
    alertOn: String,
    onAlertOn: (String) -> Unit,
    note: String,
    onNote: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MeshSatTeal,
        unfocusedBorderColor = MeshSatBorder,
    )
    Text("New zone", style = MaterialTheme.typography.titleMedium)
    Text(
        text = when {
            !hasCentre -> "Long-press the map where the zone should be."
            else -> "The orange circle is the zone. Long-press the map to move it."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (centreError && !hasCentre) MeshSatRed else MeshSatTextSecondary,
    )

    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("Name") },
        singleLine = true,
        isError = nameError,
        supportingText = if (nameError) {
            { Text("Give the zone a name.") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        colors = fieldColors,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Radius", style = MaterialTheme.typography.titleSmall, color = MeshSatTextSecondary)
    Slider(
        value = radiusToSlider(radius),
        onValueChange = { onRadiusSlider(sliderToRadius(it)) },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Radius" },
    )
    OutlinedTextField(
        value = radiusText,
        onValueChange = onRadiusText,
        label = { Text("Radius in metres") },
        singleLine = true,
        isError = radiusError,
        supportingText = {
            Text(
                if (radiusError) {
                    "Use a radius between $MIN_RADIUS_M m and ${MAX_RADIUS_M / 1000} km."
                } else {
                    "About ${formatDistance(radius * 2)} across."
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        colors = fieldColors,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Alert when a node", style = MaterialTheme.typography.titleSmall, color = MeshSatTextSecondary)
    Column(modifier = Modifier.selectableGroup()) {
        listOf("enter" to "Enters the zone", "exit" to "Leaves the zone", "both" to "Enters or leaves").forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = alertOn == mode, onClick = { onAlertOn(mode) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = alertOn == mode, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    OutlinedTextField(
        value = note,
        onValueChange = onNote,
        label = { Text("Note (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        colors = fieldColors,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Save zone")
        }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Cancel")
        }
    }
}

/** One zone: tap to see it on the map; the bin asks before it deletes. */
@Composable
private fun ZoneRow(zone: GeofenceZone, onShow: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Show ${zone.name} on the map", onClick = onShow)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(MeshSatAmber, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = zone.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Alerts when a node ${alertWhen(zone.alertOn)}. Radius about ${formatDistance(zoneRadius(zone.polygon))}.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextSecondary,
            )
            if (zone.message.isNotBlank()) {
                Text(
                    text = zone.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete zone ${zone.name}", tint = MeshSatTextSecondary)
        }
    }
}

private fun alertWhen(alertOn: String): String = when (alertOn) {
    "enter" -> "enters"
    "exit" -> "leaves"
    else -> "enters or leaves"
}

/** The radio's name for an event's node ("!a1b2c3d4"), else the id itself. */
private fun eventNodeName(id: String, names: Map<Long, String>): String =
    id.removePrefix("!").toLongOrNull(16)?.let { names[it] } ?: id

/** The slider runs on a log scale, so 50 m and 5 km are both easy to set. */
private fun radiusToSlider(r: Double): Float =
    (ln(r.coerceIn(SLIDER_MIN_M, SLIDER_MAX_M) / SLIDER_MIN_M) / ln(SLIDER_MAX_M / SLIDER_MIN_M)).toFloat()

private fun sliderToRadius(t: Float): Double {
    val raw = SLIDER_MIN_M * exp(t * ln(SLIDER_MAX_M / SLIDER_MIN_M))
    val step = when {
        raw < 200 -> 10.0
        raw < 1_000 -> 25.0
        else -> 100.0
    }
    return (raw / step).roundToInt() * step
}

private fun zoomForRadius(r: Double): Double = when {
    r <= 100 -> 17.0
    r <= 250 -> 16.0
    r <= 500 -> 15.0
    r <= 1_000 -> 14.0
    r <= 2_500 -> 13.0
    r <= 5_000 -> 12.0
    r <= 15_000 -> 11.0
    else -> 9.0
}

private fun formatDistance(metres: Double): String =
    if (metres < 1_000) {
        "${metres.roundToInt()} m"
    } else {
        "%.1f km".format(metres / 1_000).replace(".0 km", " km")
    }

/** A zone's radius, as the mean distance from its middle to its corners (zones made here are circles). */
private fun zoneRadius(polygon: List<LatLon>): Double {
    if (polygon.isEmpty()) return 0.0
    val lat = polygon.sumOf { it.lat } / polygon.size
    val lon = polygon.sumOf { it.lon } / polygon.size
    val result = FloatArray(1)
    return polygon.sumOf { p ->
        Location.distanceBetween(lat, lon, p.lat, p.lon, result)
        result[0].toDouble()
    } / polygon.size
}

/**
 * A circle as a polygon of [numPoints] corners around a centre, by the equirectangular approximation
 * (good for the sizes a zone has).
 */
private fun circlePolygon(centerLat: Double, centerLon: Double, radiusMeters: Double, numPoints: Int): List<LatLon> {
    val earthRadius = 6_371_000.0
    return (0 until numPoints).map { i ->
        val angle = 2.0 * Math.PI * i / numPoints
        val dLat = radiusMeters * cos(angle) / earthRadius
        val dLon = radiusMeters * sin(angle) / (earthRadius * cos(Math.toRadians(centerLat)))
        LatLon(centerLat + Math.toDegrees(dLat), centerLon + Math.toDegrees(dLon))
    }
}
