package com.cubeos.meshsat.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for APRS-IS passcode calculation (MESHSAT-230).
 */
class AprsIsPasscodeTest {

    @Test
    fun `known callsign produces correct passcode`() {
        // Well-known test vector: N0CALL → 13023
        assertEquals("13023", AprsIsPasscode.calculate("N0CALL"))
    }

    @Test
    fun `strips SSID before calculation`() {
        val withSsid = AprsIsPasscode.calculate("PA3XYZ-10")
        val withoutSsid = AprsIsPasscode.calculate("PA3XYZ")
        assertEquals(withSsid, withoutSsid)
    }

    @Test
    fun `case insensitive`() {
        assertEquals(
            AprsIsPasscode.calculate("pa3xyz"),
            AprsIsPasscode.calculate("PA3XYZ"),
        )
    }

    @Test
    fun `empty callsign returns negative one`() {
        assertEquals("-1", AprsIsPasscode.calculate(""))
    }

    @Test
    fun `passcode is in valid range`() {
        val code = AprsIsPasscode.calculate("W3ADO").toInt()
        assert(code in 0..32767) { "Passcode $code out of range" }
    }

    @Test
    fun `different callsigns produce different passcodes`() {
        assertNotEquals(
            AprsIsPasscode.calculate("PA3XYZ"),
            AprsIsPasscode.calculate("PA3ABC"),
        )
    }

    @Test
    fun `odd length callsign works`() {
        val code = AprsIsPasscode.calculate("N0CAL")
        assertNotEquals("-1", code)
        val num = code.toInt()
        assert(num in 0..32767)
    }
}
