package net.meshsat.android.sms

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.meshsat.android.data.SmsPartReport
import net.meshsat.android.data.SmsPartReportDao

/** Which part of which send a delivery report is about. */
data class SmsPartKey(val attempt: String, val part: Int, val parts: Int)

/** What every report of one send says so far about the whole message. */
enum class SmsCompletion { Delivered, Failed, Open }

/**
 * Adds up the carrier's delivery reports for the parts of one SMS send (MESHSAT-1584). Android
 * gives one report per part; the message is delivered once every part of the same send has a
 * report that the other phone received it, and failed while any part has a final failure and not
 * all are delivered. Each part keeps its strongest report, so duplicates and reports that come in
 * any order end in the same result, and a report for another send never counts.
 */
object SmsPartLedger {

    /** How long a part's report is kept; a carrier gives up on an SMS well before. */
    const val KEEP_MS = 7L * 24 * 3600 * 1000

    private val lock = Mutex()

    /** Strength of a report: a part's strongest one stands. */
    internal fun rank(report: SmsReport): Int = when (report) {
        SmsReport.Pending -> 0
        SmsReport.CompletedUnconfirmed -> 1
        SmsReport.Failed -> 2
        SmsReport.Delivered -> 3
    }

    private fun stateOf(name: String): SmsReport = SmsReport.entries.firstOrNull { it.name == name } ?: SmsReport.Pending

    suspend fun record(
        dao: SmsPartReportDao,
        key: SmsPartKey,
        report: SmsReport,
        messageId: Long = -1,
        deliveryId: Long = -1,
        now: Long = System.currentTimeMillis(),
    ): SmsCompletion = lock.withLock {
        if (key.attempt.isEmpty() || key.parts < 1 || key.part !in 0 until key.parts) return@withLock SmsCompletion.Open
        dao.pruneBefore(now - KEEP_MS)
        val held = dao.partsOf(key.attempt).firstOrNull { it.part == key.part && it.parts == key.parts }
        if (held == null || rank(report) > rank(stateOf(held.state))) {
            dao.put(SmsPartReport(key.attempt, key.part, key.parts, messageId, deliveryId, report.name, now))
        }
        completion(dao.partsOf(key.attempt), key.parts)
    }

    internal fun completion(rows: List<SmsPartReport>, parts: Int): SmsCompletion {
        val states = rows.filter { it.parts == parts && it.part in 0 until parts }.associate { it.part to stateOf(it.state) }
        return when {
            states.size == parts && states.values.all { it == SmsReport.Delivered } -> SmsCompletion.Delivered
            states.values.any { it == SmsReport.Failed } -> SmsCompletion.Failed
            else -> SmsCompletion.Open
        }
    }
}
