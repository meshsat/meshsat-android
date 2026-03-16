package com.cubeos.meshsat.satellite

import kotlin.math.pow

/**
 * Parsed TLE orbital elements.
 */
data class TleElements(
    val name: String,
    val line1: String,
    val line2: String,
    val catalogNumber: Int,
    val epochYear: Int,        // 2-digit year
    val epochDay: Double,      // fractional day of year
    val meanMotionDot: Double, // rev/day²
    val meanMotionDDot: Double,// rev/day³
    val bstar: Double,         // drag coefficient (1/earth-radii)
    val inclination: Double,   // degrees
    val raan: Double,          // degrees
    val eccentricity: Double,  // dimensionless
    val argPerigee: Double,    // degrees
    val meanAnomaly: Double,   // degrees
    val meanMotion: Double,    // rev/day
    val epochJd: Double,       // Julian date of epoch
)

/**
 * Parses NORAD two-line element sets (TLE).
 */
object TleParser {

    /**
     * Parse 3-line TLE format (name + line1 + line2).
     * Returns null if parsing fails.
     */
    fun parse(name: String, line1: String, line2: String): TleElements? {
        if (line1.length < 69 || line2.length < 69) return null
        if (line1[0] != '1' || line2[0] != '2') return null

        return try {
            val catalogNumber = line1.substring(2, 7).trim().toInt()
            val epochYr = line1.substring(18, 20).trim().toInt()
            val epochDay = line1.substring(20, 32).trim().toDouble()
            val ndot = line1.substring(33, 43).trim().toDouble()
            val nddot = parseExponent(line1.substring(44, 52).trim())
            val bstar = parseExponent(line1.substring(53, 61).trim())

            val inclination = line2.substring(8, 16).trim().toDouble()
            val raan = line2.substring(17, 25).trim().toDouble()
            val eccentricity = ("0." + line2.substring(26, 33).trim()).toDouble()
            val argPerigee = line2.substring(34, 42).trim().toDouble()
            val meanAnomaly = line2.substring(43, 51).trim().toDouble()
            val meanMotion = line2.substring(52, 63).trim().toDouble()

            val fullYear = if (epochYr < 57) 2000 + epochYr else 1900 + epochYr
            val epochJd = julianDate(fullYear, epochDay)

            TleElements(
                name = name.trim(),
                line1 = line1,
                line2 = line2,
                catalogNumber = catalogNumber,
                epochYear = epochYr,
                epochDay = epochDay,
                meanMotionDot = ndot,
                meanMotionDDot = nddot,
                bstar = bstar,
                inclination = inclination,
                raan = raan,
                eccentricity = eccentricity,
                argPerigee = argPerigee,
                meanAnomaly = meanAnomaly,
                meanMotion = meanMotion,
                epochJd = epochJd,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse bulk 3-line format (from Celestrak).
     * Each satellite: name\nline1\nline2
     */
    fun parseMulti(text: String): List<TleElements> {
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val result = mutableListOf<TleElements>()

        var i = 0
        while (i + 2 < lines.size) {
            val name = lines[i]
            val l1 = lines[i + 1]
            val l2 = lines[i + 2]

            if (l1.startsWith("1 ") && l2.startsWith("2 ")) {
                parse(name, l1, l2)?.let { result.add(it) }
                i += 3
            } else {
                i++
            }
        }

        return result
    }

    /**
     * Parse TLE exponential notation: "12345-6" → 0.12345e-6
     */
    private fun parseExponent(s: String): Double {
        if (s.isBlank()) return 0.0
        val cleaned = s.replace(" ", "")
        if (cleaned == "00000-0" || cleaned == "00000+0") return 0.0

        // Format: [+-]NNNNN[+-]N → mantissa * 10^exp
        val signIdx = cleaned.indexOfLast { it == '+' || it == '-' }
        if (signIdx <= 0) return 0.0

        val mantissaStr = cleaned.substring(0, signIdx)
        val expStr = cleaned.substring(signIdx)

        val mantissa = "0.${mantissaStr.replace("+", "").replace("-", "")}".toDoubleOrNull() ?: return 0.0
        val exp = expStr.toIntOrNull() ?: return 0.0
        val sign = if (mantissaStr.startsWith("-")) -1.0 else 1.0

        return sign * mantissa * 10.0.pow(exp)
    }

    /**
     * Julian date from year + fractional day of year.
     */
    private fun julianDate(year: Int, dayOfYear: Double): Double {
        // J2000.0 = JD 2451545.0 = 2000 Jan 1.5 TT
        val a = (14 - 1) / 12
        val y = year + 4800 - a
        val m = 1 + 12 * a - 3
        val jd0 = 1 + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        return jd0.toDouble() - 0.5 + dayOfYear - 1.0
    }

    /**
     * Convert Julian date to Unix timestamp (seconds).
     */
    fun jdToUnix(jd: Double): Long {
        return ((jd - 2440587.5) * 86400.0).toLong()
    }

    /**
     * Convert Unix timestamp to Julian date.
     */
    fun unixToJd(unix: Long): Double {
        return unix.toDouble() / 86400.0 + 2440587.5
    }
}
