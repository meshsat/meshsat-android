package net.meshsat.android

import net.meshsat.android.engine.Dispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A queued Iridium message is retried, not dropped (MESHSAT-1243): 3 minutes after a failed
 * session at first, 30 minutes once five retries failed, and never before the next pass
 * window above the obstacle mask when one is predicted.
 */
class SatelliteRetryTest {

    private val now = 1_790_000_000_000L
    private val initial = 180.seconds
    private val max = 30.minutes

    @Test
    fun `the first retries come after the 3-minute ISU pause`() {
        assertEquals(now + 3 * 60_000, Dispatcher.satelliteRetryAt(now, 1, initial, max, null))
        assertEquals(now + 3 * 60_000, Dispatcher.satelliteRetryAt(now, 5, initial, max, null))
    }

    @Test
    fun `after five failed retries it waits the channel's maximum`() {
        assertEquals(now + 30 * 60_000, Dispatcher.satelliteRetryAt(now, 6, initial, max, null))
        assertEquals(now + 30 * 60_000, Dispatcher.satelliteRetryAt(now, 40, initial, max, null))
    }

    @Test
    fun `a later pass window moves the retry to the window, an earlier one does not`() {
        val window = now + 12 * 60_000
        assertEquals(window, Dispatcher.satelliteRetryAt(now, 1, initial, max, window))
        assertEquals(now + 3 * 60_000, Dispatcher.satelliteRetryAt(now, 1, initial, max, now + 60_000))
    }
}
