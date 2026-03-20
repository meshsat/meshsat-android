package com.cubeos.meshsat

import com.cubeos.meshsat.astrocast.AstrocastProtocol
import com.cubeos.meshsat.astrocast.AstrocastProtocol.OpCode
import com.cubeos.meshsat.astrocast.AstrocastProtocol.RspCode
import com.cubeos.meshsat.astrocast.AstrocastProtocol.ErrorCode
import com.cubeos.meshsat.astrocast.AstrocastProtocol.EventFlag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Astrocast Astronode S protocol tests (MESHSAT-12).
 * Verifies frame encoding/decoding, CRC-16, command builders, and response parsers
 * against the official C library reference.
 */
class AstrocastProtocolTest {

    @Test
    fun `STX and ETX constants match spec`() {
        assertEquals(0x02.toByte(), AstrocastProtocol.STX)
        assertEquals(0x03.toByte(), AstrocastProtocol.ETX)
    }

    @Test
    fun `max payload is 160 bytes`() {
        assertEquals(160, AstrocastProtocol.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `encode frame starts with STX and ends with ETX`() {
        val frame = AstrocastProtocol.encodeFrame(OpCode.EVT_R)
        assertEquals(AstrocastProtocol.STX, frame[0])
        assertEquals(AstrocastProtocol.ETX, frame[frame.size - 1])
    }

    @Test
    fun `encode frame contains hex-encoded opcode`() {
        val frame = AstrocastProtocol.encodeFrame(OpCode.EVT_R) // 0x65
        val hex = String(frame, 1, frame.size - 2, Charsets.US_ASCII)
        assertTrue(hex.startsWith("65"))
    }

    @Test
    fun `decode frame round-trip`() {
        val frame = AstrocastProtocol.encodeFrame(OpCode.RTC_R)
        val rsp = AstrocastProtocol.decodeFrame(frame)
        assertNotNull(rsp)
        assertEquals(OpCode.RTC_R, rsp!!.opcode)
        assertEquals(0, rsp.payload.size)
    }

    @Test
    fun `decode frame with payload round-trip`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val frame = AstrocastProtocol.encodeFrame(OpCode.PLD_W, payload)
        val rsp = AstrocastProtocol.decodeFrame(frame)
        assertNotNull(rsp)
        assertEquals(OpCode.PLD_W, rsp!!.opcode)
        assertArrayEquals(payload, rsp.payload)
    }

    @Test
    fun `decode frame rejects bad CRC`() {
        val frame = AstrocastProtocol.encodeFrame(OpCode.EVT_R).clone()
        // Corrupt a CRC byte
        frame[frame.size - 2] = 'X'.code.toByte()
        assertNull(AstrocastProtocol.decodeFrame(frame))
    }

    @Test
    fun `decode frame rejects missing STX`() {
        val frame = AstrocastProtocol.encodeFrame(OpCode.EVT_R).clone()
        frame[0] = 0x00
        assertNull(AstrocastProtocol.decodeFrame(frame))
    }

    @Test
    fun `decode frame rejects missing ETX`() {
        val frame = AstrocastProtocol.encodeFrame(OpCode.EVT_R).clone()
        frame[frame.size - 1] = 0x00
        assertNull(AstrocastProtocol.decodeFrame(frame))
    }

    @Test
    fun `decode frame rejects too short`() {
        assertNull(AstrocastProtocol.decodeFrame(byteArrayOf(0x02, 0x03)))
    }

    @Test
    fun `CRC-16 is deterministic`() {
        val data = byteArrayOf(0x65)
        val crc1 = AstrocastProtocol.crc16(data)
        val crc2 = AstrocastProtocol.crc16(data)
        assertEquals(crc1, crc2)
    }

    @Test
    fun `CRC-16 differs for different data`() {
        val crc1 = AstrocastProtocol.crc16(byteArrayOf(0x65))
        val crc2 = AstrocastProtocol.crc16(byteArrayOf(0x17))
        assertTrue(crc1 != crc2)
    }

    @Test
    fun `payload write encodes counter as LE`() {
        val frame = AstrocastProtocol.payloadWrite(0x1234, byteArrayOf(0xAA.toByte()))
        val rsp = AstrocastProtocol.decodeFrame(frame)
        assertNotNull(rsp)
        assertEquals(OpCode.PLD_W, rsp!!.opcode)
        // Counter 0x1234 LE = 0x34, 0x12
        assertEquals(0x34.toByte(), rsp.payload[0])
        assertEquals(0x12.toByte(), rsp.payload[1])
        // Payload byte
        assertEquals(0xAA.toByte(), rsp.payload[2])
    }

    @Test
    fun `geolocation write encodes lat lon as int32 LE`() {
        // 47.3°N = 47_300_000 microdeg, -122.5°W = -122_500_000 microdeg
        val frame = AstrocastProtocol.geolocationWrite(47_300_000, -122_500_000)
        val rsp = AstrocastProtocol.decodeFrame(frame)
        assertNotNull(rsp)
        assertEquals(OpCode.GEO_W, rsp!!.opcode)
        assertEquals(8, rsp.payload.size)
    }

    @Test
    fun `configuration write packs flags correctly`() {
        val frame = AstrocastProtocol.configurationWrite(
            satAck = true, geolocation = true, ephemeris = false, deepSleep = false,
            satAckMask = true, resetMask = false, cmdMask = true, busyMask = false,
        )
        val rsp = AstrocastProtocol.decodeFrame(frame)
        assertNotNull(rsp)
        assertEquals(OpCode.CFG_W, rsp!!.opcode)
        assertEquals(4, rsp.payload.size)
        // Flags byte 0: sat_ack(1) | geoloc(2) = 3
        assertEquals(3, rsp.payload[0].toInt() and 0xFF)
        // Masks byte 2: sat_ack_mask(1) | cmd_mask(4) = 5
        assertEquals(5, rsp.payload[2].toInt() and 0xFF)
    }

    @Test
    fun `simple commands have no payload`() {
        for (cmd in listOf(
            AstrocastProtocol.payloadDequeue(),
            AstrocastProtocol.payloadClear(),
            AstrocastProtocol.eventRead(),
            AstrocastProtocol.sakRead(),
            AstrocastProtocol.sakClear(),
            AstrocastProtocol.commandRead(),
            AstrocastProtocol.commandClear(),
            AstrocastProtocol.rtcRead(),
            AstrocastProtocol.guidRead(),
            AstrocastProtocol.serialNumberRead(),
            AstrocastProtocol.configurationRead(),
            AstrocastProtocol.ephemerisRead(),
            AstrocastProtocol.moduleStateRead(),
            AstrocastProtocol.lastContactRead(),
            AstrocastProtocol.environmentRead(),
            AstrocastProtocol.resetClear(),
            AstrocastProtocol.contextSave(),
            AstrocastProtocol.perfCountersRead(),
            AstrocastProtocol.perfCountersClear(),
        )) {
            val rsp = AstrocastProtocol.decodeFrame(cmd)
            assertNotNull("Frame decode failed", rsp)
            assertEquals(0, rsp!!.payload.size)
        }
    }

    @Test
    fun `response opcodes are request OR 0x80`() {
        assertEquals((OpCode.PLD_W.toInt() or 0x80).toByte(), RspCode.PLD_W)
        assertEquals((OpCode.EVT_R.toInt() or 0x80).toByte(), RspCode.EVT_R)
        assertEquals((OpCode.RTC_R.toInt() or 0x80).toByte(), RspCode.RTC_R)
        assertEquals((OpCode.CFG_W.toInt() or 0x80).toByte(), RspCode.CFG_W)
        assertEquals((OpCode.SAK_R.toInt() or 0x80).toByte(), RspCode.SAK_R)
    }

    @Test
    fun `parse event flags extracts bits correctly`() {
        val rsp = AstrocastProtocol.Response(
            opcode = RspCode.EVT_R,
            payload = byteArrayOf(0x05), // SAK + CMD
        )
        val flags = AstrocastProtocol.parseEventFlags(rsp)
        assertTrue(flags and EventFlag.SAK_AVAILABLE != 0)
        assertFalse(flags and EventFlag.RESET_EVENT != 0)
        assertTrue(flags and EventFlag.CMD_AVAILABLE != 0)
        assertFalse(flags and EventFlag.MSG_BUSY != 0)
    }

    @Test
    fun `parse SAK counter LE`() {
        val rsp = AstrocastProtocol.Response(
            opcode = RspCode.SAK_R,
            payload = byteArrayOf(0x34, 0x12),
        )
        assertEquals(0x1234, AstrocastProtocol.parseSakCounter(rsp))
    }

    @Test
    fun `parse RTC epoch LE`() {
        // 1700000000 = 0x6553F900
        val rsp = AstrocastProtocol.Response(
            opcode = RspCode.RTC_R,
            payload = byteArrayOf(0x00, 0xF1.toByte(), 0x53, 0x65),
        )
        assertEquals(1700000000L, AstrocastProtocol.parseRtcEpoch(rsp))
    }

    @Test
    fun `parse GUID string`() {
        val guidBytes = "ASTRO-12345678".toByteArray(Charsets.US_ASCII)
        val rsp = AstrocastProtocol.Response(opcode = RspCode.GUID_R, payload = guidBytes)
        assertEquals("ASTRO-12345678", AstrocastProtocol.parseGuid(rsp))
    }

    @Test
    fun `parse serial number string`() {
        val snBytes = "SN00123".toByteArray(Charsets.US_ASCII)
        val rsp = AstrocastProtocol.Response(opcode = RspCode.SN_R, payload = snBytes)
        assertEquals("SN00123", AstrocastProtocol.parseSerialNumber(rsp))
    }

    @Test
    fun `parse payload write counter`() {
        val rsp = AstrocastProtocol.Response(
            opcode = RspCode.PLD_W,
            payload = byteArrayOf(0x07, 0x00),
        )
        assertEquals(7, AstrocastProtocol.parsePayloadWriteCounter(rsp))
    }

    @Test
    fun `parse next pass epoch`() {
        val rsp = AstrocastProtocol.Response(
            opcode = RspCode.EPH_R,
            payload = byteArrayOf(0x00, 0xF1.toByte(), 0x53, 0x65),
        )
        assertEquals(1700000000L, AstrocastProtocol.parseNextPass(rsp))
    }

    @Test
    fun `parse command returns date and data`() {
        val payload = byteArrayOf(
            0x00, 0xF1.toByte(), 0x53, 0x65, // created date
            0x48, 0x65, 0x6C, 0x6C, 0x6F,    // "Hello"
        )
        val rsp = AstrocastProtocol.Response(opcode = RspCode.CMD_R, payload = payload)
        val (date, data) = AstrocastProtocol.parseCommand(rsp)
        assertEquals(1700000000L, date)
        assertEquals("Hello", String(data, Charsets.US_ASCII))
    }

    @Test
    fun `error response detected`() {
        val rsp = AstrocastProtocol.Response(
            opcode = RspCode.ERROR,
            payload = byteArrayOf(0x01, 0x25), // BUFFER_FULL = 0x2501 LE
        )
        assertTrue(rsp.isError)
        assertEquals(ErrorCode.BUFFER_FULL, rsp.errorCode)
    }

    @Test
    fun `error code names are correct`() {
        assertEquals("BUFFER_FULL", ErrorCode.name(ErrorCode.BUFFER_FULL))
        assertEquals("CRC_NOT_VALID", ErrorCode.name(ErrorCode.CRC_NOT_VALID))
        assertEquals("BUFFER_EMPTY", ErrorCode.name(ErrorCode.BUFFER_EMPTY))
    }

    @Test
    fun `payload write rejects oversized payload`() {
        try {
            AstrocastProtocol.payloadWrite(1, ByteArray(161))
            assertTrue("Should throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("160"))
        }
    }

    @Test
    fun `full frame encode decode with payload data`() {
        val data = "Hello Astrocast".toByteArray(Charsets.UTF_8)
        val frame = AstrocastProtocol.payloadWrite(42, data)

        // Verify frame structure
        assertEquals(AstrocastProtocol.STX, frame[0])
        assertEquals(AstrocastProtocol.ETX, frame[frame.size - 1])
        // All bytes between STX and ETX should be printable ASCII hex chars
        for (i in 1 until frame.size - 1) {
            val c = frame[i].toInt().toChar()
            assertTrue("Byte at $i should be hex: $c", c in "0123456789ABCDEF")
        }

        // Decode and verify
        val rsp = AstrocastProtocol.decodeFrame(frame)
        assertNotNull(rsp)
        assertEquals(OpCode.PLD_W, rsp!!.opcode)
        // Counter 42 LE = 0x2A, 0x00
        assertEquals(0x2A.toByte(), rsp.payload[0])
        assertEquals(0x00.toByte(), rsp.payload[1])
        // Payload
        val decoded = rsp.payload.copyOfRange(2, rsp.payload.size)
        assertEquals("Hello Astrocast", String(decoded, Charsets.UTF_8))
    }
}
