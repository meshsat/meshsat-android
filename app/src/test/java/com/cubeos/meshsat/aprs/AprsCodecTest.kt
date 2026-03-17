package com.cubeos.meshsat.aprs

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AprsCodecTest {

    @Test
    fun `parse position without timestamp`() {
        val info = "!5222.06N/00454.25E-Test station".toByteArray()
        val frame = Ax25Frame(
            src = Ax25Address("PA3XYZ", 10),
            dst = Ax25Address("APRS", 0),
            info = info,
        )

        val pkt = AprsCodec.parse(frame)
        assertEquals('!', pkt.dataType)
        assertEquals("PA3XYZ-10", pkt.source)

        // 52°22.06'N = 52 + 22.06/60 ≈ 52.3677
        assertTrue("lat should be ~52.367: ${pkt.lat}", abs(pkt.lat - (52.0 + 22.06 / 60.0)) < 0.001)
        // 004°54.25'E = 4 + 54.25/60 ≈ 4.9042
        assertTrue("lon should be ~4.904: ${pkt.lon}", abs(pkt.lon - (4.0 + 54.25 / 60.0)) < 0.001)
        assertEquals("Test station", pkt.comment)
    }

    @Test
    fun `parse position south west`() {
        val info = "!3436.22S/05822.90W-Buenos Aires".toByteArray()
        val frame = Ax25Frame(
            src = Ax25Address("LU1ABC", 0),
            dst = Ax25Address("APRS", 0),
            info = info,
        )

        val pkt = AprsCodec.parse(frame)
        assertTrue("lat should be negative: ${pkt.lat}", pkt.lat < 0)
        assertTrue("lon should be negative: ${pkt.lon}", pkt.lon < 0)
    }

    @Test
    fun `parse message with id`() {
        val info = ":PA3ABC   :Hello from MeshSat{42".toByteArray()
        val frame = Ax25Frame(
            src = Ax25Address("PA3XYZ", 10),
            dst = Ax25Address("APRS", 0),
            info = info,
        )

        val pkt = AprsCodec.parse(frame)
        assertEquals(':', pkt.dataType)
        assertEquals("PA3ABC", pkt.msgTo)
        assertEquals("Hello from MeshSat", pkt.message)
        assertEquals("42", pkt.msgId)
    }

    @Test
    fun `parse message without id`() {
        val info = ":PA3ABC   :Simple message".toByteArray()
        val frame = Ax25Frame(
            src = Ax25Address("TEST", 0),
            dst = Ax25Address("APRS", 0),
            info = info,
        )

        val pkt = AprsCodec.parse(frame)
        assertEquals("PA3ABC", pkt.msgTo)
        assertEquals("Simple message", pkt.message)
        assertEquals("", pkt.msgId)
    }

    @Test
    fun `encode position north east`() {
        val pos = AprsCodec.encodePosition(52.3676, 4.9041, comment = "MeshSat Bridge")
        val s = String(pos)

        assertEquals('!', s[0])
        assertTrue("should contain N: $s", s.contains("N"))
        assertTrue("should contain E: $s", s.contains("E"))
        assertTrue("should contain comment: $s", s.contains("MeshSat Bridge"))
    }

    @Test
    fun `encode position south west`() {
        val pos = AprsCodec.encodePosition(-34.6037, -58.3816, comment = "test")
        val s = String(pos)

        assertTrue("should contain S: $s", s.contains("S"))
        assertTrue("should contain W: $s", s.contains("W"))
    }

    @Test
    fun `encode message with id`() {
        val msg = AprsCodec.encodeMessage("PA3ABC", "Hello", "123")
        val s = String(msg)

        assertTrue("should start with :PA3ABC: $s", s.startsWith(":PA3ABC"))
        assertTrue("should contain message: $s", s.contains("Hello"))
        assertTrue("should contain id: $s", s.contains("{123"))
    }

    @Test
    fun `encode message without id`() {
        val msg = AprsCodec.encodeMessage("PA3ABC", "Hello")
        val s = String(msg)

        assertTrue("should contain message: $s", s.contains("Hello"))
        assertFalse("should not contain brace: $s", s.contains("{"))
    }

    @Test
    fun `encode-decode position roundtrip`() {
        // Encode a position
        val encoded = AprsCodec.encodePosition(52.3676, 4.9041, '/', '-', "roundtrip test")

        // Wrap in AX.25 frame and parse
        val frame = Ax25Frame(
            src = Ax25Address("TEST", 10),
            dst = Ax25Address("APRS", 0),
            info = encoded,
        )
        val pkt = AprsCodec.parse(frame)

        // Verify coordinates are preserved within APRS precision (~18m)
        assertTrue("lat roundtrip: ${pkt.lat}", abs(pkt.lat - 52.3676) < 0.001)
        assertTrue("lon roundtrip: ${pkt.lon}", abs(pkt.lon - 4.9041) < 0.001)
        assertTrue("comment: ${pkt.comment}", pkt.comment.contains("roundtrip test"))
    }

    @Test
    fun `full KISS-AX25-APRS encode-decode chain`() {
        // 1. Create APRS position info
        val info = AprsCodec.encodePosition(48.8566, 2.3522, '/', '-', "Paris")

        // 2. Wrap in AX.25 frame
        val ax25 = Ax25Codec.encode(
            Ax25Address("APRS", 0),
            Ax25Address("F4TEST", 7),
            listOf(Ax25Address("WIDE1", 1)),
            info,
        )

        // 3. Wrap in KISS frame
        val kissFrame = KissCodec.encode(ax25)

        // 4. Decode KISS
        val inner = kissFrame.copyOfRange(1, kissFrame.size - 1)
        val kissDecoded = KissCodec.decode(inner)
        assertNotNull(kissDecoded)

        // 5. Decode AX.25
        val ax25Decoded = Ax25Codec.decode(kissDecoded!!)
        assertNotNull(ax25Decoded)
        assertEquals("F4TEST", ax25Decoded!!.src.call)
        assertEquals(7, ax25Decoded.src.ssid)

        // 6. Decode APRS
        val pkt = AprsCodec.parse(ax25Decoded)
        assertTrue("lat: ${pkt.lat}", abs(pkt.lat - 48.8566) < 0.001)
        assertTrue("lon: ${pkt.lon}", abs(pkt.lon - 2.3522) < 0.001)
        assertTrue("comment: ${pkt.comment}", pkt.comment.contains("Paris"))
    }
}
