package com.cubeos.meshsat.sms

import android.content.Context
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.crypto.MsvqscEncoder
import com.cubeos.meshsat.crypto.MsvqscWire

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
        msvqscEncoder: MsvqscEncoder? = null,
        msvqscStages: Int = 3,
    ) {
        var payload: ByteArray = text.toByteArray(Charsets.UTF_8)
        var compressed = false

        // Step 1: MSVQ-SC lossy compression (if enabled)
        if (msvqscEncoder != null) {
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

        // Step 3: Base64 encode for SMS transport (if binary payload)
        val finalText = if (compressed || !encryptionKey.isNullOrEmpty()) {
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } else {
            text
        }

        Log.d(TAG, "SMS to $to: ${text.length}B text → ${finalText.length} chars SMS" +
                (if (compressed) " [msvqsc]" else "") +
                (if (!encryptionKey.isNullOrEmpty()) " [encrypted]" else ""))

        // Step 4: Send via Android SMS API
        val smsManager = context.getSystemService(SmsManager::class.java)
        if (finalText.length > 160) {
            val parts = smsManager.divideMessage(finalText)
            smsManager.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(to, null, finalText, null, null)
        }
    }
}
