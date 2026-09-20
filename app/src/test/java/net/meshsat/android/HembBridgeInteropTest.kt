package net.meshsat.android

import net.meshsat.android.hemb.HembFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frames produced by the Bridge, parsed here.
 *
 * Every other HeMB test in this suite round-trips our own marshaller against our own
 * parser, so a field that both sides get wrong in the same way looks correct. These
 * fixtures come from the Bridge's Go code instead: `MarshalExtended` in
 * `meshsat/internal/hemb/frame.go`, the implementation actually in the field.
 *
 * How to regenerate, with the Bridge checked out beside this repo: copy
 * `internal/hemb/frame.go` into a scratch Go module (it only imports errors and fmt),
 * call `MarshalExtended` with the header below, append the coefficients and coded
 * data, and print the bytes.
 *
 * MESHSAT-1163 was found this way: the parser folded byte 2's repeated low nibble
 * into the stream id, so the frame below read as stream 85 instead of 5.
 * MESHSAT-1264 tracks the compact header, which is still on different bits here.
 */
class HembBridgeInteropTest {

    /**
     * Bridge `MarshalExtended` with StreamID 5, Flags data, Sequence 7, K 3, N 5,
     * BearerIndex 2, GenerationID 42, then three coefficients and four bytes of
     * coded data.
     */
    private val bridgeExtendedFrame = byteArrayOf(
        0x48, 0x4D, // magic "HM"
        0x14, // version 0, stream id low nibble, flags 0
        0x05, // stream id, the whole byte
        0x07, 0x00, // sequence, little endian
        0x03, // K
        0x05, // N
        0x02, // bearer index
        0x2A, 0x00, // generation id, little endian
        0x00, 0x00, // total payload size
        0x00, // TTL
        0x00, // extended flags
        0x83.toByte(), // CRC-8 over bytes 0..14
        0x01, 0x02, 0x03, // coefficients, K of them
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), // coded data
    )

    @Test
    fun `a Bridge extended frame is recognised`() {
        assertTrue(HembFrame.isHembFrame(bridgeExtendedFrame))
    }

    @Test
    fun `a Bridge extended frame parses field for field`() {
        val parsed = HembFrame.parseSymbol(bridgeExtendedFrame)
        assertNotNull(parsed)
        parsed!!

        assertEquals(5, parsed.streamId) // read 85 before MESHSAT-1163
        assertEquals(2, parsed.bearerIndex)
        assertEquals(5, parsed.n)
        assertEquals(HembFrame.FLAG_DATA, parsed.flags)
        assertEquals(HembFrame.HEADER_MODE_EXTENDED, parsed.headerMode)

        assertEquals(42, parsed.symbol.genId)
        assertEquals(7, parsed.symbol.symbolIndex)
        assertEquals(3, parsed.symbol.k)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), parsed.symbol.coefficients)
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            parsed.symbol.data,
        )
    }

    @Test
    fun `our extended header goes back out on the same bytes the Bridge uses`() {
        val parsed = HembFrame.parseSymbol(bridgeExtendedFrame)!!
        val ours = HembFrame.marshalExtended(
            streamId = parsed.streamId,
            sym = parsed.symbol,
            bearerIndex = parsed.bearerIndex,
            totalN = parsed.n,
        )
        // Header only: the Bridge fixture carries no payload size, and our marshaller
        // writes the same zeros, so all 16 header bytes must match byte for byte.
        assertArrayEquals(
            bridgeExtendedFrame.copyOfRange(0, 16),
            ours.copyOfRange(0, 16),
        )
    }
}
