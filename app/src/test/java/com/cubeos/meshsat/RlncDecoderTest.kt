package com.cubeos.meshsat

import com.cubeos.meshsat.rlnc.RlncDecoder
import com.cubeos.meshsat.rlnc.RlncEncoder
import org.junit.Assert.*
import org.junit.Test

class RlncDecoderTest {
    @Test
    fun `encode and decode with exact K packets`() {
        val data = "Hello RLNC World!".toByteArray()
        val k = 4
        val segments = RlncEncoder.segmentPayload(data, k)
        val coded = RlncEncoder.encode(segments, generationId = 1, codedCount = k)

        val decoder = RlncDecoder(k, segments[0].size)
        for (pkt in coded) {
            decoder.feed(pkt)
        }
        assertTrue(decoder.isSolvable)

        val recovered = decoder.solve()!!
        assertEquals(k, recovered.size)

        // Reconstruct original
        val result = ByteArray(data.size)
        var offset = 0
        for (seg in recovered) {
            val len = minOf(seg.size, data.size - offset)
            System.arraycopy(seg, 0, result, offset, len)
            offset += seg.size
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `decode with K+2 packets (any K suffice)`() {
        val data = ByteArray(50) { (it * 7 + 3).toByte() }
        val k = 5
        val segments = RlncEncoder.segmentPayload(data, k)
        val coded = RlncEncoder.encode(segments, generationId = 2, codedCount = k + 2)

        // Feed only the first K packets (skip the extra 2)
        val decoder = RlncDecoder(k, segments[0].size)
        var independent = 0
        for (pkt in coded.take(k)) {
            if (decoder.feed(pkt)) independent++
        }
        assertTrue("Should have K independent packets", decoder.isSolvable)

        val recovered = decoder.solve()!!
        val result = ByteArray(data.size)
        var offset = 0
        for (seg in recovered) {
            val len = minOf(seg.size, data.size - offset)
            System.arraycopy(seg, 0, result, offset, len)
            offset += seg.size
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `progressive decoding - rank increases with each independent packet`() {
        val k = 3
        val segments = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5, 6))
        val coded = RlncEncoder.encode(segments, generationId = 3, codedCount = k + 1)

        val decoder = RlncDecoder(k, 2)
        assertEquals(0, decoder.rank)
        assertFalse(decoder.isSolvable)

        decoder.feed(coded[0])
        assertEquals(1, decoder.rank)

        decoder.feed(coded[1])
        assertEquals(2, decoder.rank)

        decoder.feed(coded[2])
        assertEquals(3, decoder.rank)
        assertTrue(decoder.isSolvable)
    }

    @Test
    fun `duplicate packet does not increase rank`() {
        val segments = listOf(byteArrayOf(10, 20), byteArrayOf(30, 40))
        val coded = RlncEncoder.encode(segments, generationId = 4, codedCount = 3)

        val decoder = RlncDecoder(2, 2)
        assertTrue(decoder.feed(coded[0]))
        assertEquals(1, decoder.rank)

        // Feed same packet again
        assertFalse(decoder.feed(coded[0]))
        assertEquals(1, decoder.rank)
    }

    @Test
    fun `coded packet wire format round-trip`() {
        val segments = listOf(byteArrayOf(1, 2, 3))
        val coded = RlncEncoder.encode(segments, generationId = 42, codedCount = 1)
        val pkt = coded[0]

        val wire = pkt.marshal()
        val parsed = com.cubeos.meshsat.rlnc.RlncCodedPacket.unmarshal(wire)!!
        assertEquals(42, parsed.generationId)
        assertEquals(1, parsed.segmentCount)
        assertArrayEquals(pkt.coefficients, parsed.coefficients)
        assertArrayEquals(pkt.codedData, parsed.codedData)
    }
}
