package com.cubeos.meshsat

import com.cubeos.meshsat.engine.IridiumFragment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IridiumFragmentTest {

    @Test
    fun `header roundtrip`() {
        val cases = listOf(
            Triple(0, 1, 0),
            Triple(0, 2, 5),
            Triple(1, 2, 5),
            Triple(3, 4, 15),
            Triple(15, 16, 255),
            Triple(0, 1, 128),
        )
        for ((idx, total, id) in cases) {
            val hdr = IridiumFragment.encodeHeader(idx, total, id)
            val (gotIdx, gotTotal, gotId) = IridiumFragment.decodeHeader(hdr[0], hdr[1])
            assertEquals("fragIndex for ($idx,$total,$id)", idx, gotIdx)
            assertEquals("fragTotal for ($idx,$total,$id)", total, gotTotal)
            assertEquals("msgID for ($idx,$total,$id)", id, gotId)
        }
    }

    @Test
    fun `no fragmentation needed for small message`() {
        val data = ByteArray(100)
        assertNull(IridiumFragment.fragment(data, IridiumFragment.MO_MTU, 0))
    }

    @Test
    fun `no fragmentation at exact MTU`() {
        val data = ByteArray(340)
        assertNull(IridiumFragment.fragment(data, IridiumFragment.MO_MTU, 0))
    }

    @Test
    fun `two fragments for 500 bytes`() {
        val data = ByteArray(500) { (it % 256).toByte() }
        val frags = IridiumFragment.fragment(data, IridiumFragment.MO_MTU, 42)
        assertNotNull(frags)
        assertEquals(2, frags!!.size)

        // Verify headers
        val (idx0, total0, id0) = IridiumFragment.decodeHeader(frags[0][0], frags[0][1])
        assertEquals(0, idx0); assertEquals(2, total0); assertEquals(42, id0)

        val (idx1, total1, id1) = IridiumFragment.decodeHeader(frags[1][0], frags[1][1])
        assertEquals(1, idx1); assertEquals(2, total1); assertEquals(42, id1)

        // Each fragment <= MTU
        for (f in frags) assertTrue("fragment ${f.size} > MTU", f.size <= 340)
    }

    @Test
    fun `three fragment reassembly`() {
        val data = ByteArray(900) { (it % 256).toByte() }
        val frags = IridiumFragment.fragment(data, IridiumFragment.MO_MTU, 10)
        assertNotNull(frags)
        assertEquals(3, frags!!.size)

        val buf = IridiumFragment.ReassemblyBuffer()

        assertNull(buf.addFragment(frags[0]))
        assertNull(buf.addFragment(frags[1]))
        val result = buf.addFragment(frags[2])

        assertNotNull("expected reassembled message after 3rd fragment", result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `out of order reassembly`() {
        val data = ByteArray(500) { (it % 256).toByte() }
        val frags = IridiumFragment.fragment(data, IridiumFragment.MO_MTU, 7)!!

        val buf = IridiumFragment.ReassemblyBuffer()
        assertNull(buf.addFragment(frags[1])) // second first
        val result = buf.addFragment(frags[0])
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `duplicate fragment does not double count`() {
        val data = ByteArray(500) { (it % 256).toByte() }
        val frags = IridiumFragment.fragment(data, IridiumFragment.MO_MTU, 3)!!

        val buf = IridiumFragment.ReassemblyBuffer()
        buf.addFragment(frags[0])
        buf.addFragment(frags[0]) // duplicate
        assertEquals(1, buf.pendingCount())

        val result = buf.addFragment(frags[1])
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `msgID isolation`() {
        val data1 = ByteArray(500) { 0xAA.toByte() }
        val data2 = ByteArray(500) { 0xBB.toByte() }
        val frags1 = IridiumFragment.fragment(data1, IridiumFragment.MO_MTU, 10)!!
        val frags2 = IridiumFragment.fragment(data2, IridiumFragment.MO_MTU, 11)!!

        val buf = IridiumFragment.ReassemblyBuffer()
        buf.addFragment(frags1[0])
        buf.addFragment(frags2[0])
        assertEquals(2, buf.pendingCount())

        val r1 = buf.addFragment(frags1[1])
        assertNotNull(r1)
        assertArrayEquals(data1, r1)

        val r2 = buf.addFragment(frags2[1])
        assertNotNull(r2)
        assertArrayEquals(data2, r2)
    }

    @Test
    fun `max fragment truncation`() {
        // Very large message — truncated to MAX_FRAGMENTS (16).
        val mtu = 100
        val fragPayload = mtu - IridiumFragment.HEADER_SIZE
        val maxData = IridiumFragment.MAX_FRAGMENTS * fragPayload
        val data = ByteArray(maxData + 500)
        val frags = IridiumFragment.fragment(data, mtu, 0)
        assertNotNull(frags)
        assertEquals(IridiumFragment.MAX_FRAGMENTS, frags!!.size)

        // Total payload is truncated to max.
        val totalPayload = frags.sumOf { it.size - IridiumFragment.HEADER_SIZE }
        assertEquals(maxData, totalPayload)
    }
}
