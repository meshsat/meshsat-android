package com.cubeos.meshsat.sms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cubeos.meshsat.MainActivity
import com.cubeos.meshsat.MeshSatApp
import com.cubeos.meshsat.R
import com.cubeos.meshsat.codec.ProtocolVersion
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.crypto.MsvqscCodebook
import com.cubeos.meshsat.crypto.MsvqscWire
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receives incoming SMS with support for MSVQ-SC decompression and AES-GCM decryption.
 *
 * RX pipeline: SMS → base64 decode → AES-GCM decrypt → detect MSVQ-SC header → codebook decode → text
 * Either layer can be independently present or absent.
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

                // Lazy-load codebook (cached in companion)
                val codebook = getCodebook(context)

                val secureStore = com.cubeos.meshsat.crypto.SecureKeyStore.getInstance(context)

                for ((sender, body) in grouped) {
                    val text = body.toString()
                    val result = processIncoming(
                        text = text,
                        sender = sender,
                        globalKey = globalKey,
                        autoDecrypt = autoDecrypt,
                        codebook = codebook,
                        db = db,
                        secureKeyStore = secureStore,
                    )

                    db.messageDao().insert(
                        Message(
                            transport = "sms",
                            direction = "rx",
                            sender = sender,
                            text = result.displayText,
                            rawText = result.rawText,
                            encrypted = result.wasEncrypted,
                        )
                    )

                    // Relay to Hub via MQTT (MESHSAT-196)
                    relayCallback?.onSmsReceived(
                        sender = sender,
                        text = result.displayText,
                        rawText = text,
                        wasEncrypted = result.wasEncrypted,
                        wasCompressed = result.wasCompressed,
                    )

                    // Auto-forward to configured number if set [MESHSAT-447]
                    val forwardTo = autoForwardTo
                    if (forwardTo.isNotEmpty() && sender != forwardTo) {
                        Log.i(TAG, "Auto-forwarding SMS from $sender to $forwardTo")
                        com.cubeos.meshsat.sms.SmsSender.send(
                            context = context,
                            to = forwardTo,
                            text = result.displayText,
                        )
                    }

                    postNotification(context, sender, result.displayText)
                }
            } catch (e: Exception) {
                Log.e("MeshSat", "SmsReceiver processing failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private data class ProcessResult(
        val displayText: String,
        val rawText: String,
        val wasEncrypted: Boolean,
        val wasCompressed: Boolean,
    )

    /**
     * Process an incoming SMS through the decode pipeline:
     * 1. Try base64 decode
     * 2. Try AES-GCM decrypt (with conversation key or global key)
     * 3. Detect MSVQ-SC wire header → codebook decode
     * 4. Fall through to plain text
     */
    private suspend fun processIncoming(
        text: String,
        sender: String,
        globalKey: String,
        autoDecrypt: Boolean,
        codebook: MsvqscCodebook?,
        db: AppDatabase,
        secureKeyStore: com.cubeos.meshsat.crypto.SecureKeyStore? = null,
    ): ProcessResult {
        // Try to decode as base64 (all encrypted/compressed messages are base64)
        val rawBytes = try {
            Base64.decode(text.trim(), Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }

        if (rawBytes == null || rawBytes.size < 2) {
            // Plain text SMS — not encrypted, not compressed
            Log.d(TAG, "SMS from $sender: plain text (len=${text.length})")
            return ProcessResult(text, "", wasEncrypted = false, wasCompressed = false)
        }

        // Could be: encrypted+compressed, encrypted-only, compressed-only
        var payload = rawBytes
        var wasEncrypted = false

        // Step 0: Strip protocol version byte (if present)
        val versionResult = ProtocolVersion.stripVersionByte(payload)
        ProtocolVersion.logVersionInfo(versionResult.version, sender)
        payload = versionResult.data

        // Step 1: Try AES-GCM decrypt (if looks encrypted and auto-decrypt enabled)
        if (autoDecrypt && AesGcmCrypto.looksEncrypted(text)) {
            // Key resolution order: per-sender → Hub wildcard (sms:*) → global settings key [MESHSAT-447]
            // Keys in Room are SecureKeyStore references ("convkey:X") — resolve via store.
            fun resolveKey(raw: String?): String? {
                if (raw == null || raw.isEmpty()) return null
                if (raw.startsWith("convkey:") && secureKeyStore != null) {
                    return secureKeyStore.get(raw)
                }
                return raw // legacy plaintext key
            }
            val convKey = resolveKey(db.conversationKeyDao().getBySender(sender)?.hexKey)
            val wildcardKey = resolveKey(db.conversationKeyDao().getBySender("*")?.hexKey)
            val keyToUse = convKey?.ifEmpty { null }
                ?: wildcardKey?.ifEmpty { null }
                ?: globalKey

            if (keyToUse.isNotEmpty()) {
                try {
                    payload = AesGcmCrypto.decrypt(payload, keyToUse)
                    wasEncrypted = true
                    val keySource = when {
                        convKey != null && convKey.isNotEmpty() -> "conv key"
                        wildcardKey != null && wildcardKey.isNotEmpty() -> "hub wildcard sms:*"
                        else -> "global key"
                    }
                    Log.d(TAG, "SMS decrypted from $sender ($keySource)")
                } catch (e: Exception) {
                    Log.w(TAG, "SMS decrypt failed from $sender: ${e.message}")
                    // Decrypt failed — might not actually be encrypted
                    // Fall through with original rawBytes
                }
            }
        }

        // Step 2a: Try SMAZ2 decompression (bridge/Hub compress with SMAZ2) [MESHSAT-447]
        if (wasEncrypted || rawBytes.contentEquals(payload).not()) {
            val smaz2Text = com.cubeos.meshsat.codec.Smaz2.decompress(payload)
            if (smaz2Text != null && smaz2Text.all { it.code in 0x20..0x7E || it == '\n' || it == '\r' || it == '\t' }) {
                Log.d(TAG, "SMS SMAZ2 decompressed from $sender: ${payload.size}B → \"$smaz2Text\"")
                return ProcessResult(smaz2Text, text, wasEncrypted, wasCompressed = true)
            }
        }

        // Step 2b: Check if payload is MSVQ-SC wire format
        if (codebook != null && MsvqscWire.looksLikeMsvqsc(payload)) {
            try {
                val decoded = codebook.decode(payload)
                Log.d(TAG, "SMS MSVQ-SC decoded from $sender: ${payload.size}B → \"$decoded\"" +
                        if (wasEncrypted) " [was encrypted]" else "")
                return ProcessResult(decoded, text, wasEncrypted, wasCompressed = true)
            } catch (e: Exception) {
                Log.w(TAG, "MSVQ-SC decode failed: ${e.message}")
            }
        }

        // Step 3: If decrypted but not compressed, return as text
        if (wasEncrypted) {
            val decryptedText = String(payload, Charsets.UTF_8)
            Log.i(TAG, "SMS decrypted result from $sender: \"$decryptedText\" (${payload.size}B)")
            return ProcessResult(decryptedText, text, wasEncrypted = true, wasCompressed = false)
        }

        // Step 4: Not recognized — show as-is
        Log.d(TAG, "SMS from $sender: unrecognized binary (${rawBytes.size}B)")
        return ProcessResult(text, "", wasEncrypted = false, wasCompressed = false)
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

    companion object {
        private const val TAG = "SmsReceiver"
        @Volatile
        private var cachedCodebook: MsvqscCodebook? = null
        private val codebookLock = Any()

        /**
         * Optional relay callback for forwarding received SMS to Hub.
         * Set by GatewayService to publish inbound SMS via MQTT.
         */
        @Volatile
        var relayCallback: SmsRelayCallback? = null

        /** Auto-forward received SMS to this number. Empty = disabled. [MESHSAT-447] */
        @Volatile
        var autoForwardTo: String = ""

        private fun getCodebook(context: Context): MsvqscCodebook? {
            cachedCodebook?.let { return it }
            synchronized(codebookLock) {
                cachedCodebook?.let { return it }
                cachedCodebook = MsvqscCodebook.loadFromAssets(context)
                return cachedCodebook
            }
        }
    }
}
