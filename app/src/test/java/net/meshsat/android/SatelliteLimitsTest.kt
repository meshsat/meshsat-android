package net.meshsat.android

import net.meshsat.android.codec.ProtocolVersion
import net.meshsat.android.engine.IridiumFragment
import net.meshsat.android.engine.SatelliteLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One satellite message is one frame (MESHSAT-1280).
 *
 * The first test is the reason: the header the phone used to put on the first half of a two-part
 * message IS the protocol version byte. The Hub cannot tell them apart and decided the version
 * byte wins, so the phone must never send the other one.
 */
class SatelliteLimitsTest {

    @Test
    fun `the first part of a two-part message starts with the version byte`() {
        val parts = IridiumFragment.fragment(ByteArray(400) { 0x41 }, IridiumFragment.MO_MTU, msgID = 7)!!
        assertEquals(2, parts.size)
        val versioned = ProtocolVersion.prependVersionByte(byteArrayOf(0x41))
        assertEquals("the collision this rule exists for", versioned[0], parts[0][0])
    }

    @Test
    fun `340 bytes fits and 341 does not`() {
        assertTrue(SatelliteLimits.fits(0))
        assertTrue(SatelliteLimits.fits(340))
        assertFalse(SatelliteLimits.fits(341))
    }

    @Test
    fun `the limit is the frame the modem takes and the Bridge enforces`() {
        assertEquals(IridiumFragment.MO_MTU, SatelliteLimits.MAX_MO_BYTES)
    }

    @Test
    fun `a person is told the size, the limit and what to do`() {
        assertEquals(
            "Too long for a satellite message: 412 bytes, 340 at most. Shorten it or send it in two.",
            SatelliteLimits.tooLong(412),
        )
    }
}
