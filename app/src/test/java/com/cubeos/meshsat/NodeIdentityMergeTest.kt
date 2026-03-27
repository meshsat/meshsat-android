package com.cubeos.meshsat

import com.cubeos.meshsat.ble.MeshtasticProtocol.MeshNodeInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for node identity merge logic — ensures OTA-learned identity fields
 * are preserved when config downloads provide partial NodeInfo data.
 */
class NodeIdentityMergeTest {

    /** Simulates the merge logic from MeshtasticBle.addNodeInfo(). */
    private fun merge(existing: MeshNodeInfo?, info: MeshNodeInfo): MeshNodeInfo {
        return info.copy(
            longName = info.longName.ifEmpty { existing?.longName ?: "" },
            shortName = info.shortName.ifEmpty { existing?.shortName ?: "" },
            macaddr = info.macaddr.ifEmpty { existing?.macaddr ?: "" },
            hwModel = if (info.hwModel != 0) info.hwModel else (existing?.hwModel ?: 0),
            batteryLevel = if (info.batteryLevel >= 0) info.batteryLevel else (existing?.batteryLevel ?: -1),
            lastHeard = if (info.lastHeard > 0) info.lastHeard else System.currentTimeMillis(),
        )
    }

    @Test
    fun `full update overwrites all fields`() {
        val existing = MeshNodeInfo(nodeNum = 1, longName = "OldName", shortName = "OLD", hwModel = 7, batteryLevel = 80, lastHeard = 1000L)
        val info = MeshNodeInfo(nodeNum = 1, longName = "NewName", shortName = "NEW", hwModel = 14, batteryLevel = 50, lastHeard = 2000L)
        val merged = merge(existing, info)
        assertEquals("NewName", merged.longName)
        assertEquals("NEW", merged.shortName)
        assertEquals(14, merged.hwModel)
        assertEquals(50, merged.batteryLevel)
        assertEquals(2000L, merged.lastHeard)
    }

    @Test
    fun `empty fields preserved from existing`() {
        val existing = MeshNodeInfo(nodeNum = 1, longName = "MyNode", shortName = "MN", macaddr = "AA:BB:CC", hwModel = 7, batteryLevel = 90, lastHeard = 1000L)
        val info = MeshNodeInfo(nodeNum = 1, longName = "", shortName = "", macaddr = "", hwModel = 0, batteryLevel = -1, lastHeard = 0)
        val merged = merge(existing, info)
        assertEquals("MyNode", merged.longName)
        assertEquals("MN", merged.shortName)
        assertEquals("AA:BB:CC", merged.macaddr)
        assertEquals(7, merged.hwModel)
        assertEquals(90, merged.batteryLevel)
        assertTrue(merged.lastHeard > 0)
    }

    @Test
    fun `partial update merges correctly`() {
        val existing = MeshNodeInfo(nodeNum = 1, longName = "MyNode", shortName = "MN", hwModel = 7, batteryLevel = 90, lastHeard = 1000L)
        val info = MeshNodeInfo(nodeNum = 1, longName = "Updated", shortName = "", hwModel = 0, batteryLevel = 75, lastHeard = 2000L)
        val merged = merge(existing, info)
        assertEquals("Updated", merged.longName)
        assertEquals("MN", merged.shortName)
        assertEquals(7, merged.hwModel)
        assertEquals(75, merged.batteryLevel)
        assertEquals(2000L, merged.lastHeard)
    }

    @Test
    fun `new node with no existing`() {
        val info = MeshNodeInfo(nodeNum = 99, longName = "Brand New", shortName = "BN", hwModel = 14, batteryLevel = 100, lastHeard = 5000L)
        val merged = merge(null, info)
        assertEquals("Brand New", merged.longName)
        assertEquals("BN", merged.shortName)
        assertEquals(14, merged.hwModel)
        assertEquals(100, merged.batteryLevel)
        assertEquals(5000L, merged.lastHeard)
    }
}
