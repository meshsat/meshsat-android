package net.meshsat.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import net.meshsat.android.ui.components.SkyChart
import net.meshsat.android.ui.components.SkySession
import net.meshsat.android.ui.components.SkySignal
import kotlinx.coroutines.flow.first
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
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.satellite.PassPrediction
import net.meshsat.android.satellite.PassPredictor
import net.meshsat.android.satellite.TleFetcher
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.SignalExcellent
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
import net.meshsat.android.ui.theme.PlexMono

// Elevation environment presets matching Go web frontend
private data class ElevPreset(val value: Int, val label: String, val desc: String)

private val ELEV_PRESETS = listOf(
    ElevPreset(5, "Open", "Open field or rooftop"),
    ElevPreset(20, "Trees", "Some trees or low buildings"),
    ElevPreset(40, "City", "Tall buildings, narrow streets"),
    ElevPreset(60, "Canyon", "Deep valley or dense city"),
)

// 72 h is gone: on a phone its passes are hairlines (MESHSAT-1300). 12 h is the default for the same reason.
private val WINDOW_OPTIONS = listOf(6, 12, 24, 48)

@Composable
fun PassPredictorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val fetcher = remember { TleFetcher.forContext(context, db) }

    // State
    var passes by remember { mutableStateOf<List<PassPrediction>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var cacheAgeSec by remember { mutableLongStateOf(-1L) }
    var tleSource by remember { mutableStateOf(TleFetcher.Source.None) }
    var windowHours by remember { mutableIntStateOf(12) }
    var skySignals by remember { mutableStateOf<List<SkySignal>>(emptyList()) }
    var skySessions by remember { mutableStateOf<List<SkySession>>(emptyList()) }
    // The past half of the window: what the modem actually heard, and its sessions.
    LaunchedEffect(windowHours) {
        while (true) {
            withContext(Dispatchers.IO) {
                val since = System.currentTimeMillis() - windowHours * 3600_000L / 2
                skySignals = db.signalDao().getSince("iridium", since).first().map { SkySignal(it.timestamp / 1000, it.value) }
                skySessions = db.signalDao().getSince("gss", since).first().map { SkySession(it.timestamp / 1000, it.value >= 1) }
            }
            delay(60_000)
        }
    }
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
                val loc = lm?.let { net.meshsat.android.location.LocationFixes.freshest(it) }
                if (loc != null) {
                    lat = loc.latitude
                    lon = loc.longitude
                    hasLocation = true
                    locationSource = when (loc.provider) {
                        LocationManager.GPS_PROVIDER -> "GPS"
                        LocationManager.NETWORK_PROVIDER -> "Network"
                        else -> "Fused"
                    }
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
                // Offline first: the last download or the snapshot shipped in the app.
                val set = fetcher.localTles()
                val tles = set.tles
                cacheAgeSec = set.ageSec()
                tleSource = set.source
                if (tles.isEmpty()) {
                    errorMsg = "No orbit data. Tap Refresh TLEs when online."
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

    // Laid out for a small phone (MESHSAT-1300): what matters first - the pass overhead or the
    // next one - then the chart, then the settings that change it, and the bookkeeping last.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (activePass != null) {
            item {
                PassBanner(
                    label = "Overhead now",
                    pass = activePass,
                    accentColor = SignalExcellent,
                    subtitle = "A message can go out now.",
                )
            }
        } else if (nextPass != null) {
            item {
                PassBanner(
                    label = "Next pass",
                    pass = nextPass,
                    accentColor = ColorIridium,
                    showCountdown = true,
                    countdownText = countdownText,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedChoice(
                    options = WINDOW_OPTIONS,
                    selected = windowHours,
                    label = { "$it h" },
                    onSelect = { windowHours = it },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MeshSatSurface, RoundedCornerShape(8.dp))
                        .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    when {
                        loading -> Row(
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(color = ColorIridium, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Working out the passes", color = MeshSatTextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        passes.isEmpty() -> Text(
                            when {
                                errorMsg != null -> errorMsg!!
                                !hasLocation -> "No position yet. Allow location, or wait for a fix."
                                else -> "No passes above ${minElevDeg}\u00B0 in this window."
                            },
                            color = if (errorMsg != null) MeshSatRed else MeshSatTextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                        else -> SkyChart(
                            passes = passes,
                            signals = skySignals,
                            sessions = skySessions,
                            startSec = nowUnix - windowHours * 3600L / 2,
                            endSec = nowUnix + windowHours * 3600L / 2,
                            nowSec = nowUnix,
                            compact = false,
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your surroundings", style = MaterialTheme.typography.labelMedium, color = MeshSatTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ELEV_PRESETS.forEach { p ->
                        val on = minElevDeg == p.value
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) ColorIridium.copy(alpha = 0.18f) else MeshSatSurface)
                                .border(1.dp, if (on) ColorIridium.copy(alpha = 0.5f) else MeshSatBorder, RoundedCornerShape(8.dp))
                                .clickable { minElevDeg = p.value }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                "${p.value}\u00B0",
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = PlexMono,
                                color = if (on) ColorIridium else MeshSatTextSecondary,
                            )
                            Text(
                                p.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (on) ColorIridium else MeshSatTextMuted,
                            )
                        }
                    }
                }
                val chosen = ELEV_PRESETS.firstOrNull { it.value == minElevDeg }
                Text(
                    "${chosen?.desc ?: "Custom"}: counts the passes that climb above ${minElevDeg}\u00B0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }

        if (!loading && passes.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MeshSatSurface)
                        .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                        .clickable { expandedPassList = !expandedPassList }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Every pass in the window (${passes.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatTextPrimary,
                    )
                    Icon(
                        if (expandedPassList) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expandedPassList) "Hide the passes" else "Show the passes",
                        tint = MeshSatTextMuted,
                    )
                }
            }
        }
        if (expandedPassList && !loading) {
            items(passes, key = { "${it.satellite}-${it.aosUnix}" }) { pass -> PassRow(pass) }
        }

        // The bookkeeping, quiet and last
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (hasLocation) SignalExcellent else MeshSatRed),
                    )
                    Text(
                        text = if (hasLocation)
                            "Position from $locationSource, ${String.format(java.util.Locale.ROOT, "%.4f, %.4f", lat, lon)}"
                        else
                            "No position: allow location for predictions",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasLocation) MeshSatTextMuted else MeshSatRed,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Orbit data: ${formatTleSource(tleSource, cacheAgeSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshSatTextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !refreshing,
                        onClick = {
                            scope.launch {
                                refreshing = true
                                val got = fetcher.refreshFromNetwork()
                                refreshing = false
                                if (got == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Could not download new orbit data; predicting with the data on the phone.",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                                computePasses()
                            }
                        },
                    ) {
                        Text(if (refreshing) "Updating" else "Update", style = MaterialTheme.typography.labelMedium, color = ColorIridium)
                    }
                }
            }
        }
    }
}

/** A row of equal choices, the chosen one filled: sized to the screen, never cut off. */
@Composable
private fun <T> SegmentedChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MeshSatSurface)
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) ColorIridium.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(o) },
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = PlexMono,
                    color = if (on) ColorIridium else MeshSatTextMuted,
                )
            }
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
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor.copy(alpha = 0.8f),
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
                        fontFamily = PlexMono,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                }
                Text(
                    formatTimeUtc(pass.aosUnix),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = PlexMono,
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
            fontFamily = PlexMono,
            color = MeshSatTextSecondary.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )

        // Duration
        Text(
            text = formatDurationMin(pass.durationMin),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = PlexMono,
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
                fontFamily = PlexMono,
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
    sec < 3600 -> "${sec / 60}m old"
    sec < 86400 -> "${sec / 3600}h old"
    else -> "${sec / 86400}d old"
}

/** Where the elements came from and how old the newest one is. */
private fun formatTleSource(source: TleFetcher.Source, ageSec: Long): String = when (source) {
    TleFetcher.Source.None -> "No data"
    TleFetcher.Source.Downloaded -> "downloaded, ${formatCacheAge(ageSec)}"
    TleFetcher.Source.Bundled -> "built-in, ${formatCacheAge(ageSec)}"
}
