package net.meshsat.android.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import net.meshsat.android.ui.theme.MeshSatBg
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import org.osmdroid.views.overlay.Marker
import kotlin.math.ceil
import kotlin.math.max

/** A marker bitmap and the point of it that sits on the position (fractions of its size). */
class MarkerIcon(val drawable: BitmapDrawable, val anchorU: Float, val anchorV: Float) {
    fun applyTo(marker: Marker) {
        marker.icon = drawable
        marker.setAnchor(anchorU, anchorV)
    }
}

/**
 * Draws the map's markers (MESHSAT-1249): a diamond for a node, with its full name under it on a dark
 * pill, cut with an ellipsis only when it is wider than [maxLabelPx]; a round dot for this phone.
 * Colours come in as ARGB ints made from the theme tokens.
 *
 * [labelPx] is the label text size in pixels. The caller converts 12 sp, so labels follow the user's
 * font size; [density] sizes the shapes. Bitmaps are cached by what they show.
 */
class MarkerPainter(
    private val resources: Resources,
    private val labelPx: Float,
    private val maxLabelPx: Float,
    private val density: Float,
) {
    private val cache = HashMap<String, MarkerIcon>()

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = labelPx
        textAlign = Paint.Align.LEFT
    }

    /** A node: a diamond in [fill], faded when [stale], with [label] under it. */
    fun node(label: String, fill: Int, stale: Boolean): MarkerIcon {
        val key = "node|$label|$fill|$stale"
        cache[key]?.let { return it }
        if (cache.size > 300) cache.clear()

        val symbol = (22 * density).toInt()
        val stroke = 2 * density
        val text = TextUtils.ellipsize(label, textPaint, maxLabelPx, TextUtils.TruncateAt.END).toString()
        val padH = 6 * density
        val padV = 3 * density
        val gap = 2 * density
        val metrics = textPaint.fontMetrics
        val textH = metrics.descent - metrics.ascent
        val textW = textPaint.measureText(text)
        val pillW = textW + 2 * padH
        val pillH = textH + 2 * padV

        val width = max(symbol.toFloat(), pillW).let { ceil(it).toInt() }
        val height = ceil(symbol + gap + pillH).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Diamond, centred at the top.
        val cx = width / 2f
        val cy = symbol / 2f
        val r = symbol / 2f - stroke
        val diamond = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx + r, cy)
            lineTo(cx, cy + r)
            lineTo(cx - r, cy)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.alpha = if (stale) 115 else 255
        canvas.drawPath(diamond, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = MeshSatBg.toArgb()
        canvas.drawPath(diamond, paint)

        // Name on a dark pill, so it reads on any tile.
        val top = symbol + gap
        val left = (width - pillW) / 2f
        paint.style = Paint.Style.FILL
        paint.color = MeshSatBg.copy(alpha = 0.8f).toArgb()
        canvas.drawRoundRect(RectF(left, top, left + pillW, top + pillH), 4 * density, 4 * density, paint)
        textPaint.color = (if (stale) MeshSatTextMuted else MeshSatTextPrimary).toArgb()
        canvas.drawText(text, left + padH, top + padV - metrics.ascent, textPaint)

        return MarkerIcon(BitmapDrawable(resources, bitmap), 0.5f, cy / height).also { cache[key] = it }
    }

    /** A round dot in [fill] with a dark ring, anchored at its centre (this phone, a zone's centre). */
    fun dot(fill: Int, sizeDp: Int): MarkerIcon {
        val key = "dot|$fill|$sizeDp"
        cache[key]?.let { return it }
        val size = (sizeDp * density).toInt()
        val stroke = 3 * density
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = size / 2f
        paint.style = Paint.Style.FILL
        paint.color = MeshSatBg.toArgb()
        canvas.drawCircle(c, c, c, paint)
        paint.color = fill
        canvas.drawCircle(c, c, c - stroke, paint)
        return MarkerIcon(BitmapDrawable(resources, bitmap), 0.5f, 0.5f).also { cache[key] = it }
    }
}
