package net.meshsat.android

import net.meshsat.android.codec.Smaz2Bytes
import net.meshsat.android.codec.Smaz2Dict
import net.meshsat.android.sms.SmsReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the SMS receiver accepts as a compressed text (MESHSAT-1625): Greek and other non-English
 * text as sent, and still nothing that decodes to control bytes or broken UTF-8, which is how it
 * tells a compressed payload from one that never was.
 */
class SmsSmaz2TextTest {

    private fun compressed(s: String) = Smaz2Bytes.compress(s.toByteArray(Charsets.UTF_8), Smaz2Dict.DEFAULT)

    @Test
    fun `a compressed Greek, mixed or English SMS reads back as sent`() {
        for (s in listOf(
            "Καλημέρα από το σκάφος",
            "Είμαστε στο βόρειο φυλάκιο, όλοι καλά",
            "meet at the gate, ώρα 14:00",
            "the satellite is in range\nall good",
            "café ñandú 👍",
        )) assertEquals(s, SmsReceiver.smaz2Text(compressed(s)))
    }

    @Test
    fun `what does not decode to readable text is not taken as compressed`() {
        assertNull("a control byte", SmsReceiver.smaz2Text(byteArrayOf(0x41, 0x1B, 0x42)))
        assertNull("a NUL", SmsReceiver.smaz2Text(byteArrayOf(0x41, 0x00)))
        assertNull("broken UTF-8 in a verbatim run", SmsReceiver.smaz2Text(byteArrayOf(0x02, 0xFF.toByte(), 0xFE.toByte())))
        assertNull("a C1 control", SmsReceiver.smaz2Text(byteArrayOf(0x02, 0xC2.toByte(), 0x85.toByte())))
        assertNull("nothing", SmsReceiver.smaz2Text(ByteArray(0)))
    }

    @Test
    fun `an encrypted SMS that was never compressed is not read as SMAZ2`() {
        // SMAZ2 gains nothing on these, so the sender does not compress them; read as SMAZ2 each
        // byte above 0x7F was a bigram ("cafe" with an accent came out "cafssta"). [MESHSAT-1646]
        for (s in listOf("café", "Καλημέρα από το σκάφος", "ok 👍", "Είμαστε στο βόρειο φυλάκιο, όλοι καλά", "Καλημέρα", "Ναι", "Όχι")) {
            val raw = s.toByteArray(Charsets.UTF_8)
            assertTrue("$s: not compressible", net.meshsat.android.codec.Smaz2.compress(s).size >= raw.size)
            assertNull(s, SmsReceiver.smaz2Text(raw))
        }
    }

    @Test
    fun `a compressed SMS with English and Greek still reads back`() {
        val s = "this is the message from the station, όλοι καλά"
        val c = compressed(s)
        assertTrue(c.size < s.toByteArray(Charsets.UTF_8).size)
        assertEquals(s, SmsReceiver.smaz2Text(c))
    }
}
