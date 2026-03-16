package com.cubeos.meshsat

import com.cubeos.meshsat.engine.BurstMessage
import com.cubeos.meshsat.engine.BurstQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Unit tests for BurstQueue (Phase D: opportunistic satellite burst).
 */
class BurstQueueTest {

    @Test
    fun `empty queue flush returns null`() {
        val bq = BurstQueue(maxSize = 10, maxAge = 5.minutes)
        val (payload, count) = bq.flush()
        assertNull(payload)
        assertEquals(0, count)
    }

    @Test
    fun `enqueue and flush single message`() {
        val bq = BurstQueue(maxSize = 10, maxAge = 5.minutes)
        bq.enqueue(BurstMessage(payload = "hello".toByteArray(), interfaceId = "iridium_0"))
        assertEquals(1, bq.pending())

        val (payload, count) = bq.flush()
        assertNotNull(payload)
        assertEquals(1, count)
        assertEquals(0, bq.pending())
    }

    @Test
    fun `TLV wire format header byte`() {
        val bq = BurstQueue(maxSize = 10, maxAge = 5.minutes)
        bq.enqueue(BurstMessage(payload = "test".toByteArray(), interfaceId = "iridium_0"))
        val (payload, _) = bq.flush()
        assertNotNull(payload)
        assertEquals(BurstQueue.BURST_TYPE_BYTE.toByte(), payload!![0])
    }

    @Test
    fun `pack and unpack round trip`() {
        val messages = listOf("alpha", "bravo", "charlie").map {
            BurstMessage(payload = it.toByteArray(), interfaceId = "iridium_0")
        }
        val (wire, count) = BurstQueue.packBurst(messages, 340)
        assertEquals(3, count)

        val unpacked = BurstQueue.unpackBurst(wire)
        assertEquals(3, unpacked.size)
        assertEquals("alpha", String(unpacked[0]))
        assertEquals("bravo", String(unpacked[1]))
        assertEquals("charlie", String(unpacked[2]))
    }

    @Test
    fun `priority ordering - higher priority first`() {
        val bq = BurstQueue(maxSize = 10, maxAge = 5.minutes)
        bq.enqueue(BurstMessage(payload = "low".toByteArray(), priority = 0, interfaceId = "iridium_0"))
        bq.enqueue(BurstMessage(payload = "high".toByteArray(), priority = 10, interfaceId = "iridium_0"))
        bq.enqueue(BurstMessage(payload = "mid".toByteArray(), priority = 5, interfaceId = "iridium_0"))

        val (payload, count) = bq.flush()
        assertEquals(3, count)
        val unpacked = BurstQueue.unpackBurst(payload!!)
        assertEquals("high", String(unpacked[0]))
        assertEquals("mid", String(unpacked[1]))
        assertEquals("low", String(unpacked[2]))
    }

    @Test
    fun `shouldFlush when maxSize reached`() {
        val bq = BurstQueue(maxSize = 2, maxAge = 5.minutes)
        assertFalse(bq.shouldFlush())
        bq.enqueue(BurstMessage(payload = "a".toByteArray(), interfaceId = "iridium_0"))
        assertFalse(bq.shouldFlush())
        bq.enqueue(BurstMessage(payload = "b".toByteArray(), interfaceId = "iridium_0"))
        assertTrue(bq.shouldFlush())
    }

    @Test
    fun `MTU limit respected`() {
        val bq = BurstQueue(maxSize = 100, maxAge = 5.minutes)
        val largePayload = ByteArray(200) { 0x42 }
        bq.enqueue(BurstMessage(payload = largePayload, interfaceId = "iridium_0"))
        bq.enqueue(BurstMessage(payload = largePayload, interfaceId = "iridium_0"))

        val (payload, count) = bq.flush()
        assertNotNull(payload)
        assertTrue("Packed payload should fit in MTU", payload!!.size <= 340)
        assertEquals(1, count) // only 1 fits
        assertEquals(1, bq.pending()) // second stays in queue
    }
}
