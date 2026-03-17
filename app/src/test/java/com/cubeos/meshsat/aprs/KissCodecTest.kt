package com.cubeos.meshsat.aprs

import org.junit.Assert.*
import org.junit.Test

class KissCodecTest {

    @Test
    fun `encode wraps with FEND and command byte`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frame = KissCodec.encode(payload)

        assertEquals(KissCodec.FEND, frame.first())
        assertEquals(KissCodec.FEND, frame.last())
        assertEquals(KissCodec.DATA, frame[1])
    }

    @Test
    fun `encode escapes FEND in payload`() {
        val payload = byteArrayOf(0x01, KissCodec.FEND, 0x02)
        val frame = KissCodec.encode(payload)

        // Should be: FEND, 0x00, 0x01, FESC, TFEND, 0x02, FEND
        assertEquals(7, frame.size)
        assertEquals(KissCodec.FESC, frame[3])
        assertEquals(KissCodec.TFEND, frame[4])
    }

    @Test
    fun `encode escapes FESC in payload`() {
        val payload = byteArrayOf(0x01, KissCodec.FESC, 0x02)
        val frame = KissCodec.encode(payload)

        assertEquals(7, frame.size)
        assertEquals(KissCodec.FESC, frame[3])
        assertEquals(KissCodec.TFESC, frame[4])
    }

    @Test
    fun `decode simple frame`() {
        val frame = byteArrayOf(KissCodec.DATA, 0x01, 0x02, 0x03)
        val decoded = KissCodec.decode(frame)

        assertNotNull(decoded)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), decoded)
    }

    @Test
    fun `decode unescapes FEND`() {
        val frame = byteArrayOf(KissCodec.DATA, 0x01, KissCodec.FESC, KissCodec.TFEND, 0x02)
        val decoded = KissCodec.decode(frame)

        assertNotNull(decoded)
        assertArrayEquals(byteArrayOf(0x01, KissCodec.FEND, 0x02), decoded)
    }

    @Test
    fun `decode unescapes FESC`() {
        val frame = byteArrayOf(KissCodec.DATA, 0x01, KissCodec.FESC, KissCodec.TFESC, 0x02)
        val decoded = KissCodec.decode(frame)

        assertNotNull(decoded)
        assertArrayEquals(byteArrayOf(0x01, KissCodec.FESC, 0x02), decoded)
    }

    @Test
    fun `encode-decode roundtrip`() {
        val original = byteArrayOf(0x00, KissCodec.FEND, KissCodec.FESC, 0xFF.toByte(), 0x42)
        val encoded = KissCodec.encode(original)

        // Strip outer FENDs
        val inner = encoded.copyOfRange(1, encoded.size - 1)
        val decoded = KissCodec.decode(inner)

        assertNotNull(decoded)
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `decode rejects too-short frame`() {
        assertNull(KissCodec.decode(byteArrayOf(0x00)))
    }

    @Test
    fun `decode rejects non-data frame`() {
        assertNull(KissCodec.decode(byteArrayOf(0x01, 0x02, 0x03))) // cmd=0x01, not 0x00
    }

    @Test
    fun `decode rejects trailing escape`() {
        val frame = byteArrayOf(KissCodec.DATA, 0x01, KissCodec.FESC) // escape with nothing after
        assertNull(KissCodec.decode(frame))
    }

    @Test
    fun `decode rejects invalid escape sequence`() {
        val frame = byteArrayOf(KissCodec.DATA, 0x01, KissCodec.FESC, 0x42) // FESC followed by invalid byte
        assertNull(KissCodec.decode(frame))
    }
}
