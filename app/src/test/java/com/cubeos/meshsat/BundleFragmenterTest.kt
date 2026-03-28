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
    fun `custody offer wire format matches bridge (0x16, 37B header)`() {
        val custodyId = ByteArray(16) { it.toByte() }
        val sourceHash = ByteArray(16) { (it + 10).toByte() }
        val payload = "test payload".toByteArray()
        val offer = DtnProtocol.CustodyOffer(custodyId, sourceHash, 42, payload)
        val bytes = offer.marshal()

        assertEquals(0x16.toByte(), bytes[0]) // type byte
        assertEquals(DtnProtocol.CustodyOffer.HEADER_SIZE + payload.size, bytes.size)

        val parsed = DtnProtocol.CustodyOffer.unmarshal(bytes)!!
        assertArrayEquals(custodyId, parsed.custodyId)
        assertArrayEquals(sourceHash, parsed.sourceHash)
        assertEquals(42, parsed.deliveryId)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `custody ack wire format matches bridge (0x17, 97B)`() {
        val custodyId = ByteArray(16) { (it + 20).toByte() }
        val acceptorHash = ByteArray(16) { (it + 30).toByte() }
        val signature = ByteArray(64) { (it + 40).toByte() }
        val ack = DtnProtocol.CustodyAck(custodyId, acceptorHash, signature)
        val bytes = ack.marshal()

        assertEquals(0x17.toByte(), bytes[0]) // type byte
        assertEquals(DtnProtocol.CustodyAck.SIZE, bytes.size)
        assertEquals(97, bytes.size)

        val parsed = DtnProtocol.CustodyAck.unmarshal(bytes)!!
        assertArrayEquals(custodyId, parsed.custodyId)
        assertArrayEquals(acceptorHash, parsed.acceptorHash)
        assertArrayEquals(signature, parsed.signature)
    }

    @Test
    fun `fragment header wire format round-trip (LE byte order)`() {
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
