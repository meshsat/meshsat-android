package net.meshsat.android.sms

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.crypto.MsvqscEncoder
import net.meshsat.android.crypto.MsvqscWire

/**
 * Sends SMS messages with optional MSVQ-SC compression and AES-256-GCM encryption.
 *
 * TX pipeline: text → SMAZ2 or MSVQ-SC (lossy) → the app SMS wire v2 frame naming the codec →
 * AES-GCM encrypt → base64 → SMS (SmsWireV2). Either layer can be independently enabled/disabled.
 */
object SmsSender {

    private const val TAG = "SmsSender"

    /**
     * Send an SMS with optional compression and encryption.
     *
     * @param context Android context
     * @param to Recipient phone number
     * @param text Plaintext message
     * @param encryptionKey AES-256 hex key (null/empty = no encryption)
     * @param msvqscEncoder MSVQ-SC encoder (null = no compression)
     * @param msvqscStages Number of VQ stages (fewer = more compression)
     */
    // The play edition declares no SMS permission, so lint sees these SmsManager calls as
    // unguarded; SmsCapability.manager answers null there and nothing is ever sent (MESHSAT-1335).
    @SuppressLint("MissingPermission")
    suspend fun send(
        context: Context,
        to: String,
        text: String,
        encryptionKey: String? = null,
        smaz2: Boolean = false,
        msvqscEncoder: MsvqscEncoder? = null,
        msvqscStages: Int = 3,
        sentIntent: PendingIntent? = null,
        receipt: SmsReceipt? = null,
    ) {
        // The app SMS wire v2 (SmsWireV2, r40 review R40-R1): the frame names its codec, so the
        // receiver never guesses. SMAZ2 when it gains [MESHSAT-447], else MSVQ-SC (lossy) when
        // set and it encodes, else the text.
        val frame = SmsWireV2.frame(text, smaz2, msvqscEncoder?.let { enc ->
            {
                enc.encode(text, msvqscStages).also { w ->
                    if (w == null) Log.w(TAG, "MSVQ-SC encode failed, sending uncompressed")
                }
            }
        })
        val compressed = frame[0] != SmsWireV2.CODEC_TEXT
        val keyed = !encryptionKey.isNullOrEmpty()

        // A plain SMS (no key, nothing compressed) goes as typed; anything else as base64 of the
        // v2 wire, sealed with AES-256-GCM when a key is set.
        val finalText = if (compressed || keyed) {
            val wire = SmsWireV2.wire(frame, if (keyed) { f -> AesGcmCrypto.encrypt(f, encryptionKey!!) } else null)
            Base64.encodeToString(wire, Base64.NO_WRAP)
        } else {
            text
        }

        Log.d(TAG, "SMS to $to: ${text.length}B text → ${finalText.length} chars SMS" +
                (if (compressed) " [codec ${frame[0]}]" else "") +
                (if (keyed) " [encrypted]" else ""))

        // Step 5: Send via Android SMS API
        val smsManager = SmsCapability.manager(context) ?: run {
            Log.w(TAG, "No SmsManager on this device")
            return
        }
        // The sent intent rides on the last part (MESHSAT-1246); every part asks for its own
        // delivery report, and the message is delivered once all of them say so (MESHSAT-1584).
        val parts = smsManager.divideMessage(finalText)
        val delivered = receipt?.let { SmsStatusReceiver.deliveredIntents(context, it, parts.size) }
        if (parts.size > 1) {
            val sent = ArrayList<PendingIntent?>(List(parts.size) { i -> if (i == parts.size - 1) sentIntent else null })
            smsManager.sendMultipartTextMessage(to, null, parts, sent, delivered)
        } else {
            smsManager.sendTextMessage(to, null, finalText, sentIntent, delivered?.firstOrNull())
        }
    }

    /**
     * Send plain text and wait until the phone's radio reports on every part (MESHSAT-1249, the SOS
     * to emergency contacts): null once the SMS has left the phone, otherwise why not, so the
     * delivery queue tries again. "Left the phone" says nothing about the other phone.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendAndWait(
        context: Context,
        to: String,
        text: String,
        timeoutMs: Long = 60_000L,
        receipt: SmsReceipt? = null,
    ): String? {
        val smsManager = SmsCapability.manager(context) ?: return "This device cannot send SMS"
        val parts = try {
            smsManager.divideMessage(text)
        } catch (e: Exception) {
            return "SMS could not be prepared: ${e.message}"
        }
        if (parts.isEmpty()) return null
        val action = "net.meshsat.android.SMS_SENT." + UUID.randomUUID()
        val results = Channel<Int>(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                sent += PendingIntent.getBroadcast(
                    context, i, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            }
            // The carrier's delivery report, when asked for, on every part (MESHSAT-1246, 1584).
            val delivered = receipt?.let { SmsStatusReceiver.deliveredIntents(context, it, parts.size) }
            if (parts.size == 1) smsManager.sendTextMessage(to, null, parts[0], sent[0], delivered?.firstOrNull())
            else smsManager.sendMultipartTextMessage(to, null, parts, sent, delivered)
            repeat(parts.size) {
                val code = withTimeoutOrNull(timeoutMs) { results.receive() }
                    ?: return "The phone did not say whether the SMS left"
                if (code != Activity.RESULT_OK) return sentFailure(code)
            }
            Log.d(TAG, "SMS to $to left the phone (${parts.size} part(s))")
            return null
        } catch (e: SecurityException) {
            return "SMS is not allowed: allow it in Setup, SMS"
        } catch (e: Exception) {
            return "SMS failed: ${e.message}"
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    private fun sentFailure(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "The phone's mobile radio is off (flight mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "The SMS could not be encoded"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "The phone is sending too many SMS; it will try again"
        else -> "The phone could not send the SMS (code $code)"
    }
}
