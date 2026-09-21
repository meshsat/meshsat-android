package net.meshsat.android.ble

import kotlin.math.roundToInt

/**
 * The battery of the phone's own MeshSat node, from the device metrics the node sends the phone
 * (MESHSAT-1315). The XIAO could not measure its supply; the T-Beam's power chip reports a
 * percentage and a voltage, and Meshtastic reports [EXTERNAL_POWER] while the node runs on USB.
 */
object NodeBattery {

    /** What Meshtastic sends as the battery level of a node on external power. */
    const val EXTERNAL_POWER = 101

    /** Readings older than this do not count towards the estimate. */
    const val WINDOW_MS = 3 * 3_600_000L

    /** An estimate needs this much time on battery, and [MIN_DROP] points of drop, behind it. */
    const val MIN_SPAN_MS = 30 * 60_000L
    const val MIN_DROP = 2

    data class Reading(val atMs: Long, val level: Int)

    /** True for a level that means a battery reading rather than external power or none. */
    fun isBatteryLevel(level: Int): Boolean = level in 0..100

    /**
     * Hours left at the rate the level has actually been falling: a least-squares line through the
     * battery readings of the last [WINDOW_MS] since the node last ran on external power. Null
     * until those readings span [MIN_SPAN_MS] and fell by [MIN_DROP] points, so nothing is shown
     * from a guess about the cell's capacity.
     */
    fun hoursLeft(readings: List<Reading>, nowMs: Long): Double? {
        val recent = readings.filter { it.atMs >= nowMs - WINDOW_MS }.sortedBy { it.atMs }
        val sinceUnplugged = recent.drop(recent.indexOfLast { it.level > 100 } + 1)
        val onBattery = sinceUnplugged.filter { isBatteryLevel(it.level) }
        if (onBattery.size < 3) return null
        if (onBattery.last().atMs - onBattery.first().atMs < MIN_SPAN_MS) return null
        if (onBattery.maxOf { it.level } - onBattery.last().level < MIN_DROP) return null

        val t0 = onBattery.first().atMs
        val xs = onBattery.map { (it.atMs - t0) / 3_600_000.0 }
        val ys = onBattery.map { it.level.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slopePerHour = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        if (slopePerHour >= 0) return null
        return onBattery.last().level / -slopePerHour
    }

    /** "about 40 min left", "about 14 h left", "about 3 days left". */
    fun timeLeftText(hours: Double): String = when {
        hours < 1 -> "about ${((hours * 60 / 5).roundToInt() * 5).coerceAtLeast(5)} min left"
        hours < 48 -> "about ${hours.roundToInt()} h left"
        else -> "about ${(hours / 24).roundToInt()} days left"
    }

    /**
     * The node's battery in a few words: "82%, 3.98 V, about 14 h left", or "On USB power"; null
     * when it has reported none.
     */
    fun describe(level: Int, voltage: Float, hoursLeft: Double?, withVoltage: Boolean = true): String? = when {
        level > 100 -> "On USB power"
        isBatteryLevel(level) -> buildString {
            append("$level%")
            if (withVoltage && voltage > 0f) append(", ${"%.2f".format(java.util.Locale.ROOT, voltage)} V")
            hoursLeft?.let { append(", ${timeLeftText(it)}") }
        }
        else -> null
    }

    /** A table cell: "82%", "USB", or "-" for a node that reports nothing. */
    fun cell(level: Int): String = when {
        level > 100 -> "USB"
        isBatteryLevel(level) -> "$level%"
        else -> "-"
    }
}
