package com.cubeos.meshsat.sms

import android.content.Context
import android.telephony.SmsManager
import com.cubeos.meshsat.crypto.AesGcmCrypto

/**
 * Sends SMS messages, optionally encrypted with AES-256-GCM.
 */
object SmsSender {

    fun send(context: Context, to: String, text: String, encryptionKey: String? = null) {
        val finalText = if (!encryptionKey.isNullOrEmpty()) {
            AesGcmCrypto.encryptToBase64(text, encryptionKey)
        } else {
            text
        }

        val smsManager = context.getSystemService(SmsManager::class.java)

        if (finalText.length > 160) {
            val parts = smsManager.divideMessage(finalText)
            smsManager.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(to, null, finalText, null, null)
        }
    }
}
