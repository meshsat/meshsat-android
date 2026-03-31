package com.cubeos.meshsat.sms

import android.content.Context
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import com.cubeos.meshsat.codec.ProtocolVersion
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
        smaz2: Boolean = false,
        msvqscEncoder: MsvqscEncoder? = null,
        msvqscStages: Int = 3,
    ) {
        var payload: ByteArray = text.toByteArray(Charsets.UTF_8)
        var compressed = false

        // Step 0: SMAZ2 lossless compression (if enabled) [MESHSAT-447]
        if (smaz2) {
            val smaz2Bytes = com.cubeos.meshsat.codec.Smaz2.compress(text)
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
        val smsManager = context.getSystemService(SmsManager::class.java)
        if (finalText.length > 160) {
            val parts = smsManager.divideMessage(finalText)
            smsManager.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(to, null, finalText, null, null)
        }
    }
}
