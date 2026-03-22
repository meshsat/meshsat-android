package com.cubeos.meshsat

import com.cubeos.meshsat.bt.Iridium9704Spp
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Iridium 9704 JSPR protocol — CRC-16, spacedJson, constants.
 *
 * Note: parseJsprLine tests require org.json.JSONObject which is stubbed
 * in JVM unit tests (isReturnDefaultValues=true). Parse tests should be
 * run as instrumented tests on a device.
 */
class Iridium9704Test {

    // ═══════════════════════════════════════════════════════════════
    // CRC-16 (polynomial 0x1021, init 0x0000 = CRC-16/XMODEM)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun crc16_emptyData() {
        assertEquals(0, Iridium9704Spp.crc16(byteArrayOf()))
    }

    @Test
    fun crc16_knownVector_123456789() {
        // CRC-16/XMODEM (poly=0x1021, init=0x0000) for "123456789" = 0x31C3
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x31C3, Iridium9704Spp.crc16(data))
    }

    @Test
    fun crc16_deterministic() {
        val data = "Hello world!".toByteArray(Charsets.US_ASCII)
        val crc1 = Iridium9704Spp.crc16(data)
        val crc2 = Iridium9704Spp.crc16(data)
        assertEquals(crc1, crc2)
        assertTrue(crc1 in 0..0xFFFF)
    }

    @Test
    fun crc16_differentInputsDifferentCrcs() {
        val crc1 = Iridium9704Spp.crc16("abc".toByteArray())
        val crc2 = Iridium9704Spp.crc16("abd".toByteArray())
        assertNotEquals(crc1, crc2)
    }

    @Test
    fun crc16_allZeros() {
        val data = ByteArray(10) { 0 }
        val crc = Iridium9704Spp.crc16(data)
        assertTrue(crc in 0..0xFFFF)
        // CRC of all-zeros is 0x0000 for this algorithm
        assertEquals(0, crc)
    }

    @Test
    fun crc16_singleByte() {
        val crc = Iridium9704Spp.crc16(byteArrayOf(0x31))
        assertTrue(crc in 1..0xFFFF)
    }

    @Test
    fun crc16_appendAndExtract() {
        val data = "test payload".toByteArray(Charsets.US_ASCII)
        val crc = Iridium9704Spp.crc16(data)

        // Build payload + CRC (big-endian, as per JSPR spec)
        val withCrc = ByteArray(data.size + 2)
        data.copyInto(withCrc)
        withCrc[data.size] = (crc shr 8).toByte()
        withCrc[data.size + 1] = (crc and 0xFF).toByte()

        // Extract and verify
        val extractedCrc = ((withCrc[data.size].toInt() and 0xFF) shl 8) or
                (withCrc[data.size + 1].toInt() and 0xFF)
        assertEquals(crc, extractedCrc)
    }

    @Test
    fun crc16_roundTrip_variousPayloads() {
        val payloads = listOf(
            "SOS".toByteArray(),
            "Hello from the field".toByteArray(),
            ByteArray(340) { it.toByte() },
            ByteArray(1) { 0xFF.toByte() },
            ByteArray(1446) { (it % 256).toByte() }, // Max JSPR segment
        )

        for (data in payloads) {
            val crc = Iridium9704Spp.crc16(data)
            assertTrue("CRC out of range", crc in 0..0xFFFF)

            val withCrc = ByteArray(data.size + 2)
            data.copyInto(withCrc)
            withCrc[data.size] = (crc shr 8).toByte()
            withCrc[data.size + 1] = (crc and 0xFF).toByte()

            val extractedPayload = withCrc.copyOfRange(0, data.size)
            val extractedCrc = ((withCrc[data.size].toInt() and 0xFF) shl 8) or
                    (withCrc[data.size + 1].toInt() and 0xFF)

            assertArrayEquals(data, extractedPayload)
            assertEquals(crc, extractedCrc)
            assertEquals(crc, Iridium9704Spp.crc16(extractedPayload))
        }
    }

    @Test
    fun crc16_largePayload() {
        // 100KB payload (IMT max size)
        val data = ByteArray(100_000) { (it % 256).toByte() }
        val crc = Iridium9704Spp.crc16(data)
        assertTrue(crc in 0..0xFFFF)
        // Should be deterministic
        assertEquals(crc, Iridium9704Spp.crc16(data))
    }

    // ═══════════════════════════════════════════════════════════════
    // spacedJson — modem requires spaces after : and , in JSON
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun spacedJson_emptyObject() {
        assertEquals("{}", Iridium9704Spp.spacedJson("{}"))
    }

    @Test
    fun spacedJson_addsSpaces() {
        val input = """{"key":"value","num":42}"""
        val result = Iridium9704Spp.spacedJson(input)
        assertEquals("""{"key": "value", "num": 42}""", result)
    }

    @Test
    fun spacedJson_preservesStringContent() {
        val input = """{"url":"http://host:8080","list":"a,b,c"}"""
        val result = Iridium9704Spp.spacedJson(input)
        assertEquals("""{"url": "http://host:8080", "list": "a,b,c"}""", result)
    }

    @Test
    fun spacedJson_nested() {
        val input = """{"a":{"b":1}}"""
        val result = Iridium9704Spp.spacedJson(input)
        assertEquals("""{"a": {"b": 1}}""", result)
    }

    @Test
    fun spacedJson_alreadySpaced() {
        val input = """{"key": "value", "num": 42}"""
        val result = Iridium9704Spp.spacedJson(input)
        // Adds extra spaces but that's OK — modem is tolerant of extra whitespace
        assertTrue(result.contains("\"key\""))
        assertTrue(result.contains("\"value\""))
    }

    // ═══════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun imtMaxSize() {
        assertEquals(100_000, Iridium9704Spp.IMT_MAX_SIZE)
    }
}
