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

    // --- Fragmentation tests (MESHSAT-234) ---

    @Test
    fun `fragment header encode decode round-trip`() {
        for (msgID in 0..15) {
            for (fragNum in 0..3) {
                for (fragTotal in 1..4) {
                    val b = AstrocastProtocol.encodeFragmentHeader(msgID, fragNum, fragTotal)
                    val h = AstrocastProtocol.decodeFragmentHeader(b)
                    assertEquals("msgID", msgID, h.msgID)
                    assertEquals("fragNum", fragNum, h.fragNum)
                    assertEquals("fragTotal", fragTotal, h.fragTotal)
                }
            }
        }
    }

    @Test
    fun `fragment header matches Go bridge encoding`() {
        // Go: EncodeFragmentHeader(5, 2, 3) = (5 << 4) | (2 << 2) | (3-1) = 0x5A
        val b = AstrocastProtocol.encodeFragmentHeader(5, 2, 3)
        assertEquals(0x5A.toByte(), b)
    }

    @Test
    fun `fragmentMessage returns null for small messages`() {
        val data = ByteArray(160) // exactly 160 — fits in single uplink
        assertNull(AstrocastProtocol.fragmentMessage(0, data))
    }

    @Test
    fun `fragmentMessage splits 320 bytes into 3 fragments`() {
        val data = ByteArray(320) { (it % 256).toByte() }
        val frags = AstrocastProtocol.fragmentMessage(7, data)
        assertNotNull(frags)
        assertEquals(3, frags!!.size)

        // Each fragment starts with a header byte
        for ((i, frag) in frags.withIndex()) {
            val h = AstrocastProtocol.decodeFragmentHeader(frag[0])
            assertEquals(7, h.msgID)
            assertEquals(i, h.fragNum)
            assertEquals(3, h.fragTotal)
        }

        // First two fragments: 1 header + 159 payload = 160 bytes
        assertEquals(160, frags[0].size)
        assertEquals(160, frags[1].size)
        // Last fragment: 1 header + (320 - 318) = 3 bytes
        assertEquals(3, frags[2].size)
    }

    @Test
    fun `fragmentMessage caps at 4 fragments`() {
        val data = ByteArray(700) // would need 5 frags, but max is 4
        val frags = AstrocastProtocol.fragmentMessage(0, data)
        assertNotNull(frags)
        assertEquals(4, frags!!.size)

        // Total payload = 4 * 159 = 636 bytes (truncated from 700)
        val totalPayload = frags.sumOf { it.size - 1 }
        assertEquals(636, totalPayload)
    }

    @Test
    fun `reassembly buffer collects and reassembles`() {
        val buf = AstrocastProtocol.ReassemblyBuffer()
        val now = 1000L

        // Fragment 0 of 3
        val h0 = AstrocastProtocol.FragmentHeader(msgID = 5, fragNum = 0, fragTotal = 3)
        val r0 = buf.addFragment(h0, byteArrayOf(0x01, 0x02), now)
        assertNull(r0)

        // Fragment 2 of 3 (out of order)
        val h2 = AstrocastProtocol.FragmentHeader(msgID = 5, fragNum = 2, fragTotal = 3)
        val r2 = buf.addFragment(h2, byteArrayOf(0x05, 0x06), now)
        assertNull(r2)

        // Fragment 1 of 3 (completes the message)
        val h1 = AstrocastProtocol.FragmentHeader(msgID = 5, fragNum = 1, fragTotal = 3)
        val r1 = buf.addFragment(h1, byteArrayOf(0x03, 0x04), now)
        assertNotNull(r1)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06), r1)
        assertEquals(0, buf.pendingCount)
    }

    @Test
    fun `reassembly buffer expires old entries`() {
        val buf = AstrocastProtocol.ReassemblyBuffer(maxAgeSec = 60)
        val h = AstrocastProtocol.FragmentHeader(msgID = 1, fragNum = 0, fragTotal = 2)
        buf.addFragment(h, byteArrayOf(0x01), 100L)
        assertEquals(1, buf.pendingCount)

        buf.expire(200L) // 100 seconds later > 60 maxAge
        assertEquals(0, buf.pendingCount)
    }

    @Test
    fun `fragment and reassemble round-trip`() {
        val original = ByteArray(400) { (it % 256).toByte() }
        val frags = AstrocastProtocol.fragmentMessage(3, original)
        assertNotNull(frags)

        val buf = AstrocastProtocol.ReassemblyBuffer()
        var result: ByteArray? = null
        for (frag in frags!!) {
            val h = AstrocastProtocol.decodeFragmentHeader(frag[0])
            val payload = frag.copyOfRange(1, frag.size)
            result = buf.addFragment(h, payload, 1000L)
        }
        assertNotNull(result)
        assertArrayEquals(original, result)
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
    fun `CRC-16 matches Go bridge CRC16CCITT for known vectors`() {
        // Cross-implementation test vectors — these values were computed by the Go bridge's
        // CRC16CCITT function (bit-by-bit CRC-16/CCITT-FALSE, poly 0x1021, init 0xFFFF).
        // Android must produce identical raw CRC values.

        // Single opcode byte: EVT_R (0x65)
        // Go: CRC16CCITT([]byte{0x65}) = 0xDDF3
        assertEquals(0xDDF3, AstrocastProtocol.crc16(byteArrayOf(0x65)))

        // Single opcode byte: RTC_R (0x17)
        // Go: CRC16CCITT([]byte{0x17}) = 0x8326
        assertEquals(0x8326, AstrocastProtocol.crc16(byteArrayOf(0x17)))

        // Opcode + payload: PLD_W (0x25) with counter [0x2A, 0x00] + "Hello"
        // Go: CRC16CCITT([]byte{0x25, 0x2A, 0x00, 0x48, 0x65, 0x6C, 0x6C, 0x6F}) = 0x5486
        val pldData = byteArrayOf(0x25, 0x2A, 0x00, 0x48, 0x65, 0x6C, 0x6C, 0x6F)
        assertEquals(0x5486, AstrocastProtocol.crc16(pldData))
    }

    @Test
    fun `wire CRC byte order matches Go bridge little-endian convention`() {
        // Go bridge encodes CRC as [lo, hi] on the wire.
        // For EVT_R (0x65), CRC = 0xDDF3 → wire LE: [0xF3, 0xDD] → hex "F3DD"
        val frame = AstrocastProtocol.encodeFrame(OpCode.EVT_R) // 0x65
        val hex = String(frame, 1, frame.size - 2, Charsets.US_ASCII)
        // hex = "65" + CRC4 → last 4 chars are the CRC hex
        val crcHex = hex.substring(hex.length - 4)
        assertEquals("F3DD", crcHex)
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
