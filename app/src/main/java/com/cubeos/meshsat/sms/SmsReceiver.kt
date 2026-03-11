package com.cubeos.meshsat.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receives incoming SMS and auto-decrypts MeshSat encrypted messages.
 * Stores all received SMS in the local database for the message feed.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multipart SMS by sender
        val grouped = mutableMapOf<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: "unknown"
            grouped.getOrPut(sender) { StringBuilder() }.append(msg.displayMessageBody ?: "")
        }

        val db = AppDatabase.getInstance(context)
        val settings = SettingsRepository(context)

        CoroutineScope(Dispatchers.IO).launch {
            val key = settings.encryptionKey.first()
            val autoDecrypt = settings.autoDecryptSms.first()

            for ((sender, body) in grouped) {
                val text = body.toString()
                var decryptedText = text
                var wasEncrypted = false

                // Try to decrypt if it looks like a MeshSat encrypted message
                if (autoDecrypt && key.isNotEmpty() && AesGcmCrypto.looksEncrypted(text)) {
                    try {
                        decryptedText = AesGcmCrypto.decryptFromBase64(text.trim(), key)
                        wasEncrypted = true
                        Log.d("MeshSat", "SMS auto-decrypted from $sender")
                    } catch (e: Exception) {
                        Log.w("MeshSat", "SMS decrypt failed from $sender: ${e.message}")
                        // Store as encrypted but undecrypted — user can manually try in Crypto tab
                        wasEncrypted = false
                        // Keep original text
                    }
                } else {
                    if (key.isEmpty()) Log.d("MeshSat", "SMS received but no encryption key configured")
                    else if (!autoDecrypt) Log.d("MeshSat", "SMS received but auto-decrypt disabled")
                    else Log.d("MeshSat", "SMS from $sender does not look encrypted (len=${text.length})")
                }

                db.messageDao().insert(
                    Message(
                        transport = "sms",
                        direction = "rx",
                        sender = sender,
                        text = decryptedText,
                        rawText = if (wasEncrypted) text else "",
                        encrypted = wasEncrypted,
                    )
                )
            }
        }
    }
}
