package net.meshsat.android.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** The debug.zip of Setup > Support and what it blanks (MESHSAT-1806). */
class SupportBundleTest {

    private fun r(s: String) = SupportRedactor.redact(s)

    @Test
    fun `phone numbers are blanked, timestamps are not`() {
        assertEquals("SMS to [phone] queued", r("SMS to +31612345678 queued"))
        assertEquals("from [phone]", r("from +31 6 12345678"))
        assertEquals("call [phone] now", r("call 0612345678 now"))
        // Unix seconds, milliseconds and a logcat time stay as they are.
        assertEquals("at 1762440000 and 1762440000123", r("at 1762440000 and 1762440000123"))
        assertEquals("11-06 15:40:12.345  1234  5678 I MeshSat: ok", r("11-06 15:40:12.345  1234  5678 I MeshSat: ok"))
        // A signal reading with a sign is no phone number.
        assertEquals("rssi +5 dBm", r("rssi +5 dBm"))
    }

    @Test
    fun `IMEIs, emails and keys are blanked`() {
        assertEquals("imei [imei] momsn 241", r("imei 300258060512710 momsn 241"))
        assertEquals("mail [email] failed", r("mail someone.else@example.org failed"))
        assertEquals("password=[removed] user=kit", r("password=hunter2 user=kit"))
        assertEquals("{\"token\": \"[removed]\"}", r("{\"token\": \"abc.def\"}"))
        assertEquals("Authorization: Bearer [removed]", r("Authorization: Bearer eyJhbGciOi.x-y_z"))
        val pem = "cert -----BEGIN PRIVATE KEY-----\nMIIBVQIBADAN\nBgkqhkiG9w0=\n-----END PRIVATE KEY----- done"
        assertEquals("cert [key removed] done", r(pem))
    }

    @Test
    fun `MAC addresses keep only their last byte`() {
        assertEquals("node XX:XX:XX:XX:XX:4D bonded", r("node 48:CA:43:57:07:4D bonded"))
        // A node id and a destination hash are not addresses.
        assertEquals("!8b04a69e d180c2083c4b9fecd0ff19812cb9b074", r("!8b04a69e d180c2083c4b9fecd0ff19812cb9b074"))
    }

    @Test
    fun `the zip holds every part, redacted`() {
        val parts = listOf(
            SupportBundle.Part("README.txt", "hello\n"),
            SupportBundle.Part("logcat.txt", "SMS to +31612345678\n"),
        )
        val bytes = SupportBundle.bytes(parts, 1_762_440_000_000L)
        val seen = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                seen[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("README.txt", "logcat.txt"), seen.keys)
        assertEquals("SMS to [phone]\n", seen["logcat.txt"])
    }

    @Test
    fun `a runaway part keeps its newest end`() {
        val big = "a".repeat(SupportBundle.MAX_PART_CHARS) + "LAST"
        val bytes = SupportBundle.bytes(listOf(SupportBundle.Part("logcat.txt", big)), 0L)
        val text = ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry; it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(text.startsWith("[the first 4 characters were cut]"))
        assertTrue(text.endsWith("LAST"))
    }

    @Test
    fun `the file name carries the time and the mail body the version`() {
        assertEquals("meshsat-debug-20261106-144000.zip", SupportBundle.fileName(1_793_976_000_000L, ZoneId.of("UTC")))
        val body = SupportBundle.mailBody("  It stopped.  ", "2.26.0", 1490, "full edition", "16 (API 36)", "Google Pixel 9a", attached = true)
        assertTrue(body.startsWith("It stopped.\n\n--\nMeshSat Android 2.26.0 (1490), full edition\n"))
        assertTrue(body.contains("debug.zip"))
        assertFalse(SupportBundle.mailBody("x", "2.26.0", 1490, "e", "a", "d", attached = false).contains("debug.zip"))
        assertEquals("MeshSat Android 2.26.0: a problem", SupportBundle.subject(SupportBundle.Topic.Problem, "2.26.0"))
    }
}
