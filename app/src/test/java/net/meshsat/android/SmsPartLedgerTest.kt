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
import org.junit.Test

/**
 * A long SMS is delivered only when every part of the same send is (review 2026-10-18 18:26,
 * FE-R1, MESHSAT-1584): Android gives one report per part, and a report for the last part says
 * nothing about the others.
 */
class SmsPartLedgerTest {

    /** The table, as the database keeps it across a restart of the app. */
    private class Table : SmsPartReportDao {
        val rows = linkedMapOf<Pair<String, Int>, SmsPartReport>()
        val sends = linkedMapOf<String, SmsSend>()
        override suspend fun partsOf(attempt: String) = rows.values.filter { it.attempt == attempt }
        override suspend fun put(report: SmsPartReport) {
            rows[report.attempt to report.part] = report
        }
        override suspend fun pruneBefore(before: Long): Int {
            val old = rows.filterValues { it.updatedAt < before }.keys
            old.forEach { rows.remove(it) }
            return old.size
        }
        override suspend fun register(send: SmsSend): Long {
            if (send.attempt in sends) return -1
            sends[send.attempt] = send
            return 1
        }
        override suspend fun sendOf(attempt: String) = sends[attempt]
        override suspend fun pruneSendsBefore(before: Long): Int {
            val old = sends.filterValues { it.createdAt < before }.keys
            old.forEach { sends.remove(it) }
            return old.size
        }
    }

    private val now = 1_000_000_000L

    /** Each send in these tests is registered with its part count before its first report. */
    private fun Table.report(attempt: String, part: Int, parts: Int, report: SmsReport, at: Long = now, deliveryId: Long = 7) =
        runBlocking {
            if (attempt !in sends) SmsPartLedger.register(this@report, attempt, parts, SmsReceipt(deliveryId = 7), at)
            SmsPartLedger.record(this@report, SmsPartKey(attempt, part, parts), report, deliveryId = deliveryId, now = at)
        }

    @Test
    fun `two parts with only the last delivered stay unconfirmed`() {
        val t = Table()
        assertEquals(SmsCompletion.Open, t.report("a", 1, 2, SmsReport.Delivered))
    }

    @Test
    fun `both parts delivered complete the message in either order`() {
        val t = Table()
        assertEquals(SmsCompletion.Open, t.report("a", 1, 2, SmsReport.Delivered))
        assertEquals(SmsCompletion.Delivered, t.report("a", 0, 2, SmsReport.Delivered))
        val u = Table()
        assertEquals(SmsCompletion.Open, u.report("b", 0, 2, SmsReport.Delivered))
        assertEquals(SmsCompletion.Delivered, u.report("b", 1, 2, SmsReport.Delivered))
    }

    @Test
    fun `a single part delivered is the message delivered`() {
        assertEquals(SmsCompletion.Delivered, Table().report("a", 0, 1, SmsReport.Delivered))
    }

    @Test
    fun `a duplicate report for one part does not count as another part`() {
        val t = Table()
        t.report("a", 1, 3, SmsReport.Delivered)
        t.report("a", 1, 3, SmsReport.Delivered)
        assertEquals(SmsCompletion.Open, t.report("a", 0, 3, SmsReport.Delivered))
        assertEquals(SmsCompletion.Delivered, t.report("a", 2, 3, SmsReport.Delivered))
    }

    @Test
    fun `a report for another send never completes this one`() {
        val t = Table()
        assertEquals(SmsCompletion.Open, t.report("first", 0, 2, SmsReport.Delivered))
        assertEquals(SmsCompletion.Open, t.report("second", 1, 2, SmsReport.Delivered))
        // the same send with another part count is another send's report too
        assertEquals(SmsCompletion.Open, t.report("first", 1, 3, SmsReport.Delivered))
    }

    @Test
    fun `a report with another part count leaves the send's evidence as it was`() {
        // review 2026-10-18 19:34, FE-R3: 2 -> 1 replaced the two-part row and completed the send
        for (other in listOf(1, 3)) {
            val t = Table()
            t.report("a", 0, 2, SmsReport.Delivered)
            val before = t.rows.toMap()
            assertEquals("a $other-part report", SmsCompletion.Open, t.report("a", 0, other, SmsReport.Delivered))
            assertEquals("a $other-part report changed the evidence", before, t.rows.toMap())
            assertEquals(SmsCompletion.Delivered, t.report("a", 1, 2, SmsReport.Delivered))
        }
    }

    @Test
    fun `a report naming another owner leaves the send's evidence as it was`() {
        val t = Table()
        t.report("a", 0, 2, SmsReport.Delivered)
        assertEquals(SmsCompletion.Open, t.report("a", 1, 2, SmsReport.Delivered, deliveryId = 8))
        assertEquals(1, t.rows.size)
    }

    @Test
    fun `a send this app never registered can fail a message, never deliver it`() = runBlocking {
        val t = Table()
        assertEquals(SmsCompletion.Open, SmsPartLedger.record(t, SmsPartKey("x", 0, 1), SmsReport.Delivered, now = now))
        assertEquals(SmsCompletion.Failed, SmsPartLedger.record(t, SmsPartKey("x", 0, 1), SmsReport.Failed, now = now))
        assertEquals(0, t.rows.size)
    }

    @Test
    fun `a part's evidence survives a restart`() {
        val t = Table() // the database; the ledger keeps nothing of its own between reports
        t.report("a", 0, 2, SmsReport.Delivered, at = now)
        assertEquals(SmsCompletion.Delivered, t.report("a", 1, 2, SmsReport.Delivered, at = now + 6 * 3600_000L))
    }

    @Test
    fun `a weaker report later never takes back a delivered part`() {
        val t = Table()
        t.report("a", 0, 2, SmsReport.Delivered)
        t.report("a", 0, 2, SmsReport.Pending)
        t.report("a", 0, 2, SmsReport.CompletedUnconfirmed)
        assertEquals(SmsCompletion.Delivered, t.report("a", 1, 2, SmsReport.Delivered))
    }

    @Test
    fun `a part that failed fails the message until every part is delivered`() {
        val t = Table()
        assertEquals(SmsCompletion.Failed, t.report("a", 0, 2, SmsReport.Failed))
        assertEquals(SmsCompletion.Failed, t.report("a", 1, 2, SmsReport.Delivered))
    }

    @Test
    fun `the handset's receipt for a part outweighs a failure reported for it`() {
        val t = Table()
        t.report("a", 0, 2, SmsReport.Delivered)
        t.report("a", 1, 2, SmsReport.Failed)
        assertEquals(SmsCompletion.Delivered, t.report("a", 1, 2, SmsReport.Delivered))
        assertEquals(SmsCompletion.Delivered, t.report("a", 1, 2, SmsReport.Failed))
    }

    @Test
    fun `a part completed without the handset's receipt is not delivered`() {
        val t = Table()
        t.report("a", 0, 2, SmsReport.Delivered)
        assertEquals(SmsCompletion.Open, t.report("a", 1, 2, SmsReport.CompletedUnconfirmed))
    }

    @Test
    fun `every order and every duplicate of the same reports ends the same`() {
        val reports = listOf(
            0 to SmsReport.Pending, 0 to SmsReport.Delivered, 1 to SmsReport.CompletedUnconfirmed,
            1 to SmsReport.Delivered, 2 to SmsReport.Delivered, 2 to SmsReport.Delivered,
        )
        val ends = permutations(reports).map { order ->
            val t = Table()
            var last = SmsCompletion.Open
            order.forEach { (part, r) -> last = t.report("a", part, 3, r) }
            last to t.rows.values.map { it.part to it.state }.sortedBy { it.first }
        }.toSet()
        assertEquals(ends.toString(), 1, ends.size)
        assertEquals(SmsCompletion.Delivered, ends.single().first)
    }

    @Test
    fun `a report naming no part of its send is ignored`() {
        val t = Table()
        assertEquals(SmsCompletion.Open, t.report("a", 2, 2, SmsReport.Delivered))
        assertEquals(SmsCompletion.Open, t.report("a", -1, 2, SmsReport.Delivered))
        assertEquals(SmsCompletion.Open, t.report("", 0, 1, SmsReport.Delivered))
        assertEquals(0, t.rows.size)
    }

    @Test
    fun `reports older than a week are dropped`() {
        val t = Table()
        t.report("old", 0, 2, SmsReport.Delivered, at = now)
        t.report("new", 0, 1, SmsReport.Pending, at = now + SmsPartLedger.KEEP_MS + 1)
        assertEquals(listOf("new"), t.rows.values.map { it.attempt })
        assertEquals(listOf("new"), t.sends.keys.toList())
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.indices.flatMap { i -> permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it } }
}
