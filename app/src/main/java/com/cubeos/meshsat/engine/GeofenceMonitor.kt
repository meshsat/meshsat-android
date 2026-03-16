package com.cubeos.meshsat.engine

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Geographic coordinate.
 */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Polygonal geofence zone with alert triggers.
 * Port of meshsat/internal/engine.GeofenceZone.
 */
data class GeofenceZone(
    val id: String,
    val name: String,
    val polygon: List<LatLon>,
    val alertOn: String,   // "enter", "exit", "both"
    val message: String = "",
)

/**
 * Records a node entering or exiting a geofence zone.
 */
data class GeofenceEvent(
    val zone: GeofenceZone,
    val nodeId: String,
    val event: String,  // "enter" or "exit"
)

/** Persistent record of a geofence event with timestamp. */
data class GeofenceEventRecord(
    val zoneName: String,
    val nodeId: String,
    val event: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Geofence monitor — tracks node positions against configured polygonal zones
 * and detects enter/exit transitions using the ray casting algorithm.
 * Port of meshsat/internal/engine/geofence.go.
 */
class GeofenceMonitor {

    private val lock = ReentrantReadWriteLock()
    private val zones = mutableListOf<GeofenceZone>()
    private val inside = HashMap<String, HashMap<String, Boolean>>()  // zone_id -> node_id -> was_inside
    private val _events = mutableListOf<GeofenceEventRecord>()

    @Volatile
    var callback: ((zone: GeofenceZone, nodeId: String, event: String) -> Unit)? = null

    /** Get a copy of the recent event log (newest first, max 50). */
    fun getEvents(): List<GeofenceEventRecord> = lock.read {
        _events.takeLast(50).reversed()
    }

    /** Add a geofence zone. */
    fun addZone(zone: GeofenceZone) = lock.write {
        zones.add(zone)
        inside[zone.id] = HashMap()
        Log.i(TAG, "geofence zone added: ${zone.id} '${zone.name}' (${zone.polygon.size} vertices)")
    }

    /** Remove a geofence zone by ID. */
    fun removeZone(id: String) = lock.write {
        val idx = zones.indexOfFirst { it.id == id }
        if (idx >= 0) {
            zones.removeAt(idx)
            inside.remove(id)
            Log.i(TAG, "geofence zone removed: $id")
        }
    }

    /** Get a copy of all configured zones. */
    fun getZones(): List<GeofenceZone> = lock.read { zones.toList() }

    /**
     * Evaluate a node's position against all zones.
     * Returns any enter/exit transition events.
     */
    fun checkPosition(nodeId: String, lat: Double, lon: Double): List<GeofenceEvent> {
        lock.write {
            val events = mutableListOf<GeofenceEvent>()

            for (zone in zones) {
                val nowInside = pointInPolygon(lat, lon, zone.polygon)
                val wasInside = inside[zone.id]?.get(nodeId) ?: false

                if (nowInside && !wasInside) {
                    // Enter transition
                    if (zone.alertOn == "enter" || zone.alertOn == "both") {
                        events.add(GeofenceEvent(zone, nodeId, "enter"))
                        _events.add(GeofenceEventRecord(zone.name, nodeId, "enter"))
                        callback?.invoke(zone, nodeId, "enter")
                    }
                    inside.getOrPut(zone.id) { HashMap() }[nodeId] = true
                } else if (!nowInside && wasInside) {
                    // Exit transition
                    if (zone.alertOn == "exit" || zone.alertOn == "both") {
                        events.add(GeofenceEvent(zone, nodeId, "exit"))
                        _events.add(GeofenceEventRecord(zone.name, nodeId, "exit"))
                        callback?.invoke(zone, nodeId, "exit")
                    }
                    inside[zone.id]?.set(nodeId, false)
                }
            }

            return events
        }
    }

    companion object {
        private const val TAG = "GeofenceMonitor"

        /**
         * Ray casting algorithm — determines if a point is inside a polygon.
         */
        internal fun pointInPolygon(lat: Double, lon: Double, polygon: List<LatLon>): Boolean {
            val n = polygon.size
            if (n < 3) return false

            var inside = false
            var j = n - 1

            for (i in 0 until n) {
                val yi = polygon[i].lat
                val xi = polygon[i].lon
                val yj = polygon[j].lat
                val xj = polygon[j].lon

                if (((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                ) {
                    inside = !inside
                }
                j = i
            }

            return inside
        }
    }
}
