package com.cubeos.meshsat

import com.cubeos.meshsat.reticulum.*
import com.cubeos.meshsat.routing.toHex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Transport Node components:
 * - RnsForwardingTable: learn, lookup, prune, cost-preference
 * - RnsTransportNode: packet routing, dedup, hop limits (basic)
 */
class RnsTransportNodeTest {

    private lateinit var table: RnsForwardingTable

    private val destA = ByteArray(16) { 0x0A }
    private val destB = ByteArray(16) { 0x0B }
    private val destC = ByteArray(16) { 0x0C }
    private val nextHop1 = ByteArray(16) { 0x01 }

    @Before
    fun setup() {
        table = RnsForwardingTable()
    }

    // ═══════════════════════════════════════════════════════════════
    // ForwardingTable — learn & lookup
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun learn_firstEntry_returnsTrue() {
        val isNew = table.learn(destA, null, "mesh_0", hops = 2)
        assertTrue(isNew)
    }

    @Test
    fun learn_secondEntryForSameDest_returnsFalse() {
        table.learn(destA, null, "mesh_0", hops = 2)
        val isNew = table.learn(destA, null, "iridium_0", hops = 3)
        assertFalse(isNew)
    }

    @Test
    fun lookup_returnsEntry() {
        table.learn(destA, nextHop1, "mesh_0", hops = 2)
        val entry = table.lookup(destA)
        assertNotNull(entry)
        assertEquals("mesh_0", entry!!.egressInterface)
        assertEquals(2, entry.hops)
        assertArrayEquals(nextHop1, entry.nextHop)
    }

    @Test
    fun lookup_unknownDest_returnsNull() {
        assertNull(table.lookup(destA))
    }

    @Test
    fun lookup_prefersCheaperInterface() {
        table.learn(destA, null, "iridium_0", hops = 1, costCents = 5)
        table.learn(destA, null, "mesh_0", hops = 2, costCents = 0)

        val best = table.lookup(destA)
        assertNotNull(best)
        assertEquals("mesh_0", best!!.egressInterface)
    }

    @Test
    fun lookup_prefersFewerHopsAtSameCost() {
        table.learn(destA, null, "mesh_0", hops = 5, costCents = 0)
        table.learn(destA, null, "aprs_0", hops = 2, costCents = 0)

        val best = table.lookup(destA)
        assertNotNull(best)
        assertEquals("aprs_0", best!!.egressInterface)
    }

    @Test
    fun learn_updatesLowerHopCount() {
        table.learn(destA, null, "mesh_0", hops = 5)
        table.learn(destA, null, "mesh_0", hops = 2)

        val entry = table.lookup(destA)
        assertEquals(2, entry!!.hops)
    }

    @Test
    fun learn_doesNotUpdateHigherHopCount() {
        table.learn(destA, null, "mesh_0", hops = 2)
        table.learn(destA, null, "mesh_0", hops = 5)

        val entry = table.lookup(destA)
        assertEquals(2, entry!!.hops)
    }

    // ═══════════════════════════════════════════════════════════════
    // ForwardingTable — allEntries
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun allEntries_returnsMultipleInterfaces() {
        table.learn(destA, null, "mesh_0", hops = 2, costCents = 0)
        table.learn(destA, null, "iridium_0", hops = 1, costCents = 5)
        table.learn(destA, null, "sms_0", hops = 3, costCents = 1)

        val entries = table.allEntries(destA)
        assertEquals(3, entries.size)
        // Sorted by score — mesh (0 cost, 2 hops) first
        assertEquals("mesh_0", entries[0].egressInterface)
    }

    @Test
    fun allEntries_unknownDest_returnsEmpty() {
        assertTrue(table.allEntries(destA).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // ForwardingTable — hasEntry
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun hasEntry_true() {
        table.learn(destA, null, "mesh_0", hops = 1)
        assertTrue(table.hasEntry(destA))
    }

    @Test
    fun hasEntry_false() {
        assertFalse(table.hasEntry(destA))
    }

    // ═══════════════════════════════════════════════════════════════
    // ForwardingTable — remove
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun remove_clearsDest() {
        table.learn(destA, null, "mesh_0", hops = 1)
        table.learn(destA, null, "iridium_0", hops = 2)
        table.remove(destA)
        assertFalse(table.hasEntry(destA))
    }

    @Test
    fun removeInterface_removesEntries() {
        table.learn(destA, null, "mesh_0", hops = 1)
        table.learn(destB, null, "mesh_0", hops = 2)
        table.learn(destC, null, "iridium_0", hops = 1)

        table.removeInterface("mesh_0")

        assertFalse(table.hasEntry(destA))
        assertFalse(table.hasEntry(destB))
        assertTrue(table.hasEntry(destC))
    }

    // ═══════════════════════════════════════════════════════════════
    // ForwardingTable — prune
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun prune_removesExpiredEntries() {
        // Use very short TTL
        val shortTable = RnsForwardingTable(ttlMs = 1)
        shortTable.learn(destA, null, "mesh_0", hops = 1)

        Thread.sleep(10)
        val removed = shortTable.prune()

        assertTrue(removed > 0)
        assertFalse(shortTable.hasEntry(destA))
    }

    @Test
    fun prune_keepsActiveEntries() {
        table.learn(destA, null, "mesh_0", hops = 1)
        val removed = table.prune()
        assertEquals(0, removed)
        assertTrue(table.hasEntry(destA))
    }

    // ═══════════════════════════════════════════════════════════════
    // ForwardingTable — size and snapshot
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun size_countsDestinations() {
        table.learn(destA, null, "mesh_0", hops = 1)
        table.learn(destA, null, "iridium_0", hops = 2) // same dest, different interface
        table.learn(destB, null, "mesh_0", hops = 1)

        assertEquals(2, table.size())
        assertEquals(3, table.totalEntries())
    }

    @Test
    fun snapshot_returnsBestPerDest() {
        table.learn(destA, null, "iridium_0", hops = 1, costCents = 5)
        table.learn(destA, null, "mesh_0", hops = 2, costCents = 0)
        table.learn(destB, null, "mesh_0", hops = 1, costCents = 0)

        val snap = table.snapshot()
        assertEquals(2, snap.size)
        assertEquals("mesh_0", snap[destA.toHex()]?.egressInterface)
        assertEquals("mesh_0", snap[destB.toHex()]?.egressInterface)
    }

    // ═══════════════════════════════════════════════════════════════
    // ForwardingTable — score ordering
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun score_freeBeforePaid() {
        val freeEntry = RnsForwardingTable.Entry(
            destHash = destA, nextHop = null,
            egressInterface = "mesh_0", hops = 5, costCents = 0,
        )
        val paidEntry = RnsForwardingTable.Entry(
            destHash = destA, nextHop = null,
            egressInterface = "iridium_0", hops = 1, costCents = 5,
        )
        assertTrue(freeEntry.score < paidEntry.score)
    }

    @Test
    fun score_fewerHopsPreferred() {
        val near = RnsForwardingTable.Entry(
            destHash = destA, nextHop = null,
            egressInterface = "mesh_0", hops = 1, costCents = 0,
        )
        val far = RnsForwardingTable.Entry(
            destHash = destA, nextHop = null,
            egressInterface = "mesh_0", hops = 5, costCents = 0,
        )
        assertTrue(near.score < far.score)
    }

    // ═══════════════════════════════════════════════════════════════
    // Transport Node flag
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun transportNodeCapFlag() {
        assertEquals(0x20, CAP_TRANSPORT_NODE.toInt())
        // Should combine with other caps
        val caps = (MeshSatAppData.CAP_MESH.toInt() or CAP_TRANSPORT_NODE.toInt()).toByte()
        assertTrue(caps.toInt() and CAP_TRANSPORT_NODE.toInt() != 0)
        assertTrue(caps.toInt() and MeshSatAppData.CAP_MESH.toInt() != 0)
    }

    // ═══════════════════════════════════════════════════════════════
    // Packet helper tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun wrapForTransport_setsHeader2() {
        val packet = RnsPacket.data(destHash = destA, payload = "hello".toByteArray())
        val transportId = ByteArray(16) { 0xFF.toByte() }
        val wrapped = RnsPacket.wrapForTransport(packet, transportId)

        assertEquals(RnsConstants.HEADER_2, wrapped.headerType)
        assertEquals(RnsConstants.PROPAGATION_TRANSPORT, wrapped.propagationType)
        assertArrayEquals(transportId, wrapped.transportId)
        assertArrayEquals(destA, wrapped.destHash)
    }

    @Test
    fun wrapForTransport_roundTrip() {
        val packet = RnsPacket.data(destHash = destA, payload = "hello".toByteArray())
        val transportId = ByteArray(16) { 0xFF.toByte() }
        val wrapped = RnsPacket.wrapForTransport(packet, transportId)
        val raw = wrapped.marshal()
        val parsed = RnsPacket.unmarshal(raw)

        assertEquals(RnsConstants.HEADER_2, parsed.headerType)
        assertArrayEquals(transportId, parsed.transportId)
        assertArrayEquals(destA, parsed.destHash)
        assertArrayEquals("hello".toByteArray(), parsed.data)
    }

    @Test
    fun maxHops_constant() {
        assertEquals(128, RnsTransportNode.MAX_HOPS)
        assertEquals(RnsConstants.MAX_HOPS, RnsTransportNode.MAX_HOPS)
    }
}
