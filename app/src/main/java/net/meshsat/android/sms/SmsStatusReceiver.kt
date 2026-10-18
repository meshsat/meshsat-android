package net.meshsat.android.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsMessage
import android.util.Log
import java.util.UUID
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
        val key = intent.getStringExtra(EXTRA_ATTEMPT)?.let {
            SmsPartKey(it, intent.getIntExtra(EXTRA_PART, -1), intent.getIntExtra(EXTRA_PARTS, 0))
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                when (intent.action) {
                    ACTION_SENT -> if (messageId > 0) {
                        db.messageDao().setForwardedToUnlessDelivered(messageId, if (code == Activity.RESULT_OK) SENT else FAILED)
                    }
                    // The part's evidence and the outcome in one transaction (MESHSAT-1584);
                    // an unreadable report keeps what the records say.
                    ACTION_DELIVERED -> if (report != null) SmsReceipts.apply(db, key, report, messageId, deliveryId)
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
            SmsMessage.createFromPdu(pdu, format)?.let { SmsReport.from(format, it.status) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ACTION_SENT = "net.meshsat.android.SMS_STATUS_SENT"
        const val ACTION_DELIVERED = "net.meshsat.android.SMS_STATUS_DELIVERED"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_DELIVERY_ID = "delivery_id"
        private const val EXTRA_ATTEMPT = "attempt"
        private const val EXTRA_PART = "part"
        private const val EXTRA_PARTS = "parts"

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
         * The carrier's delivery report on every part of one send, for a chat message or a queued
         * delivery: one intent per part, under a new id for this send, so the receiver can tell
         * the parts and the sends apart (MESHSAT-1584). The send is registered first with its part
         * count and owner; if that fails, no report is asked for and the message stays at sent.
         * Mutable because Android adds the report's PDU to the intent; explicit (our own
         * receiver), as a mutable PendingIntent must be.
         */
        suspend fun deliveredIntents(context: Context, receipt: SmsReceipt, parts: Int): ArrayList<PendingIntent>? {
            val attempt = UUID.randomUUID().toString()
            try {
                SmsPartLedger.register(AppDatabase.getInstance(context).smsPartReportDao(), attempt, parts, receipt)
            } catch (e: Exception) {
                Log.w("SmsStatus", "Could not register an SMS send; no delivery report asked for: ${e.message}")
                return null
            }
            return ArrayList(
                List(parts) { part ->
                    PendingIntent.getBroadcast(
                        context, 0,
                        Intent(ACTION_DELIVERED, Uri.parse("meshsat://sms/report/$attempt/$part"))
                            .setClass(context, SmsStatusReceiver::class.java)
                            .putExtra(EXTRA_MESSAGE_ID, receipt.messageId)
                            .putExtra(EXTRA_DELIVERY_ID, receipt.deliveryId)
                            .putExtra(EXTRA_ATTEMPT, attempt)
                            .putExtra(EXTRA_PART, part)
                            .putExtra(EXTRA_PARTS, parts),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                },
            )
        }
    }
}

/**
 * A delivery report's status (TP-Status, 3GPP TS 23.040 9.2.3.15, as SmsMessage.getStatus gives
 * it): 0x00 the other phone received it; 0x01-0x1F the carrier completed it without confirming
 * that (0x01 forwarded but delivery unconfirmed, 0x02 replaced, the rest reserved or carrier
 * specific); 0x20-0x3F the carrier is still trying; 0x40 and up it gave up. Only 0x00 is the
 * handset's receipt (MESHSAT-1584).
 */
enum class SmsReport {
    Delivered, CompletedUnconfirmed, Pending, Failed;

    companion object {
        /** A report's status in the PDU format Android names: GSM/UMTS or CDMA; any other is unreadable. */
        fun from(format: String?, status: Int): SmsReport? = when (format ?: "3gpp") {
            "3gpp" -> fromStatus(status)
            "3gpp2" -> fromCdmaStatus(status)
            else -> null
        }

        /**
         * A CDMA report (SmsMessage.getStatus): error class in bits 25-24, status code in bits
         * 23-16 (3GPP2 C.S0015-B 4.5.21; AOSP SmsMessage.getStatus: "2 shifted left by 16 for CDMA
         * indicates the previously sent message was received"). Only class 0 code 2 is the
         * handset's receipt; class 3 is a permanent error; anything else says nothing final.
         */
        fun fromCdmaStatus(status: Int): SmsReport {
            val errorClass = (status ushr 24) and 0x03
            val code = (status ushr 16) and 0xFF
            return when {
                errorClass == 0 && code == 2 -> Delivered
                errorClass == 3 -> Failed
                else -> Pending
            }
        }

        /** A GSM/UMTS report's TP-Status. */
        fun fromStatus(status: Int): SmsReport = when {
            status < 0 -> Pending
            status == 0 -> Delivered
            status < 0x20 -> CompletedUnconfirmed
            status < 0x40 -> Pending
            else -> Failed
        }
    }
}

/** Whose delivery report an SMS send asks for: a chat message, a queued delivery, or both. */
data class SmsReceipt(val messageId: Long = -1L, val deliveryId: Long = -1L)
