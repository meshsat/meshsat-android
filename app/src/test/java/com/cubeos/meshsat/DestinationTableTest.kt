package com.cubeos.meshsat

import com.cubeos.meshsat.routing.Announce
import com.cubeos.meshsat.routing.DestinationTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for DestinationTable (Phase E: routing layer).
 */
class DestinationTableTest {

    private fun makeAnnounce(destHash: ByteArray = ByteArray(16) { it.toByte() }, hopCount: Byte = 1): Announce {
        return Announce(
            flags = 0x01,
            hopCount = hopCount,
            context = 0,
            destHash = destHash,
            signingPub = ByteArray(32),
            encryptionPub = ByteArray(32),
            appData = null,
            random = ByteArray(16),
            signature = ByteArray(64),
        )
    }

    private fun destHashHex(b: ByteArray = ByteArray(16) { it.toByte() }): String {
        return b.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `empty table returns null for lookup`() {
        val table = DestinationTable()
        assertNull(table.lookup("deadbeef01234567"))
    }

    @Test
    fun `update and lookup destination`() {
        val table = DestinationTable()
        table.update(makeAnnounce(hopCount = 1), "mesh_0")
        val dest = table.lookup(destHashHex())
        assertNotNull(dest)
        assertEquals(1, dest!!.hopCount)
        assertEquals("mesh_0", dest.sourceIface)
    }

    @Test
    fun `lower hop count preferred`() {
        val table = DestinationTable()
        val hash = ByteArray(16) { 0x42 }
        table.update(makeAnnounce(destHash = hash, hopCount = 3), "mesh_0")
        table.update(makeAnnounce(destHash = hash, hopCount = 1), "iridium_0")
        val dest = table.lookup(destHashHex(hash))
        assertEquals(1, dest!!.hopCount)
        assertEquals("iridium_0", dest.sourceIface)
    }

    @Test
    fun `higher hop count does not overwrite`() {
        val table = DestinationTable()
        val hash = ByteArray(16) { 0x42 }
        table.update(makeAnnounce(destHash = hash, hopCount = 1), "mesh_0")
        table.update(makeAnnounce(destHash = hash, hopCount = 5), "iridium_0")
        val dest = table.lookup(destHashHex(hash))
        assertEquals(1, dest!!.hopCount)
        assertEquals("mesh_0", dest.sourceIface)
    }

    @Test
    fun `count and all`() {
        val table = DestinationTable()
        assertEquals(0, table.count())
        table.update(makeAnnounce(destHash = ByteArray(16) { 0x01 }), "mesh_0")
        table.update(makeAnnounce(destHash = ByteArray(16) { 0x02 }), "mesh_0")
        assertEquals(2, table.count())
        assertEquals(2, table.all().size)
    }

    @Test
    fun `announce count increments on same hop count`() {
        val table = DestinationTable()
        table.update(makeAnnounce(hopCount = 1), "mesh_0")
        table.update(makeAnnounce(hopCount = 1), "mesh_0")
        val dest = table.lookup(destHashHex())
        assertEquals(2, dest!!.announceCount)
    }
}
