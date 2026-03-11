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
 *
 * Uses goAsync() to keep the BroadcastReceiver alive while the coroutine
 * reads settings and writes to the database.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            Telephony.Sms.Intents.SMS_DELIVER_ACTION,
        )
        if (intent.action !in validActions) return

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

        // goAsync() keeps the receiver alive while coroutine runs (up to ~30s)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val globalKey = settings.encryptionKey.first()
                val autoDecrypt = settings.autoDecryptSms.first()

                for ((sender, body) in grouped) {
                    val text = body.toString()
                    var decryptedText = text
                    var wasEncrypted = false
                    var rawCiphertext = ""

                    val looksEnc = AesGcmCrypto.looksEncrypted(text)

                    if (looksEnc) {
                        // Mark as encrypted regardless of whether decryption succeeds
                        wasEncrypted = true
                        rawCiphertext = text

                        if (autoDecrypt) {
                            // Try per-conversation key first, then global key
                            val convKey = db.conversationKeyDao().getBySender(sender)?.hexKey
                            val keyToUse = convKey?.ifEmpty { null } ?: globalKey

                            if (keyToUse.isNotEmpty()) {
                                try {
                                    decryptedText = AesGcmCrypto.decryptFromBase64(text.trim(), keyToUse)
                                    Log.d("MeshSat", "SMS auto-decrypted from $sender (${if (convKey != null) "conv key" else "global key"})")
                                } catch (e: Exception) {
                                    Log.w("MeshSat", "SMS decrypt failed from $sender: ${e.message}")
                                    // Keep original text — user can manually decrypt in Crypto tab
                                }
                            } else {
                                Log.d("MeshSat", "SMS looks encrypted from $sender but no key configured")
                            }
                        } else {
                            Log.d("MeshSat", "SMS looks encrypted from $sender but auto-decrypt disabled")
                        }
                    } else {
                        Log.d("MeshSat", "SMS from $sender plain text (len=${text.length})")
                    }

                    db.messageDao().insert(
                        Message(
                            transport = "sms",
                            direction = "rx",
                            sender = sender,
                            text = decryptedText,
                            rawText = rawCiphertext,
                            encrypted = wasEncrypted,
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("MeshSat", "SmsReceiver processing failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
