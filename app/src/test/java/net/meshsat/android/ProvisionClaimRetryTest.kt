package net.meshsat.android

import net.meshsat.android.crypto.ProvisionImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 503 from the Hub's claim means "not yet", not "failed" (MESHSAT-1298): its broker takes up to
 * about a minute to accept a new password, and the same code claims fine once it has.
 */
class ProvisionClaimRetryTest {

    @Test
    fun `the Hub's Retry-After is honoured, within sane bounds`() {
        assertEquals(5_000L, ProvisionImporter.claimRetryDelayMs(5))
        assertEquals(5_000L, ProvisionImporter.claimRetryDelayMs(null))
        assertEquals(1_000L, ProvisionImporter.claimRetryDelayMs(0))
        assertEquals(15_000L, ProvisionImporter.claimRetryDelayMs(600))
    }

    @Test
    fun `a scan waits long enough for the slowest broker measured`() {
        // Members accepted a new credential after 4.3, 28.2 and 52.8 s on 21 Sep 2026.
        assertTrue(ProvisionImporter.CLAIM_WAIT_MAX_MS >= 2 * 53_000L)
    }
}
