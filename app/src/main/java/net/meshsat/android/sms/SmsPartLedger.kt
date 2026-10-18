package net.meshsat.android.sms

import net.meshsat.android.data.SmsPartReport
import net.meshsat.android.data.SmsPartReportDao
import net.meshsat.android.data.SmsSend

/** Which part of which send a delivery report is about. */
data class SmsPartKey(val attempt: String, val part: Int, val parts: Int)

/** What every report of one send says so far about the whole message. */
enum class SmsCompletion { Delivered, Failed, Open }

/**
 * Adds up the carrier's delivery reports for the parts of one SMS send (MESHSAT-1584). Android
 * gives one report per part; the message is delivered once every part of the same send has a
 * report that the other phone received it, and failed while any part has a final failure and not
 * all are delivered. Each part keeps its strongest report, so duplicates and reports that come in
 * any order end in the same result. The send is registered before it goes out with its part count
 * and owner; a report for another send, another count or another owner never counts and never
 * changes this send's evidence. [record] runs inside the database transaction that also writes the
 * outcome ([SmsReceipts.apply]).
 */
object SmsPartLedger {

    /** How long a send and its reports are kept; a carrier gives up on an SMS well before. */
    const val KEEP_MS = 7L * 24 * 3600 * 1000

    /** Strength of a report: a part's strongest one stands. */
    internal fun rank(report: SmsReport): Int = when (report) {
        SmsReport.Pending -> 0
        SmsReport.CompletedUnconfirmed -> 1
        SmsReport.Failed -> 2
        SmsReport.Delivered -> 3
    }

    private fun stateOf(name: String): SmsReport = SmsReport.entries.firstOrNull { it.name == name } ?: SmsReport.Pending

    /** Fixes a send's part count and owner before it goes out. */
    suspend fun register(dao: SmsPartReportDao, attempt: String, parts: Int, receipt: SmsReceipt, now: Long = System.currentTimeMillis()) {
        dao.register(SmsSend(attempt, parts, receipt.messageId, receipt.deliveryId, now))
    }

    suspend fun record(
        dao: SmsPartReportDao,
        key: SmsPartKey,
        report: SmsReport,
        messageId: Long = -1,
        deliveryId: Long = -1,
        now: Long = System.currentTimeMillis(),
    ): SmsCompletion {
        if (key.attempt.isEmpty() || key.parts < 1 || key.part !in 0 until key.parts) return SmsCompletion.Open
        dao.pruneBefore(now - KEEP_MS)
        dao.pruneSendsBefore(now - KEEP_MS)
        // A send this app did not register (an intent from before registration, or a send whose
        // registration failed) can fail a message, never deliver it.
        val send = dao.sendOf(key.attempt) ?: return if (report == SmsReport.Failed) SmsCompletion.Failed else SmsCompletion.Open
        if (send.parts != key.parts || send.messageId != messageId || send.deliveryId != deliveryId) return SmsCompletion.Open
        val held = dao.partsOf(key.attempt).firstOrNull { it.part == key.part }
        if (held == null || rank(report) > rank(stateOf(held.state))) {
            dao.put(SmsPartReport(key.attempt, key.part, send.parts, messageId, deliveryId, report.name, now))
        }
        return completion(dao.partsOf(key.attempt), send.parts)
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
