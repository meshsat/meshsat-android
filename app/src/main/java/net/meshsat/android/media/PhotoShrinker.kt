package net.meshsat.android.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * A photo from the picker down to a WebP that fits an LXMF message (MESHSAT-1735): the long
 * edge at most [MAX_EDGE] px, then the quality and the edge come down until the bytes fit
 * the budget. What every client writes is "webp", so the far end shows it as it is.
 */
object PhotoShrinker {
    const val MAX_EDGE = 640
    private val QUALITIES = intArrayOf(70, 50, 35, 20)

    /** The WebP bytes within [budgetBytes], or null when the picture cannot be read. */
    fun shrink(context: Context, uri: Uri, budgetBytes: Int): ByteArray? {
        val src = load(context, uri) ?: return null
        try {
            var edge = MAX_EDGE
            while (edge >= 64) {
                val bm = scaled(src, edge)
                for (q in QUALITIES) {
                    val bytes = webp(bm, q)
                    if (bytes.size <= budgetBytes) { if (bm !== src) bm.recycle(); return bytes }
                }
                if (bm !== src) bm.recycle()
                edge /= 2
            }
            return null
        } finally {
            src.recycle()
        }
    }

    /** Shrinks WebP bytes already in hand (a received photo too big to keep as a thumbnail, for instance). */
    fun webp(bm: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bm.compress(format, quality, out)
        return out.toByteArray()
    }

    private fun scaled(src: Bitmap, edge: Int): Bitmap {
        val w = src.width; val h = src.height
        val longEdge = maxOf(w, h)
        if (longEdge <= edge) return src
        val f = edge.toDouble() / longEdge
        return Bitmap.createScaledBitmap(src, maxOf(1, (w * f).toInt()), maxOf(1, (h * f).toInt()), true)
    }

    private fun load(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the picture's own rotation and can decode straight to a small size.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longEdge = maxOf(info.size.width, info.size.height)
                if (longEdge > MAX_EDGE * 2) {
                    val f = (MAX_EDGE * 2).toDouble() / longEdge
                    decoder.setTargetSize(maxOf(1, (info.size.width * f).toInt()), maxOf(1, (info.size.height * f).toInt()))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    } catch (_: Exception) { null }
}
