package net.meshsat.android

import net.meshsat.android.satellite.PassPredictor
import net.meshsat.android.satellite.Sgp4
import net.meshsat.android.satellite.TleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.sqrt

/**
 * The app's SGP4 against the reference implementation (Vallado's, as the `sgp4` Python package
 * ships it), for a real Iridium NEXT element set. Found 21 Sep 2026: the app predicted two passes
 * for one satellite minutes apart and about three times the passes a day the Bridge and the
 * reference give, so its pass list, Home's "high overhead now" and the pass chart were wrong.
 */
class Sgp4ReferenceTest {

    private val l1 = "1 41922U 17003F   26263.65299469  .00000272  00000+0  90159-4 0  9996"
    private val l2 = "2 41922  86.3951  48.5368 0002411  92.5475 267.5997 14.34220199506951"

    // TEME position in km from the reference, at minutes after the epoch
    private val reference = mapOf(
        0.0 to doubleArrayOf(4740.199, 5364.760, 0.003),
        10.0 to doubleArrayOf(3640.600, 4518.023, 4182.265),
        60.0 to doubleArrayOf(-3678.654, -4553.689, -4128.078),
        360.0 to doubleArrayOf(-3934.896, -4779.299, -3602.570),
        1440.0 to doubleArrayOf(-2686.306, -2413.308, 6169.477),
    )

    @Test
    fun `the epoch is read as the reference reads it`() {
        val tle = TleParser.parse("IRIDIUM 104", l1, l2)
        assertNotNull(tle)
        assertEquals(2461304.15299469, tle!!.epochJd, 1e-6)
    }

    @Test
    fun `positions agree with the reference to within a kilometre`() {
        val tle = TleParser.parse("IRIDIUM 104", l1, l2)!!
        val errors = reference.map { (ts, ref) ->
            val p = Sgp4.propagate(tle, ts)!!
            val d = sqrt((p.x - ref[0]) * (p.x - ref[0]) + (p.y - ref[1]) * (p.y - ref[1]) + (p.z - ref[2]) * (p.z - ref[2]))
            println("tsince $ts min: app (${"%.1f".format(p.x)}, ${"%.1f".format(p.y)}, ${"%.1f".format(p.z)}) error ${"%.1f".format(d)} km")
            ts to d
        }
        errors.forEach { (ts, d) -> assert(d < 1.0) { "at $ts min the app is $d km from the reference" } }
    }

    @Test
    fun `one satellite gives one pass, where the reference puts it`() {
        // Leiden, 21 Sep 2026 04:30-06:00 UTC. The reference (1-minute steps) has one pass,
        // 05:15-05:26 at 11 degrees; the app had two, 05:02-05:09 and 05:19-05:31.
        val tle = TleParser.parse("IRIDIUM 104", l1, l2)!!
        val start = 1789965000L // 2026-09-21T04:30:00Z
        val passes = PassPredictor.predictPasses(tle, 52.1620, 4.5093, 0.0, start, start + 90 * 60, minElevDeg = 0.0)
        assertEquals(1, passes.size)
        val p = passes.single()
        val aos = 1789967700L // 05:15:00Z
        val los = 1789968360L // 05:26:00Z
        assert(kotlin.math.abs(p.aosUnix - aos) <= 60) { "AOS ${p.aosUnix - aos} s from the reference" }
        assert(kotlin.math.abs(p.losUnix - los) <= 60) { "LOS ${p.losUnix - los} s from the reference" }
        assertEquals(11.0, p.peakElevDeg, 1.0)
    }
}
