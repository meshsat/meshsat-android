package net.meshsat.android.sms

import androidx.room.withTransaction
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.MessageDao
import net.meshsat.android.data.MessageDeliveryDao
import net.meshsat.android.data.SmsPartReportDao

/**
 * A delivery report and the outcome it leads to, written in one database transaction (review
 * 2026-10-18 19:34, FE-R2, MESHSAT-1584): the part's evidence, the chat message and the delivery
 * either all change or none does, so a process stopped in between leaves nothing half done, and
 * two reports cannot interleave between reading the evidence and writing the outcome. Outcomes
 * only rise: a failure never takes back a message the phone already confirmed.
 */
object SmsReceipts {

    suspend fun apply(
        db: AppDatabase,
        key: SmsPartKey?,
        report: SmsReport,
        messageId: Long,
        deliveryId: Long,
        now: Long = System.currentTimeMillis(),
        afterEvidence: suspend () -> Unit = {},
    ): SmsCompletion = db.withTransaction {
        project(db.smsPartReportDao(), db.messageDao(), db.messageDeliveryDao(), key, report, messageId, deliveryId, now, afterEvidence)
    }

    internal suspend fun project(
        parts: SmsPartReportDao,
        messages: MessageDao,
        deliveries: MessageDeliveryDao,
        key: SmsPartKey?,
        report: SmsReport,
        messageId: Long,
        deliveryId: Long,
        now: Long,
        afterEvidence: suspend () -> Unit = {},
    ): SmsCompletion {
        val completion = when {
            key != null -> SmsPartLedger.record(parts, key, report, messageId, deliveryId, now)
            // A report asked for before per-part reports, on the last part only: it says nothing
            // about the other parts.
            report == SmsReport.Failed -> SmsCompletion.Failed
            else -> SmsCompletion.Open
        }
        afterEvidence()
        when (completion) {
            SmsCompletion.Delivered -> {
                if (messageId > 0) messages.setForwardedTo(messageId, SmsStatusReceiver.DELIVERED)
                // Every part reached the handset: the record says delivered, not only acked with
                // its status left at sent.
                if (deliveryId > 0) deliveries.setAcked(deliveryId, now)
            }
            SmsCompletion.Failed -> if (messageId > 0) messages.setForwardedToUnlessDelivered(messageId, SmsStatusReceiver.FAILED)
            SmsCompletion.Open -> Unit // a part is still pending or unconfirmed
        }
        return completion
    }
}
