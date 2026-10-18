package net.meshsat.android

import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.SmsPartReport
import net.meshsat.android.data.SmsPartReportDao
import net.meshsat.android.data.SmsSend
import net.meshsat.android.sms.SmsCompletion
import net.meshsat.android.sms.SmsPartKey
import net.meshsat.android.sms.SmsPartLedger
import net.meshsat.android.sms.SmsReceipt
import net.meshsat.android.sms.SmsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The HeMB-NG fixture release this app tests against (contract C12, MESHSAT-1584): the copy is the
 * release fixtures.lock names, and the SMS vectors hold for this app's decoder and part ledger.
 */
class HembNgFixturesTest {

    private val root = File(requireNotNull(javaClass.classLoader?.getResource("hemb-ng/fixtures.lock")).toURI()).parentFile
    private val dir = File(root, "v1")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the copy is the locked release`() {
        val lock = File(root, "fixtures.lock").readLines().filter { it.isNotBlank() }
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val sums = File(dir, "SHA256SUMS").readBytes()
        assertEquals("SHA256SUMS against fixtures.lock", lock["sha256"], sha256(sums))
        val listed = mutableSetOf("SHA256SUMS")
        String(sums).lines().filter { it.isNotBlank() }.forEach { line ->
            val want = line.substringBefore("  ")
            val name = line.substringAfter("  ")
            assertTrue("bad SHA256SUMS line $line", '/' !in name && '\\' !in name)
            assertEquals("$name differs from the release", want, sha256(File(dir, name).readBytes()))
            listed += name
        }
        val extra = dir.list().orEmpty().filter { it !in listed }
        assertTrue("not part of the release: $extra", extra.isEmpty())
        assertEquals(lock["tag"], File(dir, "RELEASE").readText().trim())
    }

    private fun rows(name: String) = File(dir, name).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    @Test
    fun `every delivery report vector decodes as the release says`() {
        val vectors = rows("sms_reports.tsv")
        assertTrue(vectors.size >= 10)
        for ((format, statusHex, want) in vectors) {
            val got = SmsReport.from(format, statusHex.toLong(16).toInt())
            assertEquals("$format $statusHex", want, got?.name ?: "unreadable")
        }
    }

    private class Table : SmsPartReportDao {
        val rows = mutableMapOf<Pair<String, Int>, SmsPartReport>()
        val sends = mutableMapOf<String, SmsSend>()
        override suspend fun partsOf(attempt: String) = rows.values.filter { it.attempt == attempt }
        override suspend fun put(report: SmsPartReport) { rows[report.attempt to report.part] = report }
        override suspend fun pruneBefore(before: Long) = 0
        override suspend fun register(send: SmsSend): Long { sends.putIfAbsent(send.attempt, send); return 1 }
        override suspend fun sendOf(attempt: String) = sends[attempt]
        override suspend fun pruneSendsBefore(before: Long) = 0
    }

    @Test
    fun `every multipart vector completes as the release says`() = runBlocking {
        val vectors = rows("sms_parts.tsv")
        assertTrue(vectors.size >= 8)
        for ((parts, reports, want) in vectors) {
            val table = Table()
            SmsPartLedger.register(table, "send", parts.toInt(), SmsReceipt(), now = 1_000L)
            var last = SmsCompletion.Open
            for (r in reports.split(',')) {
                val (part, state) = r.split(':')
                last = SmsPartLedger.record(table, SmsPartKey("send", part.toInt(), parts.toInt()), SmsReport.valueOf(state), now = 1_000L)
            }
            assertEquals("$parts parts, $reports", want, last.name)
        }
    }
}
