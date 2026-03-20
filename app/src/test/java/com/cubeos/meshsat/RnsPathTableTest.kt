package com.cubeos.meshsat

import com.cubeos.meshsat.reticulum.RnsConstants
import com.cubeos.meshsat.reticulum.RnsInterface
import com.cubeos.meshsat.reticulum.RnsPath
import com.cubeos.meshsat.reticulum.RnsPathDiscovery
import com.cubeos.meshsat.reticulum.RnsPathTable
import com.cubeos.meshsat.reticulum.RnsReceiveCallback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Reticulum path discovery and cost-aware routing (MESHSAT-222).
 */
class RnsPathTableTest {

    private fun makeInterface(
        id: String,
        cost: Int = 0,
        latency: Int = 0,
        online: Boolean = true,
    ): RnsInterface = object : RnsInterface {
        override val interfaceId = id
        override val name = id
        override val mtu = 500
        override val costCents = cost
        override val latencyMs = latency
        override val isOnline = online
        override fun setReceiveCallback(callback: RnsReceiveCallback?) {}
        override suspend fun send(packet: ByteArray): String? = null
        override suspend fun start() {}
        override suspend fun stop() {}
    }

    private fun destHash(b: Byte = 0x01) = ByteArray(16) { b }

    // --- Basic operations ---

    @Test
    fun `updateFromAnnounce returns true for new destination`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        val isNew = table.updateFromAnnounce(destHash(), "mesh_0", hops = 3)
        assertTrue(isNew)
    }

    @Test
    fun `updateFromAnnounce returns false for existing destination`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 3)
        val isNew = table.updateFromAnnounce(destHash(), "mesh_0", hops = 2)
        assertFalse(isNew)
    }

    @Test
    fun `bestPath returns path after announce`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 1)
        val path = table.bestPath(destHash())
        assertNotNull(path)
        assertEquals("mesh_0", path!!.interfaceId)
        assertEquals(1, path.hops)
    }

    @Test
    fun `bestPath returns null for unknown destination`() {
        val table = RnsPathTable(interfaces = { emptyList() })
        assertNull(table.bestPath(destHash()))
    }

    @Test
    fun `hasPath returns true after announce`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 1)
        assertTrue(table.hasPath(destHash()))
    }

    @Test
    fun `hopsTo returns hop count`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 5)
        assertEquals(5, table.hopsTo(destHash()))
    }

    @Test
    fun `hopsTo returns -1 for unknown destination`() {
        val table = RnsPathTable(interfaces = { emptyList() })
        assertEquals(-1, table.hopsTo(destHash()))
    }

    // --- Cost-aware routing ---

    @Test
    fun `bestPath prefers free over paid`() {
        val ifaces = listOf(
            makeInterface("mesh_0", cost = 0, latency = 200),
            makeInterface("iridium_0", cost = 5, latency = 60000),
        )
        val table = RnsPathTable(interfaces = { ifaces })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 3)
        table.updateFromAnnounce(destHash(), "iridium_0", hops = 1)

        val best = table.bestPath(destHash())!!
        assertEquals("mesh_0", best.interfaceId)  // free, even though more hops
    }

    @Test
    fun `bestPath prefers fewer hops at same cost`() {
        val ifaces = listOf(
            makeInterface("mesh_0", cost = 0),
            makeInterface("aprs_0", cost = 0),
        )
        val table = RnsPathTable(interfaces = { ifaces })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 5)
        table.updateFromAnnounce(destHash(), "aprs_0", hops = 2)

        val best = table.bestPath(destHash())!!
        assertEquals("aprs_0", best.interfaceId)
    }

    @Test
    fun `bestPath prefers online over offline`() {
        val ifaces = listOf(
            makeInterface("mesh_0", cost = 0, online = false),
            makeInterface("sms_0", cost = 1, online = true),
        )
        val table = RnsPathTable(interfaces = { ifaces })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 1)
        table.updateFromAnnounce(destHash(), "sms_0", hops = 1)

        val best = table.bestPath(destHash())!!
        assertEquals("sms_0", best.interfaceId)  // online, even though more expensive
    }

    @Test
    fun `allPaths returns sorted by score`() {
        val ifaces = listOf(
            makeInterface("iridium_0", cost = 5),
            makeInterface("sms_0", cost = 1),
            makeInterface("mesh_0", cost = 0),
        )
        val table = RnsPathTable(interfaces = { ifaces })
        table.updateFromAnnounce(destHash(), "iridium_0", hops = 1)
        table.updateFromAnnounce(destHash(), "sms_0", hops = 1)
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 1)

        val paths = table.allPaths(destHash())
        assertEquals(3, paths.size)
        assertEquals("mesh_0", paths[0].interfaceId)
        assertEquals("sms_0", paths[1].interfaceId)
        assertEquals("iridium_0", paths[2].interfaceId)
    }

    // --- Multi-path & failover ---

    @Test
    fun `multiple paths per destination`() {
        val ifaces = listOf(makeInterface("mesh_0"), makeInterface("sms_0"))
        val table = RnsPathTable(interfaces = { ifaces })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 1)
        table.updateFromAnnounce(destHash(), "sms_0", hops = 2)

        assertEquals(1, table.destCount())
        assertEquals(2, table.pathCount())
    }

    @Test
    fun `removeDest removes all paths`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 1)
        assertTrue(table.hasPath(destHash()))

        table.removeDest(destHash())
        assertFalse(table.hasPath(destHash()))
    }

    @Test
    fun `removeInterface cleans up paths`() {
        val ifaces = listOf(makeInterface("mesh_0"), makeInterface("sms_0"))
        val table = RnsPathTable(interfaces = { ifaces })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 1)
        table.updateFromAnnounce(destHash(), "sms_0", hops = 1)
        assertEquals(2, table.pathCount())

        table.removeInterface("mesh_0")
        assertEquals(1, table.pathCount())
        assertEquals("sms_0", table.bestPath(destHash())!!.interfaceId)
    }

    // --- Path score ---

    @Test
    fun `path score calculation`() {
        val path = RnsPath(
            destHash = destHash(),
            nextHop = null,
            interfaceId = "mesh_0",
            hops = 3,
            costCents = 0,
            latencyMs = 200,
        )
        // score = 0*1000 + 3*100 + 200/100 = 302
        assertEquals(302, path.score)
    }

    @Test
    fun `expensive path has higher score`() {
        val free = RnsPath(destHash(), null, "mesh_0", 3, 0, 200)
        val paid = RnsPath(destHash(), null, "iridium_0", 1, 5, 60000)
        // free: 0 + 300 + 2 = 302
        // paid: 5000 + 100 + 600 = 5700
        assertTrue(free.score < paid.score)
    }

    // --- Update semantics ---

    @Test
    fun `update prefers lower hop count`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 5)
        assertEquals(5, table.bestPath(destHash())!!.hops)

        table.updateFromAnnounce(destHash(), "mesh_0", hops = 2)
        assertEquals(2, table.bestPath(destHash())!!.hops)
    }

    @Test
    fun `update does not increase hop count`() {
        val table = RnsPathTable(interfaces = { listOf(makeInterface("mesh_0")) })
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 2)
        table.updateFromAnnounce(destHash(), "mesh_0", hops = 5)
        assertEquals(2, table.bestPath(destHash())!!.hops)  // stays at 2
    }

    // --- Path discovery packets ---

    @Test
    fun `path request creates valid packet`() {
        val raw = RnsPathDiscovery.createRequest(destHash())
        assertTrue(raw.size >= 18)  // min packet size
    }

    @Test
    fun `path response round-trip`() {
        val target = ByteArray(16) { 0x01 }
        val nextHop = ByteArray(16) { 0x02 }
        val hops = 3

        val raw = RnsPathDiscovery.createResponse(destHash(0x03), target, nextHop, hops)
        val packet = com.cubeos.meshsat.reticulum.RnsPacket.unmarshal(raw)
        val parsed = RnsPathDiscovery.parseResponse(packet.data)

        assertNotNull(parsed)
        assertArrayEquals(target, parsed!!.first)
        assertArrayEquals(nextHop, parsed.second)
        assertEquals(3, parsed.third)
    }

    @Test
    fun `path response rejects too-short payload`() {
        assertNull(RnsPathDiscovery.parseResponse(ByteArray(10)))
    }
}
