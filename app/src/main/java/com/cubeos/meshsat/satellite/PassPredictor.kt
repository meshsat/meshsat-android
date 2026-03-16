package com.cubeos.meshsat.satellite

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Satellite pass prediction result.
 */
data class PassPrediction(
    val satellite: String,
    val aosUnix: Long,           // Acquisition of Signal (unix seconds)
    val losUnix: Long,           // Loss of Signal (unix seconds)
    val durationMin: Double,     // pass duration in minutes
    val peakElevDeg: Double,     // max elevation above horizon (degrees)
    val peakAzimuthDeg: Double,  // azimuth at peak elevation (degrees)
    val isActive: Boolean,       // satellite currently overhead
)

/**
 * Predicts satellite passes using SGP4 propagation.
 */
object PassPredictor {

    private const val DEG2RAD = PI / 180.0
    private const val RAD2DEG = 180.0 / PI
    private const val EARTH_RADIUS_KM = 6378.137
    private const val EARTH_FLATTENING = 1.0 / 298.257223563
    private const val SIDEREAL_DAY_SEC = 86164.0905  // sidereal day in seconds
    private const val STEP_SEC = 60L            // scan resolution: 60 seconds
    private const val REFINE_SEC = 5L           // refinement resolution: 5 seconds

    /**
     * Compute all passes for a single satellite within a time window.
     *
     * @param tle Parsed TLE elements
     * @param lat Observer latitude (degrees, +N)
     * @param lon Observer longitude (degrees, +E)
     * @param altKm Observer altitude (km above sea level)
     * @param startUnix Start of prediction window (unix seconds)
     * @param endUnix End of prediction window (unix seconds)
     * @param minElevDeg Minimum peak elevation to include (degrees)
     */
    fun predictPasses(
        tle: TleElements,
        lat: Double,
        lon: Double,
        altKm: Double,
        startUnix: Long,
        endUnix: Long,
        minElevDeg: Double = 5.0,
    ): List<PassPrediction> {
        val passes = mutableListOf<PassPrediction>()
        val epochUnix = TleParser.jdToUnix(tle.epochJd)

        var t = startUnix
        var inPass = false
        var passStart = 0L
        var peakElev = -90.0
        var peakAz = 0.0

        while (t <= endUnix) {
            val tsince = (t - epochUnix) / 60.0  // minutes since TLE epoch
            val pos = Sgp4.propagate(tle, tsince)
            val elev = if (pos != null) elevationDeg(pos, lat, lon, altKm, t) else -90.0

            if (elev > 0.0) {
                if (!inPass) {
                    // AOS: refine backwards to find exact crossing
                    passStart = refineAos(tle, epochUnix, lat, lon, altKm, t - STEP_SEC, t)
                    inPass = true
                    peakElev = elev
                    peakAz = azimuthDeg(pos!!, lat, lon, t)
                }
                if (elev > peakElev) {
                    peakElev = elev
                    peakAz = azimuthDeg(pos!!, lat, lon, t)
                }
            } else if (inPass) {
                // LOS: refine forward to find exact crossing
                val passEnd = refineLos(tle, epochUnix, lat, lon, altKm, t - STEP_SEC, t)

                if (peakElev >= minElevDeg) {
                    val nowUnix = System.currentTimeMillis() / 1000
                    passes.add(PassPrediction(
                        satellite = tle.name,
                        aosUnix = passStart,
                        losUnix = passEnd,
                        durationMin = (passEnd - passStart) / 60.0,
                        peakElevDeg = peakElev,
                        peakAzimuthDeg = peakAz,
                        isActive = passStart <= nowUnix && nowUnix <= passEnd,
                    ))
                }
                inPass = false
                peakElev = -90.0
            }

            t += STEP_SEC
        }

        // Close any open pass at window end
        if (inPass && peakElev >= minElevDeg) {
            val nowUnix = System.currentTimeMillis() / 1000
            passes.add(PassPrediction(
                satellite = tle.name,
                aosUnix = passStart,
                losUnix = endUnix,
                durationMin = (endUnix - passStart) / 60.0,
                peakElevDeg = peakElev,
                peakAzimuthDeg = peakAz,
                isActive = passStart <= nowUnix && nowUnix <= endUnix,
            ))
        }

        return passes
    }

    /**
     * Predict passes for all satellites in a TLE set.
     */
    fun predictAllPasses(
        tles: List<TleElements>,
        lat: Double,
        lon: Double,
        altKm: Double,
        startUnix: Long,
        endUnix: Long,
        minElevDeg: Double = 5.0,
    ): List<PassPrediction> {
        return tles.flatMap { tle ->
            try {
                predictPasses(tle, lat, lon, altKm, startUnix, endUnix, minElevDeg)
            } catch (_: Exception) {
                emptyList()
            }
        }.sortedBy { it.aosUnix }
    }

    /**
     * Refine AOS by binary search between two timestamps where elevation crosses 0°.
     */
    private fun refineAos(
        tle: TleElements, epochUnix: Long,
        lat: Double, lon: Double, altKm: Double,
        tBefore: Long, tAfter: Long,
    ): Long {
        var lo = tBefore
        var hi = tAfter
        while (hi - lo > REFINE_SEC) {
            val mid = (lo + hi) / 2
            val tsince = (mid - epochUnix) / 60.0
            val pos = Sgp4.propagate(tle, tsince)
            val elev = if (pos != null) elevationDeg(pos, lat, lon, altKm, mid) else -90.0
            if (elev > 0.0) hi = mid else lo = mid
        }
        return hi
    }

    /**
     * Refine LOS by binary search.
     */
    private fun refineLos(
        tle: TleElements, epochUnix: Long,
        lat: Double, lon: Double, altKm: Double,
        tBefore: Long, tAfter: Long,
    ): Long {
        var lo = tBefore
        var hi = tAfter
        while (hi - lo > REFINE_SEC) {
            val mid = (lo + hi) / 2
            val tsince = (mid - epochUnix) / 60.0
            val pos = Sgp4.propagate(tle, tsince)
            val elev = if (pos != null) elevationDeg(pos, lat, lon, altKm, mid) else -90.0
            if (elev > 0.0) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * Compute elevation angle (degrees) of satellite from observer.
     */
    private fun elevationDeg(
        satEci: Sgp4.EciPosition,
        latDeg: Double, lonDeg: Double, altKm: Double,
        unixTime: Long,
    ): Double {
        val obsEci = observerEci(latDeg, lonDeg, altKm, unixTime)

        // Range vector in ECI
        val rx = satEci.x - obsEci.x
        val ry = satEci.y - obsEci.y
        val rz = satEci.z - obsEci.z
        val range = sqrt(rx * rx + ry * ry + rz * rz)
        if (range < 1.0) return -90.0

        // Observer unit up vector (normalized ECI position)
        val obsDist = sqrt(obsEci.x * obsEci.x + obsEci.y * obsEci.y + obsEci.z * obsEci.z)
        val ux = obsEci.x / obsDist
        val uy = obsEci.y / obsDist
        val uz = obsEci.z / obsDist

        // Dot product gives cos(zenith angle)
        val cosZenith = (rx * ux + ry * uy + rz * uz) / range
        return (asin(cosZenith) * RAD2DEG)
    }

    /**
     * Compute azimuth angle (degrees, 0=N, 90=E) of satellite from observer.
     */
    private fun azimuthDeg(
        satEci: Sgp4.EciPosition,
        latDeg: Double, lonDeg: Double,
        unixTime: Long,
    ): Double {
        val lat = latDeg * DEG2RAD
        val gmst = greenwichMeanSiderealTime(unixTime)
        val lst = gmst + lonDeg * DEG2RAD

        // Range in ECI
        val obsEci = observerEci(latDeg, lonDeg, 0.0, unixTime)
        val rx = satEci.x - obsEci.x
        val ry = satEci.y - obsEci.y
        val rz = satEci.z - obsEci.z

        // Rotate to topocentric (South, East, Up)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLst = sin(lst)
        val cosLst = cos(lst)

        val south = sinLat * cosLst * rx + sinLat * sinLst * ry - cosLat * rz
        val east = -sinLst * rx + cosLst * ry

        // Azimuth: measured from North, clockwise
        val az = atan2(east, -south) * RAD2DEG
        return if (az < 0) az + 360.0 else az
    }

    /**
     * Observer position in ECI coordinates (km).
     */
    private fun observerEci(
        latDeg: Double, lonDeg: Double, altKm: Double,
        unixTime: Long,
    ): Sgp4.EciPosition {
        val lat = latDeg * DEG2RAD
        val gmst = greenwichMeanSiderealTime(unixTime)
        val lst = gmst + lonDeg * DEG2RAD

        // WGS-84 Earth radius at latitude
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val e2 = EARTH_FLATTENING * (2.0 - EARTH_FLATTENING)
        val n = EARTH_RADIUS_KM / sqrt(1.0 - e2 * sinLat * sinLat)
        val r = (n + altKm) * cosLat
        val z = (n * (1.0 - e2) + altKm) * sinLat

        return Sgp4.EciPosition(
            x = r * cos(lst),
            y = r * sin(lst),
            z = z,
        )
    }

    /**
     * Greenwich Mean Sidereal Time (radians) from Unix timestamp.
     */
    private fun greenwichMeanSiderealTime(unixTime: Long): Double {
        val jd = TleParser.unixToJd(unixTime)
        val t = (jd - 2451545.0) / 36525.0  // Julian centuries from J2000.0

        // IAU 1982 GMST formula
        var gmst = 67310.54841 +
                (876600.0 * 3600 + 8640184.812866) * t +
                0.093104 * t * t -
                6.2e-6 * t * t * t
        gmst = (gmst % 86400.0) / 86400.0 * 2.0 * PI
        if (gmst < 0) gmst += 2.0 * PI
        return gmst
    }
}
