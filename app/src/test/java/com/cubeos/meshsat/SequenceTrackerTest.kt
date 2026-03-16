package com.cubeos.meshsat

import com.cubeos.meshsat.engine.SequenceTracker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for SequenceTracker (Phase C: QoS sequence numbers).
 */
class SequenceTrackerTest {

    @Test
    fun `egress sequence starts at 1`() {
        val tracker = SequenceTracker()
        assertEquals(1L, tracker.nextEgressSeq("mesh_0"))
    }

    @Test
    fun `egress sequence increments`() {
        val tracker = SequenceTracker()
        assertEquals(1L, tracker.nextEgressSeq("mesh_0"))
        assertEquals(2L, tracker.nextEgressSeq("mesh_0"))
        assertEquals(3L, tracker.nextEgressSeq("mesh_0"))
    }

    @Test
    fun `per-interface independent counters`() {
        val tracker = SequenceTracker()
        assertEquals(1L, tracker.nextEgressSeq("mesh_0"))
        assertEquals(1L, tracker.nextEgressSeq("iridium_0"))
        assertEquals(2L, tracker.nextEgressSeq("mesh_0"))
        assertEquals(2L, tracker.nextEgressSeq("iridium_0"))
    }

    @Test
    fun `currentEgressSeq returns 0 before first use`() {
        val tracker = SequenceTracker()
        assertEquals(0L, tracker.currentEgressSeq("mesh_0"))
    }

    @Test
    fun `currentEgressSeq reflects last assigned`() {
        val tracker = SequenceTracker()
        tracker.nextEgressSeq("mesh_0")
        tracker.nextEgressSeq("mesh_0")
        assertEquals(2L, tracker.currentEgressSeq("mesh_0"))
    }

    @Test
    fun `ingress sequence independent from egress`() {
        val tracker = SequenceTracker()
        assertEquals(1L, tracker.nextEgressSeq("mesh_0"))
        assertEquals(1L, tracker.nextIngressSeq("mesh_0"))
        assertEquals(2L, tracker.nextEgressSeq("mesh_0"))
        assertEquals(2L, tracker.nextIngressSeq("mesh_0"))
    }

    @Test
    fun `reset clears single interface`() {
        val tracker = SequenceTracker()
        tracker.nextEgressSeq("mesh_0")
        tracker.nextEgressSeq("iridium_0")
        tracker.reset("mesh_0")
        assertEquals(0L, tracker.currentEgressSeq("mesh_0"))
        assertEquals(1L, tracker.currentEgressSeq("iridium_0")) // unaffected
    }

    @Test
    fun `resetAll clears all interfaces`() {
        val tracker = SequenceTracker()
        tracker.nextEgressSeq("mesh_0")
        tracker.nextEgressSeq("iridium_0")
        tracker.resetAll()
        assertEquals(0L, tracker.currentEgressSeq("mesh_0"))
        assertEquals(0L, tracker.currentEgressSeq("iridium_0"))
    }
}
