package net.meshsat.android

import net.meshsat.android.engine.Dispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A queued Iridium message is retried, not dropped (MESHSAT-1243), on the Bridge's schedule
 * (dlqBackoff): 3 minutes after "no network service" or "wait 3 minutes", however many tries
 * failed, since the ISU allows one registration every 3 minutes and a satellite crosses the sky
 * in under 10. It used to stretch to 30 minutes, or to the next predicted pass (MESHSAT-1249).
 */
class SatelliteRetryTest {

    private val now = 1_790_000_000_000L
    private val initial = 180.seconds
    private val max = 30.minutes

    @Test
    fun `no network service is retried every 3 minutes, however many tries failed`() {
        for (retries in listOf(1, 5, 6, 8, 40)) {
            assertEquals(now + 3 * 60_000, Dispatcher.satelliteRetryAt(now, retries, 32, initial, max))
            assertEquals(now + 3 * 60_000, Dispatcher.satelliteRetryAt(now, retries, 36, initial, max))
        }
    }

    @Test
    fun `a busy modem and a gateway that did not answer are retried soon`() {
        assertEquals(now + 30_000, Dispatcher.satelliteRetryAt(now, 7, 35, initial, max))
        assertEquals(now + 60_000, Dispatcher.satelliteRetryAt(now, 7, 17, initial, max))
    }

    @Test
    fun `other failures back off by doubling up to the channel's maximum`() {
        assertEquals(now + 6 * 60_000, Dispatcher.satelliteRetryAt(now, 1, 18, initial, max))
        assertEquals(now + 12 * 60_000, Dispatcher.satelliteRetryAt(now, 2, null, initial, max))
        assertEquals(now + 30 * 60_000, Dispatcher.satelliteRetryAt(now, 4, null, initial, max))
        assertEquals(now + 30 * 60_000, Dispatcher.satelliteRetryAt(now, 40, 13, initial, max))
    }

    @Test
    fun `a 9704 send that saw no satellite comes back within a minute, not on the 9603 schedule`() {
        assertEquals(now + 15_000, Dispatcher.imtRetryAt(now, 1))
        assertEquals(now + 30_000, Dispatcher.imtRetryAt(now, 2))
        for (retries in listOf(3, 4, 10, 40)) assertEquals(now + 60_000, Dispatcher.imtRetryAt(now, retries))
    }

    @Test
    fun `only the 9704's own send failures count as a 9704 miss`() {
        assertTrue(Dispatcher.isImtMiss("9704 MO failed: cancelled"))
        assertTrue(Dispatcher.isImtMiss("9704 MO failed: timeout"))
        assertTrue(Dispatcher.isImtMiss("9704 not ready"))
        assertFalse(Dispatcher.isImtMiss("${Dispatcher.NEVER} Not sent: no key"))
        assertFalse(Dispatcher.isImtMiss("${Dispatcher.UNCONFIRMED} the 9704 did not answer the cancel of a message it held"))
        assertFalse(Dispatcher.isImtMiss("Not sent: status 32, no network service, MOMSN 228"))
    }

    @Test
    fun `the modem's status is read from the delivery's error`() {
        assertEquals(32, Dispatcher.moStatusOf("Not sent: status 32, no network service, MOMSN 228"))
        assertEquals(18, Dispatcher.moStatusOf("${Dispatcher.UNCONFIRMED} status 18, the radio link dropped, MOMSN 12 (part 1 of 2)"))
        assertNull(Dispatcher.moStatusOf("Could not hand the message to the modem"))
        assertNull(Dispatcher.moStatusOf("The modem gave no readable answer"))
    }
}
