package com.cubeos.meshsat

import com.cubeos.meshsat.satellite.PassPredictor
import com.cubeos.meshsat.satellite.Sgp4
import com.cubeos.meshsat.satellite.TleElements
import com.cubeos.meshsat.satellite.TleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteTest {

    // Real IRIDIUM 106 TLE (epoch 2024)
    private val testName = "IRIDIUM 106"
    private val testLine1 = "1 43479U 18043B   24001.50000000  .00000068  00000+0  19540-4 0  9990"
    private val testLine2 = "2 43479  86.3935 126.4957 0002172  86.8487 273.2945 14.34216202298320"

    @Test
    fun `TLE parser extracts orbital elements`() {
        val tle = TleParser.parse(testName, testLine1, testLine2)
        assertNotNull(tle)
        tle!!

        assertEquals("IRIDIUM 106", tle.name)
        assertEquals(43479, tle.catalogNumber)
        assertEquals(24, tle.epochYear)
        assertTrue(tle.epochDay > 1.0 && tle.epochDay < 366.0)
        assertTrue(tle.inclination in 80.0..90.0)  // Iridium is ~86°
        assertTrue(tle.eccentricity in 0.0..0.01)   // near-circular
        assertTrue(tle.meanMotion in 14.0..15.0)     // LEO ~14.3 rev/day
    }

    @Test
    fun `TLE parser rejects invalid lines`() {
        assertNull(TleParser.parse("test", "short", "short"))
        assertNull(TleParser.parse("test", "2" + "x".repeat(68), "1" + "x".repeat(68)))
    }

    @Test
    fun `TLE parseMulti handles 3-line format`() {
        val text = """
            IRIDIUM 106
            $testLine1
            $testLine2
            IRIDIUM 107
            $testLine1
            $testLine2
        """.trimIndent()

        val tles = TleParser.parseMulti(text)
        assertEquals(2, tles.size)
        assertEquals("IRIDIUM 106", tles[0].name)
        assertEquals("IRIDIUM 107", tles[1].name)
    }

    @Test
    fun `Julian date conversion round-trips`() {
        val unix = 1704067200L  // 2024-01-01 00:00:00 UTC
        val jd = TleParser.unixToJd(unix)
        val back = TleParser.jdToUnix(jd)
        assertTrue(kotlin.math.abs(back - unix) < 2)  // within 1 second
    }

    @Test
    fun `SGP4 propagates to valid position`() {
        val tle = TleParser.parse(testName, testLine1, testLine2)!!
        val pos = Sgp4.propagate(tle, 0.0)  // at epoch

        assertNotNull(pos)
        pos!!

        // LEO satellite should be within ~7000-8000 km from Earth center
        val dist = kotlin.math.sqrt(pos.x * pos.x + pos.y * pos.y + pos.z * pos.z)
        assertTrue("Satellite distance $dist km should be in LEO range", dist in 6500.0..8000.0)
    }

    @Test
    fun `SGP4 propagates forward in time`() {
        val tle = TleParser.parse(testName, testLine1, testLine2)!!

        // Propagate 90 minutes (about 1 orbit)
        val pos0 = Sgp4.propagate(tle, 0.0)!!
        val pos90 = Sgp4.propagate(tle, 90.0)!!

        // Position should change but remain in LEO
        val dist0 = kotlin.math.sqrt(pos0.x * pos0.x + pos0.y * pos0.y + pos0.z * pos0.z)
        val dist90 = kotlin.math.sqrt(pos90.x * pos90.x + pos90.y * pos90.y + pos90.z * pos90.z)

        assertTrue(dist0 in 6500.0..8000.0)
        assertTrue(dist90 in 6500.0..8000.0)

        // Positions should differ (satellite moved)
        val dx = pos90.x - pos0.x
        val dy = pos90.y - pos0.y
        val dz = pos90.z - pos0.z
        val separation = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        assertTrue("Satellite should have moved >100km in 90min, got $separation", separation > 100.0)
    }

    @Test
    fun `pass predictor finds passes for Iridium`() {
        val tle = TleParser.parse(testName, testLine1, testLine2)!!

        // London, 24-hour window from TLE epoch
        val epochUnix = TleParser.jdToUnix(tle.epochJd)
        val passes = PassPredictor.predictPasses(
            tle = tle,
            lat = 51.5,
            lon = -0.1,
            altKm = 0.0,
            startUnix = epochUnix,
            endUnix = epochUnix + 86400,
            minElevDeg = 5.0,
        )

        // Iridium orbits Earth ~14.3x/day, so we expect several passes
        assertTrue("Expected at least 1 pass in 24h, got ${passes.size}", passes.isNotEmpty())

        for (pass in passes) {
            assertEquals(testName, pass.satellite)
            assertTrue("AOS should be in window", pass.aosUnix >= epochUnix)
            assertTrue("LOS should be after AOS", pass.losUnix > pass.aosUnix)
            assertTrue("Duration should be positive", pass.durationMin > 0)
            assertTrue("Peak elevation should be >= min", pass.peakElevDeg >= 5.0)
            assertTrue("Azimuth should be 0-360", pass.peakAzimuthDeg in 0.0..360.0)
        }
    }

    @Test
    fun `higher min elevation filters low passes`() {
        val tle = TleParser.parse(testName, testLine1, testLine2)!!
        val epochUnix = TleParser.jdToUnix(tle.epochJd)

        val passesLow = PassPredictor.predictPasses(
            tle, 51.5, -0.1, 0.0, epochUnix, epochUnix + 86400, 5.0
        )
        val passesHigh = PassPredictor.predictPasses(
            tle, 51.5, -0.1, 0.0, epochUnix, epochUnix + 86400, 40.0
        )

        assertTrue("High min elev should return fewer passes", passesHigh.size <= passesLow.size)
    }

    @Test
    fun `predictAllPasses sorts by AOS`() {
        val tle1 = TleParser.parse("SAT-A", testLine1, testLine2)!!
        val tle2 = TleParser.parse("SAT-B", testLine1, testLine2)!!
        val epochUnix = TleParser.jdToUnix(tle1.epochJd)

        val passes = PassPredictor.predictAllPasses(
            listOf(tle1, tle2), 51.5, -0.1, 0.0, epochUnix, epochUnix + 86400, 5.0
        )

        for (i in 1 until passes.size) {
            assertTrue("Passes should be sorted by AOS", passes[i].aosUnix >= passes[i - 1].aosUnix)
        }
    }
}
