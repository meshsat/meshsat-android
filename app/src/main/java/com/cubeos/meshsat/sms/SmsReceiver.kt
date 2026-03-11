package com.cubeos.meshsat.sms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cubeos.meshsat.MainActivity
import com.cubeos.meshsat.MeshSatApp
import com.cubeos.meshsat.R
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
 * Posts notifications for incoming messages.
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
                        wasEncrypted = true
                        rawCiphertext = text

                        if (autoDecrypt) {
                            val convKey = db.conversationKeyDao().getBySender(sender)?.hexKey
                            val keyToUse = convKey?.ifEmpty { null } ?: globalKey

                            if (keyToUse.isNotEmpty()) {
                                try {
                                    decryptedText = AesGcmCrypto.decryptFromBase64(text.trim(), keyToUse)
                                    Log.d("MeshSat", "SMS auto-decrypted from $sender (${if (convKey != null) "conv key" else "global key"})")
                                } catch (e: Exception) {
                                    Log.w("MeshSat", "SMS decrypt failed from $sender: ${e.message}")
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

                    // Post notification
                    postNotification(context, sender, decryptedText)
                }
            } catch (e: Exception) {
                Log.e("MeshSat", "SmsReceiver processing failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postNotification(context: Context, sender: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, MeshSatApp.CHANNEL_MESSAGES)
            .setContentTitle("SMS: $sender")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                notification,
            )
        } catch (_: SecurityException) {}
    }
}
