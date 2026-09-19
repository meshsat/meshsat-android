package net.meshsat.android

import net.meshsat.android.sos.SosMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SOS texts and the satellite frame (MESHSAT-1249). The frame vectors come from the Bridge's own
 * encoder (meshsat internal/hubreporter/satuplink.go EncodeSatSOS, run on 19 Sep 2026), because the
 * Hub decodes the phone's frame with the Bridge's layout.
 */
class SosMessagesTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `the satellite frame is byte for byte the Bridge's`() {
        val fix = SosMessages.Fix(52.1620671, 4.5097402, 12f, 0L)
        val frame = SosMessages.satFrame("msa-flaneur", "300434067943980", fix, "SOS from flaneur, needs help", 1790000000L)
        assertEquals(
            "4d5301020b6d73612d666c616e6575720f3330303433343036373934333938304250a5f540904fcb1c534f532066726f6d20666c616e6575722c206e656564732068656c706ab13b80",
            hex(frame),
        )
    }

    @Test
    fun `a long bridge id is cut to 16 bytes and no fix is 0, 0 as on the Bridge`() {
        val frame = SosMessages.satFrame("a-very-long-bridge-id-here", "", null, "", 1790000000L)
        assertEquals("4d53010210612d766572792d6c6f6e672d62726964000000000000000000006ab13b80", hex(frame))
    }

    @Test
    fun `the test's position frame is byte for byte the Bridge's`() {
        val here = SosMessages.Fix(52.1620671, 4.5097402, 12f, 0L)
        assertEquals("4d5301010b6d73612d666c616e6575724250a5f540904fcb000c016ab13b80", hex(SosMessages.positionFrame("msa-flaneur", here, 12.7, 1790000000L)))
        val south = SosMessages.Fix(-33.86882, 151.20929, null, 0L)
        assertEquals("4d5301010b6d73612d666c616e657572c20779ac43173594fffd016ab13b80", hex(SosMessages.positionFrame("msa-flaneur", south, -3.2, 1790000000L)))
    }

    @Test
    fun `a test and a cancellation never raise an alarm at the Hub, whatever the name`() {
        for (name in listOf("flaneur", "Sosimos", "Emergency team", "MAYDAY", "")) {
            assertFalse(name, SosMessages.containsAlarmWord(SosMessages.testText(name)))
            assertFalse(name, SosMessages.containsAlarmWord(SosMessages.cancelText(name)))
        }
    }

    @Test
    fun `an SOS does raise the alarm on every route`() {
        val fix = SosMessages.Fix(52.16207, 4.50974, 8f, 1_000L)
        assertTrue(SosMessages.containsAlarmWord(SosMessages.meshText("flaneur", fix, 1_000L)))
        assertTrue(SosMessages.containsAlarmWord(SosMessages.smsText("flaneur", fix, 1_000L)))
        assertTrue(SosMessages.containsAlarmWord(SosMessages.frameMessage("flaneur", fix)))
    }

    @Test
    fun `coordinates use a point whatever the phone's language`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val fix = SosMessages.Fix(52.1620671, 4.5097402, 12.4f, 0L)
            assertEquals("52.16207, 4.50974", SosMessages.coordinates(fix))
            assertTrue(SosMessages.smsText("flaneur", fix, 0L).contains("mlat=52.16207&mlon=4.50974"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `an SMS with the longest name fits one GSM-7 part`() {
        val fix = SosMessages.Fix(-33.86882, -151.20929, 12345f, 0L)
        val text = SosMessages.smsText("A".repeat(40), fix, 10 * 60_000L)
        assertTrue("${text.length}: $text", text.length <= 160)
        assertTrue(text.all { it.code in 32..126 })
    }

    @Test
    fun `an old fix is called the last position`() {
        val fix = SosMessages.Fix(52.0, 4.0, null, 0L)
        assertEquals("Last position 52.00000, 4.00000 at 00:00 UTC.", SosMessages.whereText(fix, 3 * 60_000L))
        assertEquals("Position unknown.", SosMessages.whereText(null, 0L))
    }

    @Test
    fun `the frame message is cut without splitting a character`() {
        val cut = SosMessages.truncateUtf8("SOS: " + "Κυριάκος".repeat(8), 64)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 64)
        assertFalse(cut.contains('�'))
    }
}
