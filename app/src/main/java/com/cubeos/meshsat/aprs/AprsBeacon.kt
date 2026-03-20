package com.cubeos.meshsat.aprs

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * APRS position beaconing with smart beaconing support (MESHSAT-231).
 *
 * Periodically transmits GPS position as APRS position reports via KISS or APRS-IS.
 * Smart beaconing adjusts the interval based on speed and heading changes:
 * - Stationary: beacon at slow rate (configurable, default 10 min)
 * - Moving: beacon at fast rate (configurable, default 90s)
 * - Heading change >30°: immediate beacon (corner pegging)
 *
 * Reference: http://www.hamhud.net/hh2/smartbeacon.html
 */
class AprsBeacon(
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AprsBeacon"

        // Smart beaconing defaults
        const val DEFAULT_SLOW_RATE_SEC = 600   // 10 min when stationary
        const val DEFAULT_FAST_RATE_SEC = 90    // 90s when moving
        const val MIN_BEACON_INTERVAL_SEC = 60  // APRS courtesy: never faster than 60s
        const val SPEED_THRESHOLD_MPS = 2.0     // 2 m/s (~7 km/h) = "moving"
        const val HEADING_CHANGE_DEG = 30.0     // Corner peg threshold
    }

    /** Callback to transmit a position beacon. */
    var onBeacon: ((lat: Double, lon: Double, alt: Double, course: Double, speed: Double, comment: String) -> Unit)? = null

    private var job: Job? = null
    private var lastBeaconTime = 0L
    private var lastHeading = 0.0
    private var lastLocation: Location? = null

    var slowRateSec: Int = DEFAULT_SLOW_RATE_SEC
    var fastRateSec: Int = DEFAULT_FAST_RATE_SEC
    var enabled: Boolean = false
        private set

    /** Start the beacon timer loop. */
    fun start() {
        stop()
        enabled = true
        lastBeaconTime = 0
        job = scope.launch {
            Log.i(TAG, "Beacon started (slow=${slowRateSec}s, fast=${fastRateSec}s)")
            while (isActive) {
                delay(10_000) // Check every 10s
                checkAndBeacon()
            }
        }
    }

    /** Stop beaconing. */
    fun stop() {
        enabled = false
        job?.cancel()
        job = null
    }

    /**
     * Called on each GPS location update. Triggers immediate beacon on corner peg.
     */
    fun onLocationUpdate(location: Location) {
        lastLocation = location

        if (!enabled) return

        // Corner pegging: immediate beacon on heading change > threshold
        val heading = location.bearing.toDouble()
        if (location.speed > SPEED_THRESHOLD_MPS && lastBeaconTime > 0) {
            val headingDelta = abs(heading - lastHeading)
            val normalizedDelta = if (headingDelta > 180) 360 - headingDelta else headingDelta
            if (normalizedDelta >= HEADING_CHANGE_DEG) {
                val elapsed = (System.currentTimeMillis() - lastBeaconTime) / 1000
                if (elapsed >= MIN_BEACON_INTERVAL_SEC) {
                    Log.d(TAG, "Corner peg: heading changed ${normalizedDelta.toInt()}°")
                    beacon(location)
                }
            }
        }
        lastHeading = heading
    }

    private fun checkAndBeacon() {
        val loc = lastLocation ?: return

        val now = System.currentTimeMillis()
        val elapsed = (now - lastBeaconTime) / 1000

        // Smart beaconing: use fast rate when moving, slow rate when stationary
        val interval = if (loc.speed > SPEED_THRESHOLD_MPS) fastRateSec else slowRateSec
        val effectiveInterval = interval.coerceAtLeast(MIN_BEACON_INTERVAL_SEC)

        if (elapsed >= effectiveInterval) {
            beacon(loc)
        }
    }

    private fun beacon(location: Location) {
        lastBeaconTime = System.currentTimeMillis()

        val comment = buildComment(location)

        onBeacon?.invoke(
            location.latitude,
            location.longitude,
            location.altitude,
            location.bearing.toDouble(),
            location.speed.toDouble(),
            comment,
        )

        Log.d(TAG, "Beacon TX: %.4f,%.4f spd=%.1fm/s hdg=%.0f°".format(
            location.latitude, location.longitude, location.speed, location.bearing,
        ))
    }

    private fun buildComment(location: Location): String {
        val parts = mutableListOf<String>()

        // Speed in km/h
        val speedKmh = location.speed * 3.6
        if (speedKmh > 1.0) {
            parts.add("%.0fkm/h".format(speedKmh))
        }

        // Altitude
        if (location.altitude > 0) {
            parts.add("alt=%dm".format(location.altitude.toInt()))
        }

        parts.add("MeshSat")
        return parts.joinToString(" ")
    }
}
