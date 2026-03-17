package com.cubeos.meshsat.aprs

import org.junit.Assert.*
import org.junit.Test

class Ax25CodecTest {

    @Test
    fun `encode-decode roundtrip`() {
        val src = Ax25Address("PA3XYZ", 10)
        val dst = Ax25Address("APRS", 0)
        val path = listOf(Ax25Address("WIDE1", 1))
        val info = "!5222.08N/00454.24E-MeshSat".toByteArray()

        val encoded = Ax25Codec.encode(dst, src, path, info)
        val decoded = Ax25Codec.decode(encoded)

        assertNotNull(decoded)
        assertEquals("PA3XYZ", decoded!!.src.call)
        assertEquals(10, decoded.src.ssid)
        assertEquals("APRS", decoded.dst.call)
        assertEquals(0, decoded.dst.ssid)
        assertEquals(1, decoded.path.size)
        assertEquals("WIDE1", decoded.path[0].call)
        assertEquals(1, decoded.path[0].ssid)
        assertEquals("!5222.08N/00454.24E-MeshSat", String(decoded.info))
    }

    @Test
    fun `encode-decode no path`() {
        val src = Ax25Address("TEST", 7)
        val dst = Ax25Address("APMSHT", 0)
        val info = "test data".toByteArray()

        val encoded = Ax25Codec.encode(dst, src, emptyList(), info)
        val decoded = Ax25Codec.decode(encoded)

        assertNotNull(decoded)
        assertEquals("TEST", decoded!!.src.call)
        assertEquals(7, decoded.src.ssid)
        assertTrue(decoded.path.isEmpty())
        assertEquals("test data", String(decoded.info))
    }

    @Test
    fun `encode-decode multi-hop path`() {
        val src = Ax25Address("DL1ABC", 9)
        val dst = Ax25Address("APRS", 0)
        val path = listOf(
            Ax25Address("WIDE1", 1),
            Ax25Address("WIDE2", 1),
        )
        val info = "test".toByteArray()

        val encoded = Ax25Codec.encode(dst, src, path, info)
        val decoded = Ax25Codec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(2, decoded!!.path.size)
        assertEquals("WIDE1", decoded.path[0].call)
        assertEquals("WIDE2", decoded.path[1].call)
    }

    @Test
    fun `decode rejects too-short frame`() {
        assertNull(Ax25Codec.decode(ByteArray(10)))
    }

    @Test
    fun `format callsign with ssid`() {
        assertEquals("PA3XYZ-10", Ax25Address("PA3XYZ", 10).format())
        assertEquals("PA3XYZ", Ax25Address("PA3XYZ", 0).format())
        assertEquals("WIDE1-1", Ax25Address("WIDE1", 1).format())
    }
}
