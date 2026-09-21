package net.meshsat.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The node's battery as the phone shows it (MESHSAT-1315). */
class NodeBatteryTest {

    private val t0 = 1_790_000_000_000L
    private fun at(min: Int) = t0 + min * 60_000L

    /** A reading a minute from [fromMin] to [toMin], falling [perHour] points an hour from [start]. */
    private fun falling(start: Double, perHour: Double, fromMin: Int, toMin: Int) =
        (fromMin..toMin).map { m -> NodeBattery.Reading(at(m), (start - perHour * (m - fromMin) / 60.0).toInt()) }

    @Test
    fun `time left follows the measured rate of drop`() {
        // 100 % falling 6 points an hour: after 60 min it reads 94 %, so about 15-16 h left.
        val readings = falling(100.0, 6.0, 0, 60)
        val hours = NodeBattery.hoursLeft(readings, at(60))
        assertNotNull(hours)
        assertEquals(94.0 / 6.0, hours!!, 1.0)
    }

    @Test
    fun `no estimate from too little time or too little drop`() {
        assertNull("20 min is too short", NodeBattery.hoursLeft(falling(100.0, 6.0, 0, 20), at(20)))
        assertNull("1 point of drop is noise", NodeBattery.hoursLeft(falling(100.0, 1.0, 0, 60), at(60)))
        assertNull("a flat level", NodeBattery.hoursLeft((0..90).map { NodeBattery.Reading(at(it), 88) }, at(90)))
        assertNull(NodeBattery.hoursLeft(emptyList(), at(0)))
    }

    @Test
    fun `only the time since the node came off USB power counts`() {
        val onUsb = (0..40).map { NodeBattery.Reading(at(it), NodeBattery.EXTERNAL_POWER) }
        val unpluggedShortly = falling(100.0, 6.0, 41, 60)
        assertNull("19 min on battery", NodeBattery.hoursLeft(onUsb + unpluggedShortly, at(60)))
        val unpluggedLonger = falling(100.0, 6.0, 41, 101)
        assertNotNull(NodeBattery.hoursLeft(onUsb + unpluggedLonger, at(101)))
    }

    @Test
    fun `readings older than the window are ignored`() {
        // A fast drop four hours ago, then a slow one: the estimate follows the recent rate.
        val old = falling(100.0, 30.0, 0, 30)
        val recent = falling(80.0, 4.0, 240, 330)
        val hours = NodeBattery.hoursLeft(old + recent, at(330))!!
        assertEquals(74.0 / 4.0, hours, 2.0)
    }

    @Test
    fun `words for the screen`() {
        assertEquals("On USB power", NodeBattery.describe(101, 4.9f, null))
        assertEquals("82%, 3.98 V, about 14 h left", NodeBattery.describe(82, 3.98f, 14.2))
        assertEquals("82%, about 14 h left", NodeBattery.describe(82, 3.98f, 14.2, withVoltage = false))
        assertEquals("82%", NodeBattery.describe(82, 0f, null))
        assertNull(NodeBattery.describe(-1, 0f, null))
        assertEquals("about 40 min left", NodeBattery.timeLeftText(0.66))
        assertEquals("about 3 days left", NodeBattery.timeLeftText(70.0))
        assertEquals("USB", NodeBattery.cell(101))
        assertEquals("55%", NodeBattery.cell(55))
        assertEquals("-", NodeBattery.cell(-1))
    }
}
