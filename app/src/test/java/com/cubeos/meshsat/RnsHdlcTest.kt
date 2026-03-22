package com.cubeos.meshsat

import com.cubeos.meshsat.reticulum.RnsTcpInterface
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for HDLC framing used by TCP, Tor, and WireGuard Reticulum interfaces.
 */
class RnsHdlcTest {

    @Test
    fun escape_noSpecialBytes() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val escaped = RnsTcpInterface.hdlcEscape(data)
        assertArrayEquals(data, escaped)
    }

    @Test
    fun escape_flagByte() {
        val data = byteArrayOf(0x7E)
        val escaped = RnsTcpInterface.hdlcEscape(data)
        assertArrayEquals(byteArrayOf(0x7D, 0x5E), escaped)
    }

    @Test
    fun escape_escByte() {
        val data = byteArrayOf(0x7D)
        val escaped = RnsTcpInterface.hdlcEscape(data)
        assertArrayEquals(byteArrayOf(0x7D, 0x5D), escaped)
    }

    @Test
    fun escape_bothSpecialBytes() {
        val data = byteArrayOf(0x7E, 0x7D)
        val escaped = RnsTcpInterface.hdlcEscape(data)
        assertArrayEquals(byteArrayOf(0x7D, 0x5E, 0x7D, 0x5D), escaped)
    }

    @Test
    fun escape_mixedContent() {
        val data = byteArrayOf(0x01, 0x7E, 0x02, 0x7D, 0x03)
        val escaped = RnsTcpInterface.hdlcEscape(data)
        assertArrayEquals(byteArrayOf(0x01, 0x7D, 0x5E, 0x02, 0x7D, 0x5D, 0x03), escaped)
    }

    @Test
    fun unescape_noSpecialBytes() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val unescaped = RnsTcpInterface.hdlcUnescape(data)
        assertArrayEquals(data, unescaped)
    }

    @Test
    fun unescape_flagByte() {
        val data = byteArrayOf(0x7D, 0x5E)
        val unescaped = RnsTcpInterface.hdlcUnescape(data)
        assertArrayEquals(byteArrayOf(0x7E), unescaped)
    }

    @Test
    fun unescape_escByte() {
        val data = byteArrayOf(0x7D, 0x5D)
        val unescaped = RnsTcpInterface.hdlcUnescape(data)
        assertArrayEquals(byteArrayOf(0x7D), unescaped)
    }

    @Test
    fun roundTrip_simplePayload() {
        val original = "Hello Reticulum".toByteArray()
        val escaped = RnsTcpInterface.hdlcEscape(original)
        val restored = RnsTcpInterface.hdlcUnescape(escaped)
        assertArrayEquals(original, restored)
    }

    @Test
    fun roundTrip_allBytesValues() {
        val original = ByteArray(256) { it.toByte() }
        val escaped = RnsTcpInterface.hdlcEscape(original)
        val restored = RnsTcpInterface.hdlcUnescape(escaped)
        assertArrayEquals(original, restored)
    }

    @Test
    fun roundTrip_allFlags() {
        val original = ByteArray(10) { 0x7E }
        val escaped = RnsTcpInterface.hdlcEscape(original)
        val restored = RnsTcpInterface.hdlcUnescape(escaped)
        assertArrayEquals(original, restored)
    }

    @Test
    fun roundTrip_allEscapes() {
        val original = ByteArray(10) { 0x7D }
        val escaped = RnsTcpInterface.hdlcEscape(original)
        val restored = RnsTcpInterface.hdlcUnescape(escaped)
        assertArrayEquals(original, restored)
    }

    @Test
    fun roundTrip_rnsPacketSized() {
        // Simulate a 500-byte RNS MTU packet
        val original = ByteArray(500) { (it % 256).toByte() }
        val escaped = RnsTcpInterface.hdlcEscape(original)
        val restored = RnsTcpInterface.hdlcUnescape(escaped)
        assertArrayEquals(original, restored)
    }

    @Test
    fun escape_emptyInput() {
        val escaped = RnsTcpInterface.hdlcEscape(byteArrayOf())
        assertArrayEquals(byteArrayOf(), escaped)
    }

    @Test
    fun unescape_emptyInput() {
        val unescaped = RnsTcpInterface.hdlcUnescape(byteArrayOf())
        assertArrayEquals(byteArrayOf(), unescaped)
    }

    @Test
    fun escape_expandsSize() {
        // A payload full of special bytes should double in size
        val data = byteArrayOf(0x7E, 0x7D, 0x7E, 0x7D)
        val escaped = RnsTcpInterface.hdlcEscape(data)
        assertEquals(8, escaped.size)
    }

    @Test
    fun constants() {
        assertEquals(0x7E.toByte(), RnsTcpInterface.HDLC_FLAG)
        assertEquals(0x7D.toByte(), RnsTcpInterface.HDLC_ESC)
        assertEquals(0x20, RnsTcpInterface.HDLC_ESC_MASK)
        assertEquals(19, RnsTcpInterface.HEADER_MINSIZE)
    }
}
