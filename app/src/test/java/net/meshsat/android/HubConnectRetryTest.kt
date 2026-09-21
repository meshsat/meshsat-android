package net.meshsat.android

import net.meshsat.android.hub.CONNECT_RETRY_MAX_MS
import net.meshsat.android.hub.CONNECT_RETRY_MIN_MS
import net.meshsat.android.hub.nextConnectRetry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed first Hub connect is tried again (MESHSAT-749): on 21 Sep 2026 the connect right after
 * provisioning failed once and the phone stayed off the Hub until a person restarted it.
 */
class HubConnectRetryTest {

    @Test
    fun `the wait doubles from five seconds and stops at a minute`() {
        val waits = generateSequence(CONNECT_RETRY_MIN_MS) { nextConnectRetry(it) }.take(7).toList()
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun `nothing below the floor`() {
        assertEquals(CONNECT_RETRY_MIN_MS, nextConnectRetry(0))
        assertEquals(CONNECT_RETRY_MAX_MS, nextConnectRetry(10 * 60_000L))
    }
}
