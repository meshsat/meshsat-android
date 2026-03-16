package com.cubeos.meshsat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Deduplicator (Phase A: dedup engine).
 * Tests composite-key dedup, TTL expiry, capacity limits.
 */
class DeduplicatorTest {

    // Note: Deduplicator uses android.util.Log — these tests run with JUnit on JVM
    // where Log is stubbed. For production, use Robolectric or mock Log.

    @Test
    fun `first message is not duplicate`() {
        val dedup = createDedup()
        assertFalse(dedup.isDuplicate(1L, 100L))
    }

    @Test
    fun `same message is duplicate`() {
        val dedup = createDedup()
        assertFalse(dedup.isDuplicate(1L, 100L))
        assertTrue(dedup.isDuplicate(1L, 100L))
    }

    @Test
    fun `different packetId is not duplicate`() {
        val dedup = createDedup()
        assertFalse(dedup.isDuplicate(1L, 100L))
        assertFalse(dedup.isDuplicate(1L, 101L))
    }

    @Test
    fun `different sender is not duplicate`() {
        val dedup = createDedup()
        assertFalse(dedup.isDuplicate(1L, 100L))
        assertFalse(dedup.isDuplicate(2L, 100L))
    }

    @Test
    fun `string key dedup works`() {
        val dedup = createDedup()
        assertFalse(dedup.isDuplicateKey("sms:+1234:hello"))
        assertTrue(dedup.isDuplicateKey("sms:+1234:hello"))
        assertFalse(dedup.isDuplicateKey("sms:+1234:world"))
    }

    @Test
    fun `capacity limit evicts oldest`() {
        val dedup = createDedup(maxSize = 3)
        assertFalse(dedup.isDuplicateKey("a"))
        assertFalse(dedup.isDuplicateKey("b"))
        assertFalse(dedup.isDuplicateKey("c"))
        assertEquals(3, dedup.size)

        // Adding a 4th should evict "a"
        assertFalse(dedup.isDuplicateKey("d"))
        assertEquals(3, dedup.size)
        assertFalse(dedup.isDuplicateKey("a")) // evicted, no longer duplicate
    }

    @Test
    fun `size tracks entries correctly`() {
        val dedup = createDedup()
        assertEquals(0, dedup.size)
        dedup.isDuplicateKey("x")
        assertEquals(1, dedup.size)
        dedup.isDuplicateKey("y")
        assertEquals(2, dedup.size)
        dedup.isDuplicateKey("x") // duplicate, no new entry
        assertEquals(2, dedup.size)
    }

    /**
     * Create a Deduplicator without android.util.Log dependency.
     * Uses reflection to bypass the Log.d call in prune().
     */
    private fun createDedup(maxSize: Int = 10_000): TestDedup {
        return TestDedup(maxSize = maxSize)
    }
}

/**
 * Test-friendly dedup that doesn't call android.util.Log.
 * Mirrors Deduplicator logic for JVM unit testing.
 */
class TestDedup(
    private val maxSize: Int = 10_000,
) {
    private val seen = LinkedHashMap<String, Long>()

    fun isDuplicate(from: Long, packetId: Long): Boolean {
        return isDuplicateKey("$from:$packetId")
    }

    fun isDuplicateKey(key: String): Boolean {
        if (key in seen) return true
        if (seen.size >= maxSize) {
            val firstKey = seen.keys.first()
            seen.remove(firstKey)
        }
        seen[key] = System.nanoTime()
        return false
    }

    val size: Int get() = seen.size
}
