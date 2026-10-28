package net.meshsat.android

import net.meshsat.android.warnings.WarningText
import net.meshsat.android.warnings.WarningText.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kit's mesh text read back (MESHSAT-1734). The vectors are the Bridge's own
 * (`internal/warnings/relay_test.go`): what `MeshText` writes for an alert, an update, a cancel
 * and a test, on port 11 with the BEL and as a plain text.
 */
class WarningTextTest {

    private val label = "Relay of KNMI via MeteoAlarm warning, issued 28 Oct 10:11 UTC via MeshSat, not an official NL-Alert"
    private val alert = "WARNING Wind Moderate until 10:11 UTC: Kans op windstoten tot 90 km/u\n$label"

    @Test
    fun `the kit's alert on port 11 parses, BEL dropped`() {
        val p = WarningText.recognise(alert + "\u0007", WarningText.PORT_ALERT)
        assertNotNull(p)
        assertEquals(Kind.ALERT, p!!.kind)
        assertFalse(p.test)
        assertEquals("Wind", p.event)
        assertEquals("Moderate", p.severity)
        assertEquals("10:11", p.until)
        assertEquals("Kans op windstoten tot 90 km/u", p.text)
        assertEquals(label, p.label)
        assertEquals("WARNING Wind Moderate until 10:11 UTC: Kans op windstoten tot 90 km/u", p.headline)
    }

    @Test
    fun `the same words as a plain text are the warning, not a chat`() {
        val p = WarningText.recognise(alert, WarningText.PORT_TEXT)
        assertNotNull(p)
        assertEquals("Wind", p!!.event)
        assertTrue(WarningText.isRelayedText(alert))
    }

    @Test
    fun `a chat that starts with WARNING but has no relay line stays a chat`() {
        assertNull(WarningText.recognise("WARNING Wind Severe: the tent is gone", WarningText.PORT_TEXT))
        assertNull(WarningText.recognise("WARNING Wind Severe: the tent is gone\nsee you at the car", WarningText.PORT_TEXT))
        assertFalse(WarningText.isRelayedText("warning, the tent is gone"))
        assertNull(WarningText.recognise("hello", WarningText.PORT_TEXT))
    }

    @Test
    fun `an update and a cancel parse to their kinds`() {
        val u = WarningText.parse("UPDATE: WARNING Wind Severe until 12:00 UTC: Windstoten tot 110 km/u\n$label")
        assertEquals(Kind.UPDATE, u!!.kind)
        assertEquals("Severe", u.severity)
        assertEquals("12:00", u.until)

        val c = WarningText.parse("CANCELLED: Wind\n$label")
        assertEquals(Kind.CANCEL, c!!.kind)
        assertEquals("Wind", c.event)
        assertEquals("", c.severity)
        assertEquals("", c.text)

        val ct = WarningText.parse("CANCELLED: Wind - Het waait niet meer\n$label")
        assertEquals("Wind", ct!!.event)
        assertEquals("Het waait niet meer", ct.text)
    }

    @Test
    fun `a test warning is marked as one`() {
        val t = WarningText.parse("TEST WARNING Wind Minor until 10:11 UTC: proef\nRelay of MeshSat test warning, issued 28 Oct 10:11 UTC via MeshSat, not an official NL-Alert")
        assertTrue(t!!.test)
        assertEquals(Kind.ALERT, t.kind)
        assertEquals("Wind", t.event)
        val tc = WarningText.parse("TEST CANCELLED: Wind\n$label")
        assertTrue(tc!!.test)
        assertEquals(Kind.CANCEL, tc.kind)
        assertEquals("TEST WARNING", WarningText.kindLabel(Kind.ALERT, true))
        assertEquals("CANCELLED", WarningText.kindLabel(Kind.CANCEL, false))
    }

    @Test
    fun `no expiry and no text still parse, and a one-word event with a slash`() {
        val p = WarningText.parse("WARNING Snow/ice Severe\n$label")
        assertEquals("Snow/ice", p!!.event)
        assertEquals("Severe", p.severity)
        assertEquals("", p.until)
        assertEquals("", p.text)
        assertEquals("Snow/ice Severe", WarningText.summary(p.event, p.severity, p.until))
        assertEquals("Wind Moderate until 10:11 UTC", WarningText.summary("Wind", "Moderate", "10:11"))
    }

    @Test
    fun `a first line cut by the kit still gives the event and the severity`() {
        val p = WarningText.parse("WARNING Wind Severe until 10:1\n$label")
        assertEquals("Wind", p!!.event)
        assertEquals("Severe", p.severity)
        assertEquals("", p.until)
        val q = WarningText.parse("WARNING Wind Severe until 10:11 UTC: Windstoten tot 1\n$label")
        assertEquals("Windstoten tot 1", q!!.text)
    }

    @Test
    fun `an unknown severity is not taken for one`() {
        val p = WarningText.parse("WARNING Wind Huge until 10:11 UTC: x\n$label")
        assertEquals("Wind", p!!.event)
        assertEquals("", p.severity)
    }

    @Test
    fun `an alert of another shape on port 11 is still an alert, kept whole`() {
        val p = WarningText.recognise("Shelter now\u0007", WarningText.PORT_ALERT)
        assertNotNull(p)
        assertEquals("Alert", p!!.event)
        assertEquals("Shelter now", p.text)
        assertEquals("", p.label)
        assertNull(WarningText.recognise("\u0007", WarningText.PORT_ALERT))
        assertNull(WarningText.recognise(alert, 3))
    }

    @Test
    fun `the dedup key folds whitespace and the BEL so both copies are one`() {
        assertEquals(WarningText.dedupKey(alert), WarningText.dedupKey(alert + "\u0007"))
        assertEquals(WarningText.dedupKey(alert), WarningText.dedupKey(alert.replace("\n", "  \n ")))
        assertFalse(WarningText.dedupKey(alert) == WarningText.dedupKey(alert.replace("Moderate", "Severe")))
    }
}
