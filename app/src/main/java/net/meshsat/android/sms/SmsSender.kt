package net.meshsat.android.sms

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
import net.meshsat.android.codec.ProtocolVersion
import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.crypto.MsvqscEncoder
import net.meshsat.android.crypto.MsvqscWire

/**
 * Sends SMS messages with optional MSVQ-SC compression and AES-256-GCM encryption.
 *
 * TX pipeline: text → MSVQ-SC encode (lossy) → AES-GCM encrypt → base64 → SMS
 * Either layer can be independently enabled/disabled.
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
    fun send(
        context: Context,
        to: String,
        text: String,
        encryptionKey: String? = null,
        smaz2: Boolean = false,
        msvqscEncoder: MsvqscEncoder? = null,
        msvqscStages: Int = 3,
        sentIntent: PendingIntent? = null,
        deliveryIntent: PendingIntent? = null,
    ) {
        var payload: ByteArray = text.toByteArray(Charsets.UTF_8)
        var compressed = false

        // Step 0: SMAZ2 lossless compression (if enabled) [MESHSAT-447]
        if (smaz2) {
            val smaz2Bytes = net.meshsat.android.codec.Smaz2.compress(text)
            if (smaz2Bytes.size < payload.size) {
                Log.d(TAG, "SMAZ2: ${payload.size}B → ${smaz2Bytes.size}B")
                payload = smaz2Bytes
                compressed = true
            } else {
                Log.d(TAG, "SMAZ2: no gain (${payload.size}B → ${smaz2Bytes.size}B), skipping")
            }
        }

        // Step 1: MSVQ-SC lossy compression (if enabled, overrides SMAZ2)
        if (msvqscEncoder != null && !compressed) {
            val wire = msvqscEncoder.encode(text, msvqscStages)
            if (wire != null) {
                Log.d(TAG, "MSVQ-SC: ${payload.size}B → ${wire.size}B ($msvqscStages stages)")
                payload = wire
                compressed = true
            } else {
                Log.w(TAG, "MSVQ-SC encode failed, sending uncompressed")
            }
        }

        // Step 2: AES-GCM encryption (if key provided)
        if (!encryptionKey.isNullOrEmpty()) {
            payload = AesGcmCrypto.encrypt(payload, encryptionKey)
            Log.d(TAG, "Encrypted: ${payload.size}B")
        }

        // Step 3: Prepend protocol version byte (if payload was transformed)
        if (compressed || !encryptionKey.isNullOrEmpty()) {
            payload = ProtocolVersion.prependVersionByte(payload)
        }

        // Step 4: Base64 encode for SMS transport (if binary payload)
        val finalText = if (compressed || !encryptionKey.isNullOrEmpty()) {
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } else {
            text
        }

        Log.d(TAG, "SMS to $to: ${text.length}B text → ${finalText.length} chars SMS" +
                (if (compressed) " [msvqsc]" else "") +
                (if (!encryptionKey.isNullOrEmpty()) " [encrypted]" else ""))

        // Step 5: Send via Android SMS API
        val smsManager = SmsCapability.manager(context) ?: run {
            Log.w(TAG, "No SmsManager on this device")
            return
        }
        // The sent and delivery intents ride on the last part: its result stands for the message
        // (MESHSAT-1246).
        val parts = smsManager.divideMessage(finalText)
        if (parts.size > 1) {
            val sent = ArrayList<PendingIntent?>(List(parts.size) { i -> if (i == parts.size - 1) sentIntent else null })
            val delivered = ArrayList<PendingIntent?>(List(parts.size) { i -> if (i == parts.size - 1) deliveryIntent else null })
            smsManager.sendMultipartTextMessage(to, null, parts, sent, delivered)
        } else {
            smsManager.sendTextMessage(to, null, finalText, sentIntent, deliveryIntent)
        }
    }

    /**
     * Send plain text and wait until the phone's radio reports on every part (MESHSAT-1249, the SOS
     * to emergency contacts): null once the SMS has left the phone, otherwise why not, so the
     * delivery queue tries again. "Left the phone" says nothing about the other phone.
     */
    suspend fun sendAndWait(
        context: Context,
        to: String,
        text: String,
        timeoutMs: Long = 60_000L,
        deliveryIntent: PendingIntent? = null,
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
            // The carrier's delivery report, when asked for, rides on the last part (MESHSAT-1246).
            val delivered = ArrayList<PendingIntent?>(List(parts.size) { i -> if (i == parts.size - 1) deliveryIntent else null })
            if (parts.size == 1) smsManager.sendTextMessage(to, null, parts[0], sent[0], deliveryIntent)
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
