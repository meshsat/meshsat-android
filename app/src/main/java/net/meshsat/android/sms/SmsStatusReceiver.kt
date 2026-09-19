package net.meshsat.android.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.meshsat.android.data.AppDatabase

/**
 * What the phone's radio and the carrier say about an SMS the app sent (MESHSAT-1246): "sent"
 * once it left the phone, "delivered" once the carrier's delivery report says the other phone has
 * it. A chat message shows one tick, then two; an SOS to an emergency contact says it was delivered.
 *
 * Registered in the manifest, not at run time, because a delivery report can come hours later,
 * when the app may not be running.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val deliveryId = intent.getLongExtra(EXTRA_DELIVERY_ID, -1L)
        val code = resultCode
        val report = if (intent.action == ACTION_DELIVERED) reportState(intent) else null
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                when (intent.action) {
                    ACTION_SENT -> if (messageId > 0) {
                        db.messageDao().setForwardedToUnlessDelivered(messageId, if (code == Activity.RESULT_OK) SENT else FAILED)
                    }
                    ACTION_DELIVERED -> when (report) {
                        SmsReport.Delivered -> {
                            if (messageId > 0) db.messageDao().setForwardedTo(messageId, DELIVERED)
                            if (deliveryId > 0) db.messageDeliveryDao().markAcked(deliveryId)
                        }
                        SmsReport.Failed -> if (messageId > 0) db.messageDao().setForwardedTo(messageId, FAILED)
                        else -> Unit // still pending at the carrier, or unreadable: keep what it says
                    }
                }
            } catch (e: Exception) {
                Log.w("SmsStatus", "Could not record an SMS status: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun reportState(intent: Intent): SmsReport? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format") ?: "3gpp"
        return try {
            SmsMessage.createFromPdu(pdu, format)?.let { SmsReport.fromStatus(it.status) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ACTION_SENT = "net.meshsat.android.SMS_STATUS_SENT"
        const val ACTION_DELIVERED = "net.meshsat.android.SMS_STATUS_DELIVERED"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_DELIVERY_ID = "delivery_id"

        const val SENDING = "sms:sending"
        const val SENT = "sms:sent"
        const val DELIVERED = "sms:delivered"
        const val FAILED = "sms:failed"

        /** "It left the phone" for a chat message; immutable, the result code is all it carries. */
        fun sentIntent(context: Context, messageId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_SENT, Uri.parse("meshsat://sms/message/$messageId"))
                    .setClass(context, SmsStatusReceiver::class.java)
                    .putExtra(EXTRA_MESSAGE_ID, messageId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /**
         * The carrier's delivery report, for a chat message or a queued delivery. Mutable because
         * Android adds the report's PDU to the intent; explicit (our own receiver), as a mutable
         * PendingIntent must be.
         */
        fun deliveredIntent(context: Context, messageId: Long = -1L, deliveryId: Long = -1L): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_DELIVERED, Uri.parse("meshsat://sms/report/$messageId/$deliveryId"))
                    .setClass(context, SmsStatusReceiver::class.java)
                    .putExtra(EXTRA_MESSAGE_ID, messageId)
                    .putExtra(EXTRA_DELIVERY_ID, deliveryId),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/**
 * A delivery report's status (TP-Status, 3GPP TS 23.040 9.2.3.15, as SmsMessage.getStatus gives
 * it): 0x00-0x1F the message reached the other phone, 0x20-0x3F the carrier is still trying,
 * 0x40 and up it gave up.
 */
enum class SmsReport {
    Delivered, Pending, Failed;

    companion object {
        fun fromStatus(status: Int): SmsReport = when {
            status < 0 -> Pending
            status < 0x20 -> Delivered
            status < 0x40 -> Pending
            else -> Failed
        }
    }
}
