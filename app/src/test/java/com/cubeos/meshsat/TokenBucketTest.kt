package com.cubeos.meshsat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for TokenBucket (Phase A: rate limiter).
 * Tests token bucket semantics: refill, consume, capacity.
 */
class TokenBucketTest {

    @Test
    fun `fresh bucket allows requests up to capacity`() {
        val bucket = TestTokenBucket(maxTokens = 3.0, refillRate = 1.0)
        assertTrue(bucket.allow())
        assertTrue(bucket.allow())
        assertTrue(bucket.allow())
        assertFalse(bucket.allow()) // exhausted
    }

    @Test
    fun `tokens refill over time`() {
        val bucket = TestTokenBucket(maxTokens = 2.0, refillRate = 10.0)
        assertTrue(bucket.allow())
        assertTrue(bucket.allow())
        assertFalse(bucket.allow()) // empty

        // Simulate 200ms passing (should refill 2 tokens at 10/s)
        bucket.advanceTime(200_000_000L)
        assertTrue(bucket.allow())
    }

    @Test
    fun `tokens never exceed max`() {
        val bucket = TestTokenBucket(maxTokens = 3.0, refillRate = 100.0)
        // Advance a long time
        bucket.advanceTime(10_000_000_000L) // 10 seconds
        assertEquals(3.0, bucket.tokens(), 0.01)
    }

    @Test
    fun `zero refill rate never refills`() {
        val bucket = TestTokenBucket(maxTokens = 1.0, refillRate = 0.0)
        assertTrue(bucket.allow())
        assertFalse(bucket.allow())
        bucket.advanceTime(10_000_000_000L)
        assertFalse(bucket.allow()) // still empty
    }
}

/**
 * Test-friendly token bucket (no android.util.Log, controllable time).
 */
class TestTokenBucket(
    private val maxTokens: Double,
    private val refillRate: Double, // tokens per second
) {
    private var tokens = maxTokens
    private var lastRefillNano = System.nanoTime()
    private var timeOffsetNano = 0L

    fun allow(): Boolean {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            return true
        }
        return false
    }

    fun tokens(): Double {
        refill()
        return tokens
    }

    fun advanceTime(nanos: Long) {
        timeOffsetNano += nanos
    }

    private fun refill() {
        val now = System.nanoTime() + timeOffsetNano
        val elapsed = (now - lastRefillNano) / 1_000_000_000.0
        tokens = (tokens + elapsed * refillRate).coerceAtMost(maxTokens)
        lastRefillNano = now
    }
}
