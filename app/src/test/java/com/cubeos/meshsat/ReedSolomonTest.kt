package com.cubeos.meshsat

import com.cubeos.meshsat.fec.FecHeader
import com.cubeos.meshsat.fec.ReedSolomon
import org.junit.Assert.*
import org.junit.Test

class ReedSolomonTest {
    @Test
    fun `encode produces correct shard count`() {
        val data = "Hello, MeshSat FEC!".toByteArray()
        val shards = ReedSolomon.encode(data, dataShards = 4, parityShards = 2)
        assertEquals(6, shards.size)
    }

    @Test
    fun `each shard has FEC header`() {
        val data = ByteArray(100) { it.toByte() }
        val shards = ReedSolomon.encode(data, 5, 2)
        for ((i, shard) in shards.withIndex()) {
            val header = FecHeader.unmarshal(shard)
            assertNotNull(header)
            assertEquals(5, header!!.dataShards)
            assertEquals(2, header.parityShards)
            assertEquals(i, header.shardIndex)
        }
    }

    @Test
    fun `decode with all data shards (no erasures)`() {
        val data = "Hello, World! This is a FEC test payload.".toByteArray()
        val shards = ReedSolomon.encode(data, 4, 2)
        val dataOnly = shards.take(4) // just the data shards
        val decoded = ReedSolomon.decode(dataOnly, data.size)
        assertNotNull(decoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `decode with one data shard missing (use parity)`() {
        val data = ByteArray(40) { (it * 3 + 7).toByte() }
        val shards = ReedSolomon.encode(data, 4, 2)
        // Drop shard 2 (data), use shards 0,1,3,4 (3 data + 1 parity)
        val available = listOf(shards[0], shards[1], shards[3], shards[4])
        val decoded = ReedSolomon.decode(available, data.size)
        assertNotNull(decoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `decode with two data shards missing (use both parities)`() {
        val data = ByteArray(50) { (it * 7 + 13).toByte() }
        val shards = ReedSolomon.encode(data, 5, 2)
        // Drop shards 1 and 3, use shards 0,2,4,5,6
        val available = listOf(shards[0], shards[2], shards[4], shards[5], shards[6])
        val decoded = ReedSolomon.decode(available, data.size)
        assertNotNull(decoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `decode fails with too few shards`() {
        val data = ByteArray(20) { it.toByte() }
        val shards = ReedSolomon.encode(data, 4, 2)
        val tooFew = shards.take(3) // only 3 of 4 needed
        val decoded = ReedSolomon.decode(tooFew, data.size)
        assertNull(decoded)
    }

    @Test
    fun `round-trip with single byte payload`() {
        val data = byteArrayOf(42)
        val shards = ReedSolomon.encode(data, 1, 1)
        assertEquals(2, shards.size)
        val decoded = ReedSolomon.decode(listOf(shards[1]), data.size) // use only parity
        assertNotNull(decoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `FEC header round-trip`() {
        val header = FecHeader(dataShards = 10, parityShards = 3, shardIndex = 5)
        val bytes = header.marshal()
        assertEquals(4, bytes.size)
        val parsed = FecHeader.unmarshal(bytes)!!
        assertEquals(10, parsed.dataShards)
        assertEquals(3, parsed.parityShards)
        assertEquals(5, parsed.shardIndex)
        assertTrue(parsed.isDataShard)
        assertFalse(parsed.isParityShard)
    }
}
