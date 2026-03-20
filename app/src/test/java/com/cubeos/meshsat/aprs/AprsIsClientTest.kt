package com.cubeos.meshsat.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APRS-IS TNC-2 line parser tests (MESHSAT-230).
 * Verifies parsing of APRS-IS text protocol packets.
 */
class AprsIsClientTest {

    @Test
    fun `parse position without timestamp`() {
        val line = "PA3XYZ-10>APMSHT,WIDE1-1:!5222.06N/00454.25E-MeshSat Gateway"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertEquals("PA3XYZ-10", pkt!!.source)
        assertEquals("APMSHT", pkt.dest)
        assertEquals("WIDE1-1", pkt.path)
        assertEquals('!', pkt.dataType)
        assertEquals(52.3676, pkt.lat, 0.001)
        assertEquals(4.9041, pkt.lon, 0.001)
        assertTrue(pkt.comment.contains("MeshSat"))
    }

    @Test
    fun `parse position with timestamp`() {
        val line = "N0CALL>APRS,TCPIP*:/092345z4903.50N/07201.75W-PHG2360"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertEquals("N0CALL", pkt!!.source)
        assertEquals('/', pkt.dataType)
        assertEquals(49.0583, pkt.lat, 0.01)
        assertEquals(-72.0291, pkt.lon, 0.01)
    }

    @Test
    fun `parse message with id`() {
        val line = "PA3ABC-5>APRS,TCPIP*::PA3XYZ-10:Hello from APRS-IS{42"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertEquals("PA3ABC-5", pkt!!.source)
        assertEquals(':', pkt.dataType)
        assertEquals("PA3XYZ-10", pkt.msgTo)
        assertEquals("Hello from APRS-IS", pkt.message)
        assertEquals("42", pkt.msgId)
    }

    @Test
    fun `parse message without id`() {
        val line = "W3ADO-1>APRS::PA3XYZ   :Weather alert"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertEquals("PA3XYZ", pkt!!.msgTo)
        assertEquals("Weather alert", pkt.message)
        assertEquals("", pkt.msgId)
    }

    @Test
    fun `parse ack message`() {
        val line = "PA3XYZ-10>APRS,TCPIP*::PA3ABC-5 :ack42"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertEquals("PA3ABC-5", pkt!!.msgTo)
        assertEquals("ack42", pkt.message)
    }

    @Test
    fun `parse south west position`() {
        val line = "VK2ABC>APRS:!3352.00S/15112.00W-Test"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertTrue(pkt!!.lat < 0)
        assertTrue(pkt.lon < 0)
    }

    @Test
    fun `parse multi-hop path`() {
        val line = "N0CALL>APRS,WIDE1-1,WIDE2-1,qAR,RELAY:!0000.00N/00000.00E-test"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertEquals("WIDE1-1,WIDE2-1,qAR,RELAY", pkt!!.path)
    }

    @Test
    fun `reject malformed line - no gt`() {
        assertNull(AprsIsClient.parseTnc2Line("malformed line"))
    }

    @Test
    fun `reject malformed line - no colon`() {
        assertNull(AprsIsClient.parseTnc2Line("SRC>DST"))
    }

    @Test
    fun `reject empty source`() {
        assertNull(AprsIsClient.parseTnc2Line(">DST:data"))
    }

    @Test
    fun `parse unknown data type preserves raw`() {
        val line = "N0CALL>APRS:>Status text here"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        assertEquals('>', pkt!!.dataType)
        assertEquals(">Status text here", pkt.raw)
    }

    @Test
    fun `position precision matches APRS spec`() {
        // 1 digit of DDMM.MM = ~18m precision
        val line = "TEST>APRS:!4903.50N/07201.75W-test"
        val pkt = AprsIsClient.parseTnc2Line(line)

        assertNotNull(pkt)
        // 49°03.50' = 49.05833...
        assertEquals(49.0583, pkt!!.lat, 0.0005)
        // 072°01.75' = -72.02916...
        assertEquals(-72.0291, pkt.lon, 0.0005)
    }
}
