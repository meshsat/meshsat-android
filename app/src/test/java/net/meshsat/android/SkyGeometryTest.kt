package net.meshsat.android

import net.meshsat.android.satellite.PassPrediction
import net.meshsat.android.ui.components.SkyGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart draws what the Bridge draws (MESHSAT-1300). The first test is why it was rewritten:
 * the old chart made each pass a quadratic Bezier with its control point at the peak elevation,
 * and a quadratic Bezier only rises half way to its control point, so an 80 degree pass stood at
 * 40 degrees.
 */
class SkyGeometryTest {

    private fun pass(aos: Long, los: Long, peak: Double) =
        PassPrediction("IRIDIUM 122", aos, los, (los - aos) / 60.0, peak, 88.0, false)

    @Test
    fun `an 80 degree pass peaks at 80 degrees, not at half of it`() {
        val t = SkyGeometry.triangle(pass(1_000, 1_600, 80.0), 0, 3_600, left = 0f, width = 3600f, bottom = 900f, height = 900f)
        assertEquals(900f - 800f, t.peakY, 0.001f)
        assertEquals(1_300f, t.xMid, 0.001f)
    }

    @Test
    fun `a pass that runs off the chart is clipped, then its apex sits between the clipped ends`() {
        val t = SkyGeometry.triangle(pass(-300, 300, 45.0), 0, 3_600, left = 10f, width = 3600f, bottom = 90f, height = 90f)
        assertEquals(10f, t.x1, 0.001f)
        assertEquals(310f, t.x2, 0.001f)
        assertEquals(160f, t.xMid, 0.001f)
    }

    @Test
    fun `signal colours follow the Bridge`() {
        assertEquals(0xFF10B981, SkyGeometry.signalArgb(5))
        assertEquals(0xFF10B981, SkyGeometry.signalArgb(3))
        assertEquals(0xFFF59E0B, SkyGeometry.signalArgb(2))
        assertEquals(0xFFF59E0B, SkyGeometry.signalArgb(1))
        assertEquals(0xFFEF4444, SkyGeometry.signalArgb(0))
    }

    @Test
    fun `bars and degrees share the plot`() {
        assertEquals(100f, SkyGeometry.barsY(0, 100f, 50f), 0.001f)
        assertEquals(50f, SkyGeometry.barsY(5, 100f, 50f), 0.001f)
        assertEquals(50f, SkyGeometry.elevY(90.0, 100f, 50f), 0.001f)
    }

    @Test
    fun `labels fall on whole hours inside the window`() {
        assertEquals(listOf(3_600L, 7_200L), SkyGeometry.ticks(1_800, 9_000, 3_600))
    }

    @Test
    fun `only passes that touch the window are drawn`() {
        assertTrue(SkyGeometry.overlaps(pass(900, 1_500, 30.0), 1_000, 2_000))
        assertFalse(SkyGeometry.overlaps(pass(100, 900, 30.0), 1_000, 2_000))
        assertFalse(SkyGeometry.overlaps(pass(2_000, 2_600, 30.0), 1_000, 2_000))
    }
}
