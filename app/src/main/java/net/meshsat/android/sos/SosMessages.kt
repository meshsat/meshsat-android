package net.meshsat.android.sos

import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What an SOS says on each route (MESHSAT-1249).
 *
 * The Hub raises an alarm for any incoming text that contains one of [HUB_ALARM_WORDS], anywhere and
 * in any case (meshsat-hub internal/sos/detector.go), and a MeshSat kit on the same mesh forwards what
 * it hears to the Hub. So an SOS says "SOS", and a test or a cancellation must never contain one of
 * those words, not even inside the sender's name.
 */
object SosMessages {

    val HUB_ALARM_WORDS = listOf("SOS", "MAYDAY", "EMERGENCY")

    /** Longest name used in a message, so an SMS stays in one part. */
    const val MAX_NAME = 24

    /** Longest text inside the satellite frame (the Bridge's maxSOSMessageLen). */
    private const val MAX_FRAME_MESSAGE = 64
    private const val MAX_FRAME_ID = 16

    /** A position: where, how precise, and when the phone measured it. */
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long)

    /** A fix older than this is called the last known position. */
    private const val STALE_FIX_MS = 2 * 60_000L

    fun containsAlarmWord(text: String): Boolean {
        val upper = text.uppercase(Locale.ROOT)
        return HUB_ALARM_WORDS.any { upper.contains(it) }
    }

    /** The user's name as it goes into a message: printable, trimmed, short. */
    fun cleanName(name: String): String {
        val printable = name.filter { !it.isISOControl() }.trim()
        return printable.take(MAX_NAME).trim().ifEmpty { "A MeshSat user" }
    }

    /** The name for a message that must not raise an alarm. */
    private fun quietName(name: String): String {
        val clean = cleanName(name)
        return if (containsAlarmWord(clean)) "This phone" else clean
    }

    fun coordinates(fix: Fix): String = String.format(Locale.ROOT, "%.5f, %.5f", fix.lat, fix.lon)

    private fun utcTime(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " UTC"

    /** "At 52.16207, 4.50974 (within 12 m) at 14:03 UTC.", or the last position, or none. */
    fun whereText(fix: Fix?, nowMs: Long): String {
        if (fix == null) return "Position unknown."
        val within = fix.accuracyM?.takeIf { it > 0f }?.let { " (within ${Math.round(it)} m)" } ?: ""
        val lead = if (nowMs - fix.timeMs > STALE_FIX_MS) "Last position" else "At"
        return "$lead ${coordinates(fix)}$within at ${utcTime(fix.timeMs)}."
    }

    /** Broadcast on the mesh. */
    fun meshText(name: String, fix: Fix?, nowMs: Long): String =
        "SOS: ${cleanName(name)} needs help. ${whereText(fix, nowMs)}"

    /**
     * To each emergency contact, from the phone's own SIM: plain ASCII with a map link, at most 160
     * characters so it goes as one SMS whatever the name and the position.
     */
    fun smsText(name: String, fix: Fix?, nowMs: Long): String {
        val base = "SOS: ${cleanName(name).filter { it.code in 32..126 }.ifEmpty { "A MeshSat user" }} needs help. ${whereText(fix, nowMs)}"
        if (fix == null) return "$base Sent by MeshSat."
        val lat = String.format(Locale.ROOT, "%.5f", fix.lat)
        val lon = String.format(Locale.ROOT, "%.5f", fix.lon)
        return "$base https://osm.org/?mlat=$lat&mlon=$lon"
    }

    /** The alert text the Hub shows for the satellite frame. */
    fun frameMessage(name: String, fix: Fix?): String {
        val text = "SOS: ${cleanName(name)} needs help" + if (fix == null) ", position unknown" else ""
        return truncateUtf8(text, MAX_FRAME_MESSAGE)
    }

    /** Sent on every route that carried the SOS, once the user cancels it. */
    fun cancelText(name: String): String = "Alarm cancelled: ${quietName(name)} is safe and needs no help now."

    /** A test of the alarm routes: says it is a test and raises nothing at the Hub. */
    fun testText(name: String): String = "Test from ${quietName(name)}: checking the MeshSat alarm routes. No help needed."

    /**
     * The SOS frame the Hub decodes from any bearer (magic "MS", type 0x02), byte for byte the
     * Bridge's EncodeSatSOS (meshsat internal/hubreporter/satuplink.go): ids and message are
     * length-prefixed and cut to 16, 16 and 64 bytes, positions are big-endian float32, the time is
     * uint32 Unix seconds. Without a fix the position is 0, 0, as on the Bridge, and the message says
     * the position is unknown.
     */
    fun satFrame(bridgeId: String, deviceId: String, fix: Fix?, message: String, nowSec: Long): ByteArray {
        val bridge = truncateUtf8(bridgeId, MAX_FRAME_ID).toByteArray(Charsets.UTF_8)
        val device = truncateUtf8(deviceId, MAX_FRAME_ID).toByteArray(Charsets.UTF_8)
        val msg = truncateUtf8(message, MAX_FRAME_MESSAGE).toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 1 + bridge.size + 1 + device.size + 4 + 4 + 1 + msg.size + 4)
        buf.put(0x4D).put(0x53).put(1).put(0x02)
        buf.put(bridge.size.toByte()).put(bridge)
        buf.put(device.size.toByte()).put(device)
        buf.putFloat((fix?.lat ?: 0.0).toFloat())
        buf.putFloat((fix?.lon ?: 0.0).toFloat())
        buf.put(msg.size.toByte()).put(msg)
        buf.putInt(nowSec.toInt())
        return buf.array()
    }

    /**
     * A position report the Hub decodes from any bearer (magic "MS", type 0x01), byte for byte the
     * Bridge's EncodeSatPosition: the alarm test's satellite leg. It crosses the same modem, Rock7 and
     * Hub uplink decoder as the SOS frame, lands on the position topic and the bridge's "last report",
     * and never reaches the Hub's routing engine, where a plain text could be relayed to real phones.
     * Height in whole metres, cut toward zero like Go's int16(float32); source 1 is GPS.
     */
    fun positionFrame(bridgeId: String, fix: Fix, altitudeM: Double, nowSec: Long): ByteArray {
        val bridge = truncateUtf8(bridgeId, MAX_FRAME_ID).toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 1 + bridge.size + 4 + 4 + 2 + 1 + 4)
        buf.put(0x4D).put(0x53).put(1).put(0x01)
        buf.put(bridge.size.toByte()).put(bridge)
        buf.putFloat(fix.lat.toFloat())
        buf.putFloat(fix.lon.toFloat())
        buf.putShort(altitudeM.toFloat().toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        buf.put(1)
        buf.putInt(nowSec.toInt())
        return buf.array()
    }

    /** Cut [s] to at most [maxBytes] of UTF-8 without splitting a character. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            out.appendCodePoint(cp)
            used += bytes
            i += chars
        }
        return out.toString()
    }
}
