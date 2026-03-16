package com.cubeos.meshsat

import com.cubeos.meshsat.engine.GeofenceMonitor
import com.cubeos.meshsat.engine.GeofenceZone
import com.cubeos.meshsat.engine.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for GeofenceMonitor (Phase D: geofence alerts).
 * Tests point-in-polygon, enter/exit detection, event log.
 */
class GeofenceMonitorTest {

    private fun squareZone(centerLat: Double = 47.0, centerLon: Double = -122.0, size: Double = 0.01): GeofenceZone {
        return GeofenceZone(
            id = "test_zone",
            name = "Test Zone",
            polygon = listOf(
                LatLon(centerLat - size, centerLon - size),
                LatLon(centerLat - size, centerLon + size),
                LatLon(centerLat + size, centerLon + size),
                LatLon(centerLat + size, centerLon - size),
            ),
            alertOn = "both",
        )
    }

    @Test
    fun `point inside polygon detected`() {
        val polygon = squareZone().polygon
        assertTrue(GeofenceMonitor.pointInPolygon(47.0, -122.0, polygon))
    }

    @Test
    fun `point outside polygon not detected`() {
        val polygon = squareZone().polygon
        assertTrue(!GeofenceMonitor.pointInPolygon(48.0, -122.0, polygon))
    }

    @Test
    fun `enter event triggered when node enters zone`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone())

        // Node starts outside
        var events = monitor.checkPosition("node1", 48.0, -122.0)
        assertEquals(0, events.size)

        // Node enters zone
        events = monitor.checkPosition("node1", 47.0, -122.0)
        assertEquals(1, events.size)
        assertEquals("enter", events[0].event)
    }

    @Test
    fun `exit event triggered when node leaves zone`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone())

        // Enter
        monitor.checkPosition("node1", 47.0, -122.0)
        // Exit
        val events = monitor.checkPosition("node1", 48.0, -122.0)
        assertEquals(1, events.size)
        assertEquals("exit", events[0].event)
    }

    @Test
    fun `no event when node stays inside`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone())

        monitor.checkPosition("node1", 47.0, -122.0) // enter
        val events = monitor.checkPosition("node1", 47.001, -122.001) // still inside
        assertEquals(0, events.size)
    }

    @Test
    fun `enter-only zone does not trigger exit`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone().copy(alertOn = "enter"))

        monitor.checkPosition("node1", 47.0, -122.0) // enter
        val events = monitor.checkPosition("node1", 48.0, -122.0) // exit
        assertEquals(0, events.size) // no exit event
    }

    @Test
    fun `event log records events`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone())

        assertEquals(0, monitor.getEvents().size)

        monitor.checkPosition("node1", 47.0, -122.0) // enter
        assertEquals(1, monitor.getEvents().size)
        assertEquals("enter", monitor.getEvents()[0].event)

        monitor.checkPosition("node1", 48.0, -122.0) // exit
        assertEquals(2, monitor.getEvents().size)
    }

    @Test
    fun `multiple zones tracked independently`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone(centerLat = 47.0).copy(id = "z1", name = "Zone 1"))
        monitor.addZone(squareZone(centerLat = 48.0).copy(id = "z2", name = "Zone 2"))

        // Enter zone 1 only
        val events = monitor.checkPosition("node1", 47.0, -122.0)
        assertEquals(1, events.size)
        assertEquals("Zone 1", events[0].zone.name)
    }

    @Test
    fun `remove zone stops tracking`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone())
        monitor.removeZone("test_zone")

        val events = monitor.checkPosition("node1", 47.0, -122.0)
        assertEquals(0, events.size)
    }

    @Test
    fun `getZones returns copy`() {
        val monitor = GeofenceMonitor()
        monitor.addZone(squareZone())
        val zones = monitor.getZones()
        assertEquals(1, zones.size)
        assertEquals("Test Zone", zones[0].name)
    }
}
