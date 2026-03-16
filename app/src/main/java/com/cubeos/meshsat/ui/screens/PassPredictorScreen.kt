package com.cubeos.meshsat.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.satellite.PassPrediction
import com.cubeos.meshsat.satellite.PassPredictor
import com.cubeos.meshsat.satellite.TleFetcher
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatSurfaceLight
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTextPrimary
import com.cubeos.meshsat.ui.theme.MeshSatTextSecondary
import com.cubeos.meshsat.ui.theme.SignalExcellent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlin.math.roundToInt

// Elevation environment presets matching Go web frontend
private data class ElevPreset(val value: Int, val label: String, val desc: String)

private val ELEV_PRESETS = listOf(
    ElevPreset(5, "Clear Sky", "Open field, rooftop"),
    ElevPreset(20, "Partial", "Some trees, low buildings"),
    ElevPreset(40, "Urban", "Tall buildings, narrow streets"),
    ElevPreset(60, "Canyon", "Deep valley, dense urban"),
)

private val WINDOW_OPTIONS = listOf(12, 24, 48, 72)

@Composable
fun PassPredictorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val fetcher = remember { TleFetcher(db) }

    // State
    var passes by remember { mutableStateOf<List<PassPrediction>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var cacheAgeSec by remember { mutableLongStateOf(-1L) }
    var windowHours by remember { mutableIntStateOf(24) }
    var minElevDeg by remember { mutableIntStateOf(5) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var expandedPassList by remember { mutableStateOf(false) }

    // Location from phone GPS
    var lat by remember { mutableDoubleStateOf(0.0) }
    var lon by remember { mutableDoubleStateOf(0.0) }
    var hasLocation by remember { mutableStateOf(false) }
    var locationSource by remember { mutableStateOf("None") }

    // Countdown timer
    var countdownText by remember { mutableStateOf("") }

    // Try to get GPS location
    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            try {
                val lm = context.getSystemService(LocationManager::class.java)
                val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    lat = loc.latitude
                    lon = loc.longitude
                    hasLocation = true
                    locationSource = if (loc.provider == LocationManager.GPS_PROVIDER) "GPS" else "Network"
                }
            } catch (_: SecurityException) { }
        }
    }

    // Compute passes when location or params change
    fun computePasses() {
        if (!hasLocation) return
        scope.launch {
            loading = true
            errorMsg = null
            try {
                val tles = fetcher.getTles()
                if (tles.isEmpty()) {
                    errorMsg = "No TLE data available. Tap Refresh TLEs."
                    loading = false
                    return@launch
                }
                val nowUnix = System.currentTimeMillis() / 1000
                val startUnix = nowUnix - (windowHours * 3600L / 2)
                val endUnix = nowUnix + (windowHours * 3600L / 2)
                val computed = withContext(Dispatchers.Default) {
                    PassPredictor.predictAllPasses(
                        tles, lat, lon, 0.0, startUnix, endUnix, minElevDeg.toDouble()
                    )
                }
                passes = computed
                cacheAgeSec = fetcher.cacheAgeSec()
            } catch (e: Exception) {
                errorMsg = "Prediction failed: ${e.message}"
            }
            loading = false
        }
    }

    // Initial load
    LaunchedEffect(hasLocation, windowHours, minElevDeg) {
        if (hasLocation) computePasses()
    }

    // Countdown ticker (every second)
    LaunchedEffect(passes) {
        while (true) {
            val nowUnix = System.currentTimeMillis() / 1000
            val next = passes.firstOrNull { it.aosUnix > nowUnix }
            countdownText = if (next != null) {
                formatCountdown(next.aosUnix - nowUnix)
            } else {
                ""
            }
            delay(1000)
        }
    }

    val nowUnix = System.currentTimeMillis() / 1000
    val nextPass = passes.firstOrNull { it.aosUnix > nowUnix }
    val activePass = passes.firstOrNull { it.aosUnix <= nowUnix && it.losUnix >= nowUnix }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pass Predictor",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "TLE: ${formatCacheAge(cacheAgeSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextMuted,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                fetcher.refreshFromCelestrak()
                                cacheAgeSec = fetcher.cacheAgeSec()
                                refreshing = false
                                computePasses()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                        enabled = !refreshing,
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            if (refreshing) "Refreshing..." else "Refresh TLEs",
                            style = MaterialTheme.typography.labelSmall,
                            color = ColorIridium,
                        )
                    }
                }
            }
        }

        // Location indicator
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (hasLocation) SignalExcellent else MeshSatRed)
                )
                Text(
                    text = if (hasLocation)
                        "$locationSource: ${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                    else
                        "No location — grant GPS permission",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasLocation) MeshSatTextSecondary else MeshSatRed,
                )
            }
        }

        // Time window buttons
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WINDOW_OPTIONS.forEach { h ->
                    Button(
                        onClick = { windowHours = h },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (windowHours == h)
                                ColorIridium.copy(alpha = 0.2f) else MeshSatSurface,
                        ),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            "${h}h",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (windowHours == h) ColorIridium else MeshSatTextMuted,
                            fontWeight = if (windowHours == h) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // Elevation presets
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Min Elev", style = MaterialTheme.typography.labelSmall, color = MeshSatTextMuted)
                ELEV_PRESETS.forEach { p ->
                    Button(
                        onClick = { minElevDeg = p.value },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (minElevDeg == p.value)
                                ColorIridium.copy(alpha = 0.2f) else MeshSatSurface,
                        ),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            "${p.label} ${p.value}°",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (minElevDeg == p.value) ColorIridium else MeshSatTextMuted,
                            fontWeight = if (minElevDeg == p.value) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // Active pass banner
        if (activePass != null) {
            item {
                PassBanner(
                    label = "ACTIVE PASS",
                    pass = activePass,
                    accentColor = SignalExcellent,
                    subtitle = "Satellite overhead — transmit now!",
                )
            }
        }

        // Next pass countdown
        if (nextPass != null && activePass == null) {
            item {
                PassBanner(
                    label = "NEXT PASS",
                    pass = nextPass,
                    accentColor = ColorIridium,
                    subtitle = if (countdownText.isNotEmpty()) "T-$countdownText" else null,
                    showCountdown = true,
                    countdownText = countdownText,
                )
            }
        }

        // Elevation arc chart
        if (!loading && passes.isNotEmpty()) {
            item {
                PassElevationChart(
                    passes = passes,
                    windowHours = windowHours,
                    minElevDeg = minElevDeg.toDouble(),
                    nowUnix = nowUnix,
                )
            }
        }

        // Loading / Error
        if (loading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = ColorIridium,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Calculating passes...", color = MeshSatTextMuted)
                }
            }
        }

        if (errorMsg != null) {
            item {
                Text(errorMsg!!, color = MeshSatRed, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Pass list header (collapsible)
        if (!loading && passes.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MeshSatSurface.copy(alpha = 0.5f))
                        .border(1.dp, MeshSatBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable { expandedPassList = !expandedPassList }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${passes.size} passes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeshSatTextSecondary,
                    )
                    Text(
                        text = if (expandedPassList) "▼" else "▶",
                        color = MeshSatTextMuted,
                    )
                }
            }
        }

        // Pass list items
        if (expandedPassList && !loading) {
            items(passes, key = { "${it.satellite}-${it.aosUnix}" }) { pass ->
                PassRow(pass)
            }
        }

        // Empty state
        if (!loading && passes.isEmpty() && hasLocation && errorMsg == null) {
            item {
                Text(
                    "No passes found for this location and time window.",
                    color = MeshSatTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MeshSatSurface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                        .padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun PassElevationChart(
    passes: List<PassPrediction>,
    windowHours: Int,
    minElevDeg: Double,
    nowUnix: Long,
) {
    val density = LocalDensity.current

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        val w = size.width
        val h = size.height
        val pad = 40f * density.density   // left padding for Y labels
        val padBottom = 24f * density.density  // bottom padding for X labels
        val padTop = 8f * density.density
        val chartW = w - pad
        val chartH = h - padBottom - padTop

        val startUnix = nowUnix - windowHours * 3600L / 2
        val endUnix = nowUnix + windowHours * 3600L / 2
        val timeRange = (endUnix - startUnix).toFloat()

        fun timeToX(unix: Long): Float = pad + ((unix - startUnix).toFloat() / timeRange) * chartW
        fun elevToY(deg: Double): Float = padTop + chartH * (1f - (deg.toFloat() / 90f))

        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

        // Horizontal grid lines at 30 and 60 degrees
        val gridPaint = Color(0xFF4B5563).copy(alpha = 0.4f)
        for (deg in listOf(30.0, 60.0)) {
            val y = elevToY(deg)
            drawLine(
                color = gridPaint,
                start = androidx.compose.ui.geometry.Offset(pad, y),
                end = androidx.compose.ui.geometry.Offset(w, y),
                pathEffect = dashEffect,
                strokeWidth = 1f,
            )
        }

        // Y axis labels: 0, 30, 60, 90
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#6B7280")
            textSize = 9f * density.density
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        for (deg in listOf(0, 30, 60, 90)) {
            val y = elevToY(deg.toDouble())
            drawContext.canvas.nativeCanvas.drawText(
                "${deg}\u00B0",
                pad - 6f * density.density,
                y + 3f * density.density,
                labelPaint,
            )
        }

        // X axis labels: time marks
        val xLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#6B7280")
            textSize = 9f * density.density
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        // Determine interval: 2h for <= 24h, 4h for larger windows
        val intervalHours = if (windowHours <= 24) 2 else 4
        val intervalSec = intervalHours * 3600L
        // Snap start to next interval boundary
        val firstTick = ((startUnix / intervalSec) + 1) * intervalSec
        var tick = firstTick
        while (tick < endUnix) {
            val x = timeToX(tick)
            if (x > pad && x < w - 10f) {
                drawContext.canvas.nativeCanvas.drawText(
                    formatTimeUtc(tick),
                    x,
                    h - 4f * density.density,
                    xLabelPaint,
                )
                // Small tick mark
                drawLine(
                    color = gridPaint,
                    start = androidx.compose.ui.geometry.Offset(x, padTop + chartH),
                    end = androidx.compose.ui.geometry.Offset(x, padTop + chartH + 4f * density.density),
                    strokeWidth = 1f,
                )
            }
            tick += intervalSec
        }

        // Baseline at 0 degrees
        drawLine(
            color = MeshSatBorder,
            start = androidx.compose.ui.geometry.Offset(pad, elevToY(0.0)),
            end = androidx.compose.ui.geometry.Offset(w, elevToY(0.0)),
            strokeWidth = 1f,
        )

        // Min elevation line (dashed amber)
        if (minElevDeg > 0) {
            val minY = elevToY(minElevDeg)
            drawLine(
                color = MeshSatAmber.copy(alpha = 0.6f),
                start = androidx.compose.ui.geometry.Offset(pad, minY),
                end = androidx.compose.ui.geometry.Offset(w, minY),
                pathEffect = dashEffect,
                strokeWidth = 1f * density.density,
            )
        }

        // Determine next upcoming pass for highlight
        val nextUpcoming = passes.firstOrNull { it.aosUnix > nowUnix }

        // Pass arcs
        for (pass in passes) {
            if (pass.losUnix < startUnix || pass.aosUnix > endUnix) continue

            val x1 = timeToX(pass.aosUnix)
            val x2 = timeToX(pass.losUnix)
            val xMid = (x1 + x2) / 2f
            val yPeak = elevToY(pass.peakElevDeg)
            val yBase = elevToY(0.0)

            val isHighlight = pass.isActive || pass == nextUpcoming
            val fillAlpha = if (isHighlight) 0.5f else 0.25f
            val strokeAlpha = if (isHighlight) 1f else 0.6f
            val strokeWidth = if (isHighlight) 2.5f * density.density else 1.5f * density.density

            // Filled bezier arc
            val fillPath = Path().apply {
                moveTo(x1, yBase)
                quadraticTo(xMid, yPeak, x2, yBase)
                close()
            }
            drawPath(fillPath, ColorIridium.copy(alpha = fillAlpha))

            // Stroke bezier arc
            val strokePath = Path().apply {
                moveTo(x1, yBase)
                quadraticTo(xMid, yPeak, x2, yBase)
            }
            drawPath(
                strokePath,
                ColorIridium.copy(alpha = strokeAlpha),
                style = Stroke(width = strokeWidth),
            )
        }

        // Current time vertical line (dashed amber)
        val nowX = timeToX(nowUnix)
        if (nowX in pad..w) {
            drawLine(
                color = MeshSatAmber,
                start = androidx.compose.ui.geometry.Offset(nowX, padTop),
                end = androidx.compose.ui.geometry.Offset(nowX, padTop + chartH),
                pathEffect = dashEffect,
                strokeWidth = 1.5f * density.density,
            )
        }
    }
}

@Composable
private fun PassBanner(
    label: String,
    pass: PassPrediction,
    accentColor: Color,
    subtitle: String? = null,
    showCountdown: Boolean = false,
    countdownText: String = "",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(16.dp)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor.copy(alpha = 0.6f),
                    letterSpacing = 1.sp,
                )
                Text(
                    pass.satellite,
                    style = MaterialTheme.typography.titleMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (showCountdown && countdownText.isNotEmpty()) {
                    Text(
                        countdownText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                }
                Text(
                    formatTimeUtc(pass.aosUnix),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (showCountdown) MeshSatTextSecondary else accentColor,
                )
                Text(
                    "${formatDateShort(pass.aosUnix)} UTC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PassDetailChip("Duration", formatDurationMin(pass.durationMin))
            PassDetailChip("Peak", "${pass.peakElevDeg.roundToInt()}°")
            PassDetailChip("Az", "${pass.peakAzimuthDeg.roundToInt()}°")
        }
        if (subtitle != null && !showCountdown) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = accentColor)
        }
    }
}

@Composable
private fun PassDetailChip(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MeshSatTextMuted)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MeshSatTextSecondary)
    }
}

@Composable
private fun PassRow(pass: PassPrediction) {
    val nowUnix = System.currentTimeMillis() / 1000
    val isActive = pass.aosUnix <= nowUnix && pass.losUnix >= nowUnix
    val isPast = pass.losUnix < nowUnix
    val alpha = if (isPast) 0.4f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) ColorIridium.copy(alpha = 0.1f)
                else MeshSatSurface.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .then(
                if (isActive) Modifier.border(1.dp, ColorIridium.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Active indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) ColorIridium
                    else MeshSatSurfaceLight.copy(alpha = alpha)
                )
        )

        // Satellite name
        Text(
            text = pass.satellite,
            style = MaterialTheme.typography.labelSmall,
            color = MeshSatTextPrimary.copy(alpha = alpha),
            modifier = Modifier.weight(1.2f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Time range
        Text(
            text = "${formatTimeUtc(pass.aosUnix)}-${formatTimeUtc(pass.losUnix)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MeshSatTextSecondary.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )

        // Duration
        Text(
            text = formatDurationMin(pass.durationMin),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MeshSatTextMuted.copy(alpha = alpha),
            modifier = Modifier.width(40.dp),
        )

        // Elevation bar + value
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MeshSatSurface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = min(1f, (pass.peakElevDeg / 90.0).toFloat()))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(elevationColor(pass.peakElevDeg).copy(alpha = alpha))
                )
            }
            Text(
                text = "${pass.peakElevDeg.roundToInt()}°",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MeshSatTextMuted.copy(alpha = alpha),
            )
        }
    }
}

private fun elevationColor(elev: Double): Color = when {
    elev >= 60 -> ColorIridium
    elev >= 30 -> SignalExcellent
    elev >= 15 -> MeshSatAmber
    else -> MeshSatTextMuted
}

private fun formatCountdown(seconds: Long): String {
    if (seconds <= 0) return "00:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private val utcTimeFormat = SimpleDateFormat("HH:mm", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private val utcDateFormat = SimpleDateFormat("dd MMM", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun formatTimeUtc(unix: Long): String = utcTimeFormat.format(Date(unix * 1000))
private fun formatDateShort(unix: Long): String = utcDateFormat.format(Date(unix * 1000))

private fun formatDurationMin(min: Double): String {
    val m = min.roundToInt()
    return if (m >= 60) "${m / 60}h${m % 60}m" else "${m}m"
}

private fun formatCacheAge(sec: Long): String = when {
    sec < 0 -> "No data"
    sec < 3600 -> "${sec / 60}m ago"
    sec < 86400 -> "${sec / 3600}h ago"
    else -> "${sec / 86400}d ago"
}
