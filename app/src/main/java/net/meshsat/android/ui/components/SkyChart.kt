package net.meshsat.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import net.meshsat.android.satellite.PassPrediction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil

/** One Iridium signal reading, 0 to 5 bars, at a unix second. */
data class SkySignal(val atSec: Long, val bars: Int)

/** One satellite session (SBDIX) and whether the gateway took it: the Bridge's "GSS" dots. */
data class SkySession(val atSec: Long, val ok: Boolean)

/**
 * The geometry of the chart, pure so it has tests. It follows the Bridge's `PassesView.vue` and
 * `DashboardView.vue` (MESHSAT-1300): a pass is a triangle from AOS to LOS whose apex is its peak
 * elevation on a 0-90 degree scale, signal readings sit on a 0-5 bar scale over the same time
 * axis, and sessions are dots on the baseline.
 */
internal object SkyGeometry {

    fun x(tsSec: Long, startSec: Long, endSec: Long, left: Float, width: Float): Float =
        left + ((tsSec - startSec).toFloat() / (endSec - startSec).toFloat()) * width

    fun elevY(deg: Double, bottom: Float, height: Float): Float = bottom - (deg.toFloat() / 90f) * height

    fun barsY(bars: Int, bottom: Float, height: Float): Float = bottom - (bars.coerceIn(0, 5) / 5f) * height

    data class Triangle(val x1: Float, val xMid: Float, val x2: Float, val peakY: Float)

    /**
     * AOS and LOS on the baseline, clipped to the plot's sides, the apex half way between them at
     * the peak elevation. The Bridge clips first and then finds the middle, and so does this.
     */
    fun triangle(
        p: PassPrediction, startSec: Long, endSec: Long,
        left: Float, width: Float, bottom: Float, height: Float,
    ): Triangle {
        val x1 = maxOf(left, x(p.aosUnix, startSec, endSec, left, width))
        val x2 = minOf(left + width, x(p.losUnix, startSec, endSec, left, width))
        return Triangle(x1, (x1 + x2) / 2f, x2, elevY(p.peakElevDeg, bottom, height))
    }

    /** Green from 3 bars, amber at 1 and 2, red at 0 - the Bridge's thresholds. */
    fun signalArgb(bars: Int): Long = when {
        bars >= 3 -> 0xFF10B981
        bars >= 1 -> 0xFFF59E0B
        else -> 0xFFEF4444
    }

    /** Label times on whole multiples of [stepSec] inside the window. */
    fun ticks(startSec: Long, endSec: Long, stepSec: Long): List<Long> {
        val out = mutableListOf<Long>()
        var t = ceil(startSec.toDouble() / stepSec).toLong() * stepSec
        while (t < endSec) {
            out += t
            t += stepSec
        }
        return out
    }

    fun overlaps(p: PassPrediction, startSec: Long, endSec: Long): Boolean = p.losUnix > startSec && p.aosUnix < endSec
}

private val Indigo = Color(0xFF818CF8)
private val IndigoActive = Color(0xFFA5B4FC)
private val SignalGreen = Color(0xFF10B981)
private val NowAmber = Color(0xFFF59E0B)
private val SessionOk = Color(0xFFE879F9)
private val SessionFail = Color(0xFFF87171)
private val Grid = Color(0xFF374151)

private val hhmm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * Predicted passes with the modem's real signal and its satellite sessions on one time axis: the
 * Bridge's "Signal vs passes" chart (MESHSAT-1300). [compact] is the Home widget - the Bridge's
 * Iridium card - and the full size is the passes screen, with both scales and tap to inspect.
 */
@Composable
fun SkyChart(
    passes: List<PassPrediction>,
    signals: List<SkySignal>,
    sessions: List<SkySession>,
    startSec: Long,
    endSec: Long,
    nowSec: Long,
    compact: Boolean,
    modifier: Modifier = Modifier,
    windowLabel: String? = null,
) {
    val density = LocalDensity.current.density
    val visible = remember(passes, startSec, endSec) { passes.filter { SkyGeometry.overlaps(it, startSec, endSec) } }
    var tapX by remember { mutableStateOf<Float?>(null) }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 104.dp else 240.dp)
                .then(
                    if (compact) Modifier
                    else Modifier.pointerInput(Unit) { detectTapGestures { tapX = if (tapX == null) it.x else null } },
                ),
        ) {
            val w = size.width
            val h = size.height
            val padL = (if (compact) 8f else 30f) * density
            val padR = (if (compact) 8f else 30f) * density
            val plotTop = (if (compact) 6f else 14f) * density
            val plotBottom = h - (if (compact) 14f else 22f) * density
            val plotW = w - padL - padR
            val plotH = plotBottom - plotTop
            fun xOf(ts: Long) = SkyGeometry.x(ts, startSec, endSec, padL, plotW)

            val labelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = (if (compact) 8f else 9f) * density
                color = android.graphics.Color.parseColor(if (compact) "#4B5563" else "#6B7280")
            }

            // Grid at the bar positions
            val dash = PathEffect.dashPathEffect(floatArrayOf(2f * density, 3f * density), 0f)
            for (v in (if (compact) 1..5 else 0..5)) {
                val y = SkyGeometry.barsY(v, plotBottom, plotH)
                drawLine(Grid, Offset(padL, y), Offset(w - padR, y), strokeWidth = 0.5f * density, pathEffect = dash)
            }

            // Both scales on the full chart: bars on the left, degrees on the right
            if (!compact) {
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                for (v in 0..5) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "$v", padL - 5f * density, SkyGeometry.barsY(v, plotBottom, plotH) + 3f * density, labelPaint,
                    )
                }
                drawContext.canvas.nativeCanvas.drawText("bars", padL - 5f * density, plotTop - 4f * density, labelPaint)
                val degPaint = android.graphics.Paint(labelPaint).apply {
                    textAlign = android.graphics.Paint.Align.LEFT
                    color = android.graphics.Color.argb(128, 0x81, 0x8C, 0xF8)
                }
                for (d in listOf(0, 15, 30, 45, 60, 75, 90)) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "$d", w - padR + 5f * density, SkyGeometry.elevY(d.toDouble(), plotBottom, plotH) + 3f * density, degPaint,
                    )
                }
                drawContext.canvas.nativeCanvas.drawText("deg", w - padR + 5f * density, plotTop - 4f * density, degPaint)
            }

            clipRect(padL, plotTop, w - padR, plotBottom) {
                // Pass triangles, the background layer
                val peakPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = (if (compact) 7f else 8f) * density
                    textAlign = android.graphics.Paint.Align.CENTER
                    color = android.graphics.Color.argb(153, 0xA5, 0xB4, 0xFC)
                }
                for (p in visible) {
                    val t = SkyGeometry.triangle(p, startSec, endSec, padL, plotW, plotBottom, plotH)
                    val path = Path().apply {
                        moveTo(t.x1, plotBottom)
                        lineTo(t.xMid, t.peakY)
                        lineTo(t.x2, plotBottom)
                        close()
                    }
                    val base = if (p.isActive) IndigoActive else Indigo
                    if (compact) {
                        drawPath(path, base.copy(alpha = if (p.isActive) 0.35f else 0.15f))
                    } else {
                        drawPath(
                            path,
                            Brush.verticalGradient(
                                listOf(base.copy(alpha = if (p.isActive) 0.50f else 0.30f), base.copy(alpha = if (p.isActive) 0.08f else 0.03f)),
                                startY = t.peakY,
                                endY = plotBottom,
                            ),
                        )
                    }
                    drawPath(
                        path,
                        base.copy(alpha = if (p.isActive) 0.5f else 0.2f),
                        style = Stroke(width = (if (compact) 0.5f else 1f) * density),
                    )
                    if (t.x2 - t.x1 > (if (compact) 15f else 20f) * density) {
                        drawContext.canvas.nativeCanvas.drawText(
                            p.peakElevDeg.toInt().toString(), t.xMid, t.peakY - 3f * density, peakPaint,
                        )
                    }
                }

                // Signal: soft area, the line, then a dot per reading coloured by strength
                val pts = signals.sortedBy { it.atSec }
                    .filter { it.atSec in startSec..endSec }
                    .map { Offset(xOf(it.atSec), SkyGeometry.barsY(it.bars, plotBottom, plotH)) to it.bars }
                if (pts.size > 1) {
                    val area = Path().apply {
                        moveTo(pts.first().first.x, plotBottom)
                        pts.forEach { lineTo(it.first.x, it.first.y) }
                        lineTo(pts.last().first.x, plotBottom)
                        close()
                    }
                    if (compact) drawPath(area, SignalGreen.copy(alpha = 0.08f))
                    else drawPath(area, Brush.verticalGradient(listOf(SignalGreen.copy(alpha = 0.15f), SignalGreen.copy(alpha = 0.02f)), startY = plotTop, endY = plotBottom))
                    val line = Path().apply {
                        moveTo(pts.first().first.x, pts.first().first.y)
                        pts.drop(1).forEach { lineTo(it.first.x, it.first.y) }
                    }
                    drawPath(line, SignalGreen.copy(alpha = 0.7f), style = Stroke(width = (if (compact) 1.2f else 1.5f) * density))
                }
                for ((o, bars) in pts) {
                    drawCircle(Color(SkyGeometry.signalArgb(bars)).copy(alpha = 0.85f), radius = (if (compact) 1.5f else 2.5f) * density, center = o)
                }

                // Satellite sessions on the baseline
                for (s in sessions) {
                    if (s.atSec !in startSec..endSec) continue
                    drawCircle(
                        if (s.ok) SessionOk.copy(alpha = 0.9f) else SessionFail.copy(alpha = 0.7f),
                        radius = (if (compact) 2f else 3f) * density,
                        center = Offset(xOf(s.atSec), plotBottom - (if (compact) 4f else 6f) * density),
                    )
                }

                // Now
                val nowX = xOf(nowSec)
                drawLine(
                    NowAmber.copy(alpha = if (compact) 0.5f else 0.6f), Offset(nowX, plotTop), Offset(nowX, plotBottom),
                    strokeWidth = (if (compact) 0.5f else 1f) * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 2f * density), 0f),
                )
            }

            // Time labels: every hour on the widget, every 3 h (6 h past a day) on the screen
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
            val span = endSec - startSec
            val step = if (compact) 3600L else if (span <= 24 * 3600L) 3 * 3600L else 6 * 3600L
            val nowX = xOf(nowSec)
            for (t in SkyGeometry.ticks(startSec, endSec, step)) {
                val x = xOf(t)
                if (!compact && abs(x - nowX) < 18f * density) continue
                drawContext.canvas.nativeCanvas.drawText(hhmm.format(Date(t * 1000)), x, h - 3f * density, labelPaint)
            }
            if (!compact) {
                val nowPaint = android.graphics.Paint(labelPaint).apply { color = android.graphics.Color.parseColor("#F59E0B") }
                drawContext.canvas.nativeCanvas.drawText("now", nowX, h - 3f * density, nowPaint)
            }

            // Tap to inspect (the Bridge's hover): the time, the highest pass there, the nearest reading
            val tx = tapX
            if (!compact && tx != null && tx in padL..(w - padR)) {
                drawLine(Color(0xFF9CA3AF).copy(alpha = 0.5f), Offset(tx, plotTop), Offset(tx, plotBottom), strokeWidth = 0.5f * density)
                val ts = startSec + ((tx - padL) / plotW * (endSec - startSec)).toLong()
                val over = visible.filter { ts in it.aosUnix..it.losUnix }.maxByOrNull { it.peakElevDeg }
                val near = signals.minByOrNull { abs(it.atSec - ts) }?.takeIf { abs(it.atSec - ts) < 15 * 60 }
                val lines = buildList {
                    add("${hhmm.format(Date(ts * 1000))} UTC")
                    if (over != null) add("${over.satellite} ${over.peakElevDeg.toInt()}°")
                    if (near != null) add("Signal: ${near.bars} bars")
                }
                val tip = android.graphics.Paint(labelPaint).apply {
                    textAlign = android.graphics.Paint.Align.LEFT
                    color = android.graphics.Color.parseColor("#D1D5DB")
                }
                val boxW = 120f * density
                val boxX = if (tx > w / 2) tx - boxW - 6f * density else tx + 6f * density
                val lineH = 12f * density
                drawRect(Color(0xFF1F2937).copy(alpha = 0.95f), Offset(boxX, plotTop + 2f * density), androidx.compose.ui.geometry.Size(boxW, lines.size * lineH + 6f * density))
                lines.forEachIndexed { i, s ->
                    drawContext.canvas.nativeCanvas.drawText(s, boxX + 6f * density, plotTop + 2f * density + lineH * (i + 1), tip)
                }
            }
        }
        // Legend, as on the Bridge
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendItem("▲", Indigo.copy(alpha = 0.6f), "Pass")
                LegendItem("●", SignalGreen, if (compact) "Signal" else "Signal")
                LegendItem("●", SessionOk, if (compact) "Session" else "Session OK")
                if (!compact) LegendItem("●", SessionFail, "Session failed")
            }
            if (windowLabel != null) {
                Text(windowLabel, style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B7280))
            }
        }
    }
}

@Composable
private fun LegendItem(mark: String, markColor: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mark, style = MaterialTheme.typography.labelSmall, color = markColor)
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B7280))
    }
}
