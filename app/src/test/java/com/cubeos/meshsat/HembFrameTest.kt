package com.cubeos.meshsat

import com.cubeos.meshsat.hemb.HembCodedSymbol
import com.cubeos.meshsat.hemb.HembFrame
import org.junit.Assert.*
import org.junit.Test

class HembFrameTest {

    @Test
    fun `crc8 deterministic`() {
        val data = byteArrayOf(0x48, 0x4D, 0x00, 0x01, 0x02, 0x03, 0x04)
        val crc1 = HembFrame.crc8(data)
        val crc2 = HembFrame.crc8(data)
        assertEquals(crc1, crc2)
    }

    @Test
    fun `crc8 detects bit flip`() {
        val data = byteArrayOf(0x48, 0x4D, 0x00, 0x01, 0x02, 0x03, 0x04)
        val crc = HembFrame.crc8(data)
        data[3] = (data[3].toInt() xor 0x01).toByte()
        assertNotEquals(crc, HembFrame.crc8(data))
    }

    @Test
    fun `marshalExtended produces valid frame`() {
        val sym = HembCodedSymbol(
            genId = 42,
            symbolIndex = 0,
            k = 2,
            coefficients = byteArrayOf(0x01, 0x02),
            data = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )
        val frame = HembFrame.marshalExtended(
            streamId = 1,
            sym = sym,
            bearerIndex = 0,
            totalN = 3,
        )

        assertTrue("frame starts with magic", frame[0] == HembFrame.MAGIC_0 && frame[1] == HembFrame.MAGIC_1)
        assertTrue("isHembFrame detects it", HembFrame.isHembFrame(frame))
        assertEquals(HembFrame.EXTENDED_HEADER_LEN + 2 + 2, frame.size)
    }

    @Test
    fun `parseSymbol round-trips extended frame`() {
        val sym = HembCodedSymbol(
            genId = 7,
            symbolIndex = 0,
            k = 3,
            coefficients = byteArrayOf(0x11, 0x22, 0x33),
            data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
        )
        val frame = HembFrame.marshalExtended(
            streamId = 5,
            sym = sym,
            bearerIndex = 2,
            totalN = 4,
        )
        val parsed = HembFrame.parseSymbol(frame)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.bearerIndex)
        assertEquals(3, parsed.symbol.k)
        assertEquals(7, parsed.symbol.genId)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), parsed.symbol.coefficients)
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), parsed.symbol.data)
    }

    @Test
    fun `isHembFrame rejects garbage`() {
        assertFalse(HembFrame.isHembFrame(byteArrayOf(0x00, 0x01, 0x02)))
        assertFalse(HembFrame.isHembFrame(byteArrayOf()))
    }

    @Test
    fun `isHembFrame detects valid extended`() {
        val frame = ByteArray(HembFrame.EXTENDED_HEADER_LEN)
        frame[0] = HembFrame.MAGIC_0
        frame[1] = HembFrame.MAGIC_1
        frame[6] = 1 // K=1
        frame[7] = 1 // N=1
        frame[15] = HembFrame.crc8(frame, 0, 15)
        assertTrue(HembFrame.isHembFrame(frame))
    }
}
