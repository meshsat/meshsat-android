package com.cubeos.meshsat

import com.cubeos.meshsat.dtn.BundleFragmenter
import com.cubeos.meshsat.dtn.BundleReassembler
import com.cubeos.meshsat.dtn.DtnProtocol
import org.junit.Assert.*
import org.junit.Test

class BundleFragmenterTest {
    @Test
    fun `fragment and reassemble round-trip`() {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val fragments = BundleFragmenter.fragment(data, mtu = 340)
        assertTrue("Should produce multiple fragments", fragments.size > 1)

        val reassembler = BundleReassembler()
        var result: ByteArray? = null
        for (frag in fragments) {
            result = reassembler.feed(frag)
        }
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `small payload produces single fragment`() {
        val data = "Hello".toByteArray()
        val fragments = BundleFragmenter.fragment(data, mtu = 340)
        assertEquals(1, fragments.size)

        val reassembler = BundleReassembler()
        val result = reassembler.feed(fragments[0])
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `out-of-order reassembly works`() {
        val data = ByteArray(500) { it.toByte() }
        val fragments = BundleFragmenter.fragment(data, mtu = 200)
        assertTrue(fragments.size > 1)

        val reassembler = BundleReassembler()
        // Feed in reverse order
        for (frag in fragments.reversed()) {
            val result = reassembler.feed(frag)
            if (result != null) {
                assertArrayEquals(data, result)
                return
            }
        }
        fail("Should have reassembled")
    }

    @Test
    fun `fragment header preserves fields`() {
        val data = ByteArray(100)
        val fragments = BundleFragmenter.fragment(data, mtu = 60)
        for ((i, frag) in fragments.withIndex()) {
            val (header, _) = BundleFragmenter.parseFragment(frag)!!
            assertEquals(i, header.fragmentIndex)
            assertEquals(fragments.size, header.fragmentCount)
            assertEquals(100, header.totalSize)
        }
    }

    @Test
    fun `custody offer wire format round-trip`() {
        val hash = ByteArray(16) { it.toByte() }
        val offer = DtnProtocol.CustodyOffer(hash, 1000, 3, 3600, 1)
        val bytes = offer.marshal()
        assertEquals(DtnProtocol.CustodyOffer.SIZE, bytes.size)

        val parsed = DtnProtocol.CustodyOffer.unmarshal(bytes)!!
        assertArrayEquals(hash, parsed.bundleHash)
        assertEquals(1000, parsed.totalSize)
        assertEquals(3, parsed.fragmentCount)
        assertEquals(3600, parsed.ttlSeconds)
        assertEquals(1, parsed.priority)
    }

    @Test
    fun `custody accept wire format round-trip`() {
        val hash = ByteArray(16) { (it + 10).toByte() }
        val custodian = ByteArray(16) { (it + 20).toByte() }
        val accept = DtnProtocol.CustodyAccept(hash, custodian)
        val bytes = accept.marshal()
        assertEquals(DtnProtocol.CustodyAccept.SIZE, bytes.size)

        val parsed = DtnProtocol.CustodyAccept.unmarshal(bytes)!!
        assertArrayEquals(hash, parsed.bundleHash)
        assertArrayEquals(custodian, parsed.custodianHash)
    }

    @Test
    fun `fragment header wire format round-trip`() {
        val id = ByteArray(16) { (it * 3).toByte() }
        val header = DtnProtocol.FragmentHeader(id, 5, 10, 5000)
        val bytes = header.marshal()
        assertEquals(DtnProtocol.FragmentHeader.SIZE, bytes.size)

        val parsed = DtnProtocol.FragmentHeader.unmarshal(bytes)!!
        assertArrayEquals(id, parsed.bundleId)
        assertEquals(5, parsed.fragmentIndex)
        assertEquals(10, parsed.fragmentCount)
        assertEquals(5000, parsed.totalSize)
    }
}
