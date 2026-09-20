package net.meshsat.android

import net.meshsat.android.rlnc.RlncDecoder
import net.meshsat.android.rlnc.RlncEncoder
import org.junit.Assert.*
import org.junit.Test

class RlncDecoderTest {

    /**
     * Encode, drawing fresh coefficients until the first [k] packets are mutually innovative.
     *
     * `RlncEncoder` fills every coefficient from `GF256Vector.randomCoefficients`, so K of them
     * are linearly dependent roughly once in 256 encodings. A test that feeds exactly K packets
     * and expects the decoder to solve therefore fails at that rate through no fault of the
     * code: it took down the v2.16.1 release pipeline on 20 Sep 2026. Same defect as
     * MESHSAT-1269, in the second of the two RLNC implementations.
     *
     * The Bridge's own tests pre-flight the same way before asserting on rank:
     * internal/hemb/hemb_test.go, "Pre-flight: verify every C(n,k) subset decodes. If any
     * subset is rank-deficient, regenerate the encoding with fresh coefficients."
     */
    private fun encodeInnovative(
        segments: List<ByteArray>,
        generationId: Int,
        codedCount: Int,
        attempts: Int = 10,
    ): List<net.meshsat.android.rlnc.RlncCodedPacket> {
        val k = segments.size
        repeat(attempts) {
            val coded = RlncEncoder.encode(segments, generationId, codedCount)
            val probe = RlncDecoder(k, segments[0].size)
            coded.take(k).forEach { probe.feed(it) }
            if (probe.rank == k) return coded
        }
        throw AssertionError("no full-rank encoding of K=$k in $attempts attempts")
    }

    @Test
    fun `encode and decode with exact K packets`() {
        val data = "Hello RLNC World!".toByteArray()
        val k = 4
        val segments = RlncEncoder.segmentPayload(data, k)
        val coded = encodeInnovative(segments, generationId = 1, codedCount = k)

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
        val coded = encodeInnovative(segments, generationId = 2, codedCount = k + 2)

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
        val coded = encodeInnovative(segments, generationId = 3, codedCount = k + 1)

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
    fun `encoding for a decode assertion survives a dependent draw`() {
        // At K=5 roughly one encoding in 256 cannot be decoded from its first K packets, so a
        // thousand draws meet that case several times over. Without the retry inside
        // encodeInnovative this test is the flake it guards against (MESHSAT-1269).
        val segments = RlncEncoder.segmentPayload(ByteArray(50) { (it * 7 + 3).toByte() }, 5)
        repeat(1000) {
            val coded = encodeInnovative(segments, generationId = 9, codedCount = 7)
            val decoder = RlncDecoder(5, segments[0].size)
            coded.take(5).forEach { decoder.feed(it) }
            assertTrue(decoder.isSolvable)
        }
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
        val parsed = net.meshsat.android.rlnc.RlncCodedPacket.unmarshal(wire)!!
        assertEquals(42, parsed.generationId)
        assertEquals(1, parsed.segmentCount)
        assertArrayEquals(pkt.coefficients, parsed.coefficients)
        assertArrayEquals(pkt.codedData, parsed.codedData)
    }
}
