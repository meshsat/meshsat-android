package com.cubeos.meshsat.aprs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APRS directed message tracker tests (MESHSAT-232).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AprsMessageTrackerTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `send assigns sequential message IDs`() {
        val tracker = AprsMessageTracker(testScope)
        val sent = mutableListOf<String>()
        tracker.onSend = { _, _, msgId -> sent.add(msgId) }

        val id1 = tracker.send("PA3ABC", "Hello")
        val id2 = tracker.send("PA3ABC", "World")

        assertEquals("1", id1)
        assertEquals("2", id2)
        assertEquals(2, sent.size)
    }

    @Test
    fun `message starts as PENDING`() {
        val tracker = AprsMessageTracker(testScope)
        tracker.onSend = { _, _, _ -> }

        val id = tracker.send("PA3ABC", "test")

        assertEquals(AprsMessageTracker.DeliveryStatus.PENDING, tracker.getStatus(id))
    }

    @Test
    fun `handleAck changes status to ACKED`() {
        val tracker = AprsMessageTracker(testScope)
        tracker.onSend = { _, _, _ -> }
        var lastStatus: AprsMessageTracker.DeliveryStatus? = null
        tracker.onStatusChange = { _, status -> lastStatus = status }

        val id = tracker.send("PA3ABC", "test")
        tracker.handleAck(id)

        assertEquals(AprsMessageTracker.DeliveryStatus.ACKED, tracker.getStatus(id))
        assertEquals(AprsMessageTracker.DeliveryStatus.ACKED, lastStatus)
    }

    @Test
    fun `handleRej changes status to REJECTED`() {
        val tracker = AprsMessageTracker(testScope)
        tracker.onSend = { _, _, _ -> }

        val id = tracker.send("PA3ABC", "test")
        tracker.handleRej(id)

        assertEquals(AprsMessageTracker.DeliveryStatus.REJECTED, tracker.getStatus(id))
    }

    @Test
    fun `processInbound handles ack packet`() {
        val tracker = AprsMessageTracker(testScope)
        tracker.onSend = { _, _, _ -> }

        val id = tracker.send("PA3ABC", "test")

        val ackPkt = AprsPacket(
            source = "PA3ABC",
            dataType = ':',
            msgTo = "MYCALL",
            message = "ack$id",
        )

        assertTrue(tracker.processInbound(ackPkt))
        assertEquals(AprsMessageTracker.DeliveryStatus.ACKED, tracker.getStatus(id))
    }

    @Test
    fun `processInbound handles rej packet`() {
        val tracker = AprsMessageTracker(testScope)
        tracker.onSend = { _, _, _ -> }

        val id = tracker.send("PA3ABC", "test")

        val rejPkt = AprsPacket(
            source = "PA3ABC",
            dataType = ':',
            msgTo = "MYCALL",
            message = "rej$id",
        )

        assertTrue(tracker.processInbound(rejPkt))
        assertEquals(AprsMessageTracker.DeliveryStatus.REJECTED, tracker.getStatus(id))
    }

    @Test
    fun `processInbound ignores non-message packets`() {
        val tracker = AprsMessageTracker(testScope)
        val posPkt = AprsPacket(source = "N0CALL", dataType = '!')
        assertFalse(tracker.processInbound(posPkt))
    }

    @Test
    fun `processInbound ignores unknown ack IDs`() {
        val tracker = AprsMessageTracker(testScope)
        val ackPkt = AprsPacket(
            source = "PA3ABC", dataType = ':',
            msgTo = "MYCALL", message = "ack999",
        )
        assertFalse(tracker.processInbound(ackPkt))
    }

    @Test
    fun `getPending returns only PENDING messages`() {
        val tracker = AprsMessageTracker(testScope)
        tracker.onSend = { _, _, _ -> }

        tracker.send("PA3ABC", "msg1")
        val id2 = tracker.send("PA3ABC", "msg2")
        tracker.send("PA3ABC", "msg3")
        tracker.handleAck(id2)

        assertEquals(2, tracker.getPending().size)
    }

    @Test
    fun `getStatus returns null for unknown ID`() {
        val tracker = AprsMessageTracker(testScope)
        assertNull(tracker.getStatus("999"))
    }

    @Test
    fun `cancelAll clears pending`() {
        val tracker = AprsMessageTracker(testScope)
        tracker.onSend = { _, _, _ -> }

        tracker.send("PA3ABC", "msg1")
        tracker.send("PA3ABC", "msg2")
        tracker.cancelAll()

        assertEquals(0, tracker.getPending().size)
    }

    @Test
    fun `max retries is 3`() {
        assertEquals(3, AprsMessageTracker.MAX_RETRIES)
    }

    @Test
    fun `retry interval is 30 seconds`() {
        assertEquals(30_000L, AprsMessageTracker.RETRY_INTERVAL_MS)
    }
}
