package com.cubeos.meshsat.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APRS beacon smart beaconing tests (MESHSAT-231).
 */
class AprsBeaconTest {

    @Test
    fun `default slow rate is 600 seconds`() {
        assertEquals(600, AprsBeacon.DEFAULT_SLOW_RATE_SEC)
    }

    @Test
    fun `default fast rate is 90 seconds`() {
        assertEquals(90, AprsBeacon.DEFAULT_FAST_RATE_SEC)
    }

    @Test
    fun `minimum beacon interval is 60 seconds`() {
        assertEquals(60, AprsBeacon.MIN_BEACON_INTERVAL_SEC)
    }

    @Test
    fun `speed threshold is 2 mps`() {
        assertEquals(2.0, AprsBeacon.SPEED_THRESHOLD_MPS, 0.01)
    }

    @Test
    fun `heading change threshold is 30 degrees`() {
        assertEquals(30.0, AprsBeacon.HEADING_CHANGE_DEG, 0.01)
    }

    @Test
    fun `beacon starts disabled`() {
        val beacon = AprsBeacon(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertFalse(beacon.enabled)
    }

    @Test
    fun `position encoding for beacon comment`() {
        // Verify that position encoding produces valid APRS format
        val encoded = String(AprsCodec.encodePosition(47.3, -122.5, comment = "MeshSat"))
        assertTrue(encoded.startsWith("!"))
        assertTrue(encoded.contains("N"))
        assertTrue(encoded.contains("W"))
        assertTrue(encoded.endsWith("MeshSat"))
    }

    @Test
    fun `position encoding with speed comment`() {
        val encoded = String(AprsCodec.encodePosition(
            52.3676, 4.9041, comment = "25km/h alt=15m MeshSat",
        ))
        assertTrue(encoded.contains("5222"))  // 52°22'
        assertTrue(encoded.contains("00454")) // 004°54'
        assertTrue(encoded.contains("25km/h"))
    }
}
