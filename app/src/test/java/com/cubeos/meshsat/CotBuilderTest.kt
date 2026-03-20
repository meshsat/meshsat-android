package com.cubeos.meshsat

import com.cubeos.meshsat.tak.CotBuilder
import com.cubeos.meshsat.tak.CotHow
import com.cubeos.meshsat.tak.CotType
import com.cubeos.meshsat.tak.CotXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoT builder and XML serialization tests (MESHSAT-191).
 * Verifies wire compatibility with Bridge's tak_cot.go format.
 */
class CotBuilderTest {

    @Test
    fun `callsign format matches bridge`() {
        assertEquals("MESHSAT-3456", CotBuilder.callsign("123456"))
        assertEquals("MESHSAT-ABCD", CotBuilder.callsign("abcd"))
        assertEquals("MESHSAT", CotBuilder.callsign(""))
        assertEquals("MS-3456", CotBuilder.callsign("123456", "MS"))
    }

    @Test
    fun `position event has correct type and how`() {
        val ev = CotBuilder.position("uid1", "MESHSAT-1234", 47.3, -122.5, 100.0)
        assertEquals(CotType.POSITION, ev.type)
        assertEquals(CotHow.GPS, ev.how)
        assertEquals("2.0", ev.version)
        assertEquals(47.3, ev.point.lat, 0.001)
        assertEquals(-122.5, ev.point.lon, 0.001)
        assertEquals(100.0, ev.point.hae, 0.001)
        assertEquals(10.0, ev.point.ce, 0.001)
        assertEquals(10.0, ev.point.le, 0.001)
        assertEquals("MESHSAT-1234", ev.detail?.contact?.callsign)
        assertEquals("Cyan", ev.detail?.group?.name)
        assertEquals("Team Member", ev.detail?.group?.role)
        assertEquals("GPS", ev.detail?.precision?.altSrc)
        assertNull(ev.detail?.emergency)
    }

    @Test
    fun `sos event has emergency element`() {
        val ev = CotBuilder.sos("uid1", "MESHSAT-1234", 47.3, -122.5, reason = "battery low")
        assertEquals(CotType.POSITION, ev.type) // SOS uses position type with emergency detail
        assertNotNull(ev.detail?.emergency)
        assertEquals("911 Alert", ev.detail?.emergency?.type)
        assertEquals("battery low", ev.detail?.emergency?.text)
        assertEquals("Emergency: battery low", ev.detail?.remarks?.text)
        assertEquals("MeshSat", ev.detail?.remarks?.source)
    }

    @Test
    fun `deadman event has alarm type`() {
        val ev = CotBuilder.deadman("uid1", "MESHSAT-1234", 47.3, -122.5, 7200)
        assertEquals(CotType.ALARM, ev.type)
        assertEquals(CotHow.HUMAN_ENTERED, ev.how)
        assertEquals("uid1-DEADMAN", ev.uid)
        assertEquals(100.0, ev.point.ce, 0.001)
        assertTrue(ev.detail?.remarks?.text?.contains("7200s") == true)
    }

    @Test
    fun `chat event has geochat type and zero coords`() {
        val ev = CotBuilder.chat("uid1", "MESHSAT-1234", "hello world")
        assertEquals(CotType.CHAT, ev.type)
        assertEquals(CotHow.HUMAN_GEOCHAT, ev.how)
        assertTrue(ev.uid.contains("-CHAT-"))
        assertEquals(0.0, ev.point.lat, 0.001)
        assertEquals(0.0, ev.point.lon, 0.001)
        assertEquals(9999999.0, ev.point.ce, 0.001)
        assertEquals("hello world", ev.detail?.remarks?.text)
        assertEquals("MESHSAT-1234", ev.detail?.remarks?.source)
    }

    @Test
    fun `telemetry event has sensor type`() {
        val ev = CotBuilder.telemetry("uid1", "MESHSAT-1234", 47.3, -122.5, "temp=22.5C")
        assertEquals(CotType.SENSOR, ev.type)
        assertEquals("uid1-SENSOR", ev.uid)
        assertEquals("MESHSAT-1234-SENSOR", ev.detail?.contact?.callsign)
        assertEquals(50.0, ev.point.ce, 0.001)
        assertEquals("temp=22.5C", ev.detail?.remarks?.text)
    }

    @Test
    fun `stale time is in the future`() {
        val ev = CotBuilder.position("uid1", "CS", 0.0, 0.0, staleSec = 300)
        // Stale should be after start
        assertTrue(ev.stale > ev.start)
    }

    @Test
    fun `xml marshal produces valid CoT XML`() {
        val ev = CotBuilder.position("uid1", "MESHSAT-1234", 47.3, -122.5, 100.0)
        val xml = CotXml.marshal(ev)
        assertTrue(xml.startsWith("<event"))
        assertTrue(xml.contains("version=\"2.0\""))
        assertTrue(xml.contains("type=\"a-f-G-U-C\""))
        assertTrue(xml.contains("how=\"m-g\""))
        assertTrue(xml.contains("<point"))
        assertTrue(xml.contains("lat=\"47.3\""))
        assertTrue(xml.contains("<contact"))
        assertTrue(xml.contains("callsign=\"MESHSAT-1234\""))
        assertTrue(xml.contains("<__group"))
        assertTrue(xml.contains("name=\"Cyan\""))
        assertTrue(xml.contains("<precisionlocation"))
        assertTrue(xml.contains("</event>"))
    }

    @Test
    fun `xml marshal sos includes emergency element`() {
        val ev = CotBuilder.sos("uid1", "CS", 47.3, -122.5, reason = "help me")
        val xml = CotXml.marshal(ev)
        assertTrue(xml.contains("<emergency"))
        assertTrue(xml.contains("type=\"911 Alert\""))
        assertTrue(xml.contains("help me"))
        assertTrue(xml.contains("<remarks"))
        assertTrue(xml.contains("Emergency: help me"))
    }

    @Test
    fun `xml round-trip preserves event`() {
        val ev = CotBuilder.position("uid-rt", "MESHSAT-TEST", 47.3, -122.5, 100.0)
        val xml = CotXml.marshal(ev)
        val parsed = CotXml.parse(xml)

        assertNotNull(parsed)
        assertEquals(ev.version, parsed!!.version)
        assertEquals(ev.uid, parsed.uid)
        assertEquals(ev.type, parsed.type)
        assertEquals(ev.how, parsed.how)
        assertEquals(ev.point.lat, parsed.point.lat, 0.001)
        assertEquals(ev.point.lon, parsed.point.lon, 0.001)
        assertEquals(ev.point.hae, parsed.point.hae, 0.001)
        assertEquals(ev.detail?.contact?.callsign, parsed.detail?.contact?.callsign)
        assertEquals(ev.detail?.group?.name, parsed.detail?.group?.name)
    }

    @Test
    fun `xml round-trip preserves sos emergency`() {
        val ev = CotBuilder.sos("uid-sos", "CS", 47.3, -122.5, reason = "evacuation needed")
        val xml = CotXml.marshal(ev)
        val parsed = CotXml.parse(xml)

        assertNotNull(parsed)
        assertNotNull(parsed!!.detail?.emergency)
        assertEquals("911 Alert", parsed.detail?.emergency?.type)
        assertEquals("evacuation needed", parsed.detail?.emergency?.text)
    }

    @Test
    fun `xml parse returns null for invalid input`() {
        assertNull(CotXml.parse("not xml"))
        assertNull(CotXml.parse(""))
    }

    @Test
    fun `xml escapes special characters`() {
        val ev = CotBuilder.chat("uid1", "CS", "test <>&\"' message")
        val xml = CotXml.marshal(ev)
        assertTrue(xml.contains("&lt;"))
        assertTrue(xml.contains("&gt;"))
        assertTrue(xml.contains("&amp;"))
        // Verify round-trip preserves special chars
        val parsed = CotXml.parse(xml)
        assertEquals("test <>&\"' message", parsed?.detail?.remarks?.text)
    }

    @Test
    fun `time format matches bridge format`() {
        val ev = CotBuilder.position("uid1", "CS", 0.0, 0.0)
        // Should be ISO 8601 UTC: yyyy-MM-ddTHH:mm:ssZ
        assertTrue(ev.time.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
        assertTrue(ev.start.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
        assertTrue(ev.stale.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
    }
}
