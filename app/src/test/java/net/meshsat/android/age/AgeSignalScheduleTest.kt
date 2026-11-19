package net.meshsat.android.age

import net.meshsat.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Google Play edition asks Play for an age signal (MESHSAT-1908). Runs under both
 * testFdroidDebugUnitTest and testPlayDebugUnitTest.
 */
class AgeSignalScheduleTest {
    private val day = AgeSignalSchedule.REFRESH_MS
    private val hour = AgeSignalSchedule.RETRY_MS
    private val now = 1_800_000_000_000L

    @Test
    fun `only the play edition asks`() {
        assertEquals(BuildConfig.FLAVOR == "play", AgeSignals.INCLUDED)
    }

    @Test
    fun `a fresh install asks at its first start`() {
        assertTrue(AgeSignalSchedule.due(doneMs = 0, triedMs = 0, nowMs = now))
    }

    @Test
    fun `an answered ask is made again after a day, not before`() {
        assertFalse(AgeSignalSchedule.due(doneMs = now - day + 1, triedMs = 0, nowMs = now))
        assertTrue(AgeSignalSchedule.due(doneMs = now - day, triedMs = 0, nowMs = now))
    }

    @Test
    fun `a failed ask waits an hour before the next try`() {
        assertFalse(AgeSignalSchedule.due(doneMs = 0, triedMs = now - hour + 1, nowMs = now))
        assertTrue(AgeSignalSchedule.due(doneMs = 0, triedMs = now - hour, nowMs = now))
    }

    @Test
    fun `a clock set back does not silence the ask`() {
        assertTrue(AgeSignalSchedule.due(doneMs = now + day, triedMs = 0, nowMs = now))
        assertTrue(AgeSignalSchedule.due(doneMs = 0, triedMs = now + hour, nowMs = now))
    }

    @Test
    fun `only an answer counts as done`() {
        assertTrue(AgeSignalOutcome.NotRequired.done)
        assertTrue(AgeSignalOutcome.Shared.done)
        assertTrue(AgeSignalOutcome.NotShared.done)
        assertTrue(AgeSignalOutcome.VerificationRequired.done)
        assertFalse(AgeSignalOutcome.Failed(-3).done)
        assertFalse(AgeSignalOutcome.Failed(null).done)
    }
}
