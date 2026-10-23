package net.meshsat.android.sms

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
import net.meshsat.android.MainActivity
import net.meshsat.android.MeshSatApp
import net.meshsat.android.R
import net.meshsat.android.codec.ProtocolVersion
import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.crypto.MsvqscCodebook
import net.meshsat.android.crypto.MsvqscWire
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.Message
import net.meshsat.android.data.SettingsRepository
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

                val secureStore = net.meshsat.android.crypto.SecureKeyStore.getInstance(context)

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

                    postNotification(context, sender, result.displayText)
                }
            } catch (e: Exception) {
                Log.e("MeshSat", "SmsReceiver processing failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal data class ProcessResult(
        val displayText: String,
        val rawText: String,
        val wasEncrypted: Boolean,
        val wasCompressed: Boolean,
    )

    /**
     * Process an incoming SMS through the decode pipeline:
     * 1. Try base64 decode
     * 2. The app SMS wire v2 (SmsWireV2): read by the codec its frame names, or shown as it came
     * 3. Version 1: try AES-GCM decrypt (with conversation key or global key)
     * 4. Version 1: SMAZ2, then the MSVQ-SC wire header → codebook decode (guessed)
     * 5. Fall through to plain text
     */
    private suspend fun processIncoming(
        text: String,
        sender: String,
        globalKey: String,
        autoDecrypt: Boolean,
        codebook: MsvqscCodebook?,
        db: AppDatabase,
        secureKeyStore: net.meshsat.android.crypto.SecureKeyStore? = null,
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

        // Key resolution order: per-sender → Hub wildcard (sms:*) → global settings key [MESHSAT-447]
        // Keys in Room are SecureKeyStore references ("convkey:X") — resolve via store.
        fun resolveKey(raw: String?): String? {
            if (raw == null || raw.isEmpty()) return null
            if (raw.startsWith("convkey:") && secureKeyStore != null) {
                return secureKeyStore.get(raw)
            }
            return raw // legacy plaintext key
        }
        val convKey = if (autoDecrypt) resolveKey(db.conversationKeyDao().getBySender(sender)?.hexKey) else null
        val wildcardKey = if (autoDecrypt) resolveKey(db.conversationKeyDao().getBySender("*")?.hexKey) else null
        val keyToUse = if (!autoDecrypt) "" else convKey?.ifEmpty { null } ?: wildcardKey?.ifEmpty { null } ?: globalKey
        val keySource = when {
            convKey != null && convKey.isNotEmpty() -> "conv key"
            wildcardKey != null && wildcardKey.isNotEmpty() -> "hub wildcard sms:*"
            else -> "global key"
        }

        return decode(text, sender, rawBytes, keyToUse, keySource, autoDecrypt, codebook)
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

        /**
         * processIncoming's steps once the SMS is decoded from base64 ([rawBytes]) and the key is
         * resolved ([keyToUse], empty for none; [keySource] names it for the log).
         */
        internal fun decode(
            text: String,
            sender: String,
            rawBytes: ByteArray,
            keyToUse: String,
            keySource: String,
            autoDecrypt: Boolean,
            codebook: MsvqscCodebook?,
        ): ProcessResult {
            // The app SMS wire v2 (SmsWireV2, r40 review R40-R1): the frame names its codec, so
            // nothing is guessed. What the key opens is read first: a sealed v2 frame here, version 1
            // or unversioned below. Only then a clear v2 frame, which nothing authenticates: an old
            // sender's random nonce, or a Bridge chain's, can start 02 00 with a rest that reads, and
            // the key that opens it must win (r41 review V2). A frame whose mode or codec this app
            // does not read, or that no key opens, is shown as it came.
            val v2 = SmsWireV2.read(
                rawBytes,
                open = { p ->
                    if (keyToUse.isEmpty()) null else try { AesGcmCrypto.decrypt(p, keyToUse) } catch (_: Exception) { null }
                },
                msvqsc = codebook?.let { cb -> { b: ByteArray -> try { cb.decode(b) } catch (_: Exception) { null } } },
            )
            if (v2 is SmsWireV2.Read.Text && v2.encrypted) {
                Log.d(TAG, "SMS from $sender: app SMS wire v2, codec ${v2.codec} ($keySource)")
                return ProcessResult(v2.text, text, wasEncrypted = true, wasCompressed = v2.codec != SmsWireV2.CODEC_TEXT)
            }

            // Version 1 or unversioned: encrypted+compressed, encrypted-only, compressed-only, guessed.
            var payload = rawBytes
            var wasEncrypted = false

            // Step 0: Strip protocol version byte (if present)
            val versionResult = ProtocolVersion.stripVersionByte(payload)
            ProtocolVersion.logVersionInfo(versionResult.version, sender)
            payload = versionResult.data

            // Step 1: Try AES-GCM decrypt (if looks encrypted and auto-decrypt enabled)
            if (autoDecrypt && looksSealed(text, rawBytes) && keyToUse.isNotEmpty()) {
                try {
                    payload = AesGcmCrypto.decrypt(payload, keyToUse)
                    wasEncrypted = true
                    Log.d(TAG, "SMS decrypted from $sender ($keySource)")
                } catch (e: Exception) {
                    Log.w(TAG, "SMS decrypt failed from $sender: ${e.message}")
                    // Decrypt failed — might not actually be encrypted
                    // Fall through with original rawBytes
                }
            }
            if (v2 is SmsWireV2.Read.Text && !wasEncrypted) {
                Log.d(TAG, "SMS from $sender: app SMS wire v2, clear, codec ${v2.codec}")
                return ProcessResult(v2.text, text, wasEncrypted = false, wasCompressed = v2.codec != SmsWireV2.CODEC_TEXT)
            }
            if (v2 is SmsWireV2.Read.Kept && !wasEncrypted) {
                Log.w(TAG, "SMS from $sender: app SMS wire v2 this app does not read (or no key opens it), shown as it came")
                return ProcessResult(text, "", wasEncrypted = v2.encrypted, wasCompressed = false)
            }

            // Step 2a: Try SMAZ2 decompression (bridge/Hub compress with SMAZ2) [MESHSAT-447]
            if (wasEncrypted || rawBytes.contentEquals(payload).not()) {
                val decoded = smaz2Text(payload)
                if (decoded != null) {
                    Log.d(TAG, "SMS SMAZ2 decompressed from $sender: ${payload.size}B → \"$decoded\"")
                    return ProcessResult(decoded, text, wasEncrypted, wasCompressed = true)
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

        /** AesGcmCrypto.looksEncrypted on the bytes already decoded: a nonce and a tag at least. */
        private fun looksSealed(text: String, rawBytes: ByteArray): Boolean = text.length >= 24 && rawBytes.size > 12 + 16

        /**
         * [payload] as SMAZ2 text, or null when what it decodes to is not readable text: the
         * payload may never have been compressed. Readable is printable Unicode plus tab, CR and
         * LF, so Greek and other non-English text count; until MESHSAT-1625 only ASCII did, and a
         * compressed Greek SMS was shown as its raw bytes.
         */
        internal fun smaz2Text(payload: ByteArray): String? {
            // A payload that is itself readable UTF-8 with a character above ASCII is the text,
            // never SMAZ2: Greek, accented or emoji text mostly goes out uncompressed, and read as
            // SMAZ2 each of its bytes above 0x7F is a bigram whose letters compress back to the
            // same bytes, so the round trip below cannot tell a word like "Καλημέρα". A compressed
            // payload is almost never such text: its verbatim counts and word codes are control
            // bytes, its bigram codes mostly stray bytes above 0x7F. [MESHSAT-1646]
            if (isPlainNonAscii(payload)) return null
            val text = net.meshsat.android.codec.Smaz2.decompress(payload) ?: return null
            if (!text.all { it == '\n' || it == '\r' || it == '\t' || (it.code >= 0x20 && it.code != 0x7F && it.code !in 0x80..0x9F && it != '\uFFFD') }) return null
            // Compressed only if compressing the text again gives these very bytes: every sender
            // (both apps, the Bridge, the Hub) compresses byte for byte alike and only when it
            // gains. Read as SMAZ2, an uncompressed payload turns each byte above 0x7F into a
            // bigram, so an encrypted Greek SMS, never compressed, came out as ASCII gibberish
            // that passed the readable check. [MESHSAT-1646]
            return text.takeIf { net.meshsat.android.codec.Smaz2.compress(it).contentEquals(payload) }
        }

        private fun isPlainNonAscii(payload: ByteArray): Boolean {
            if (payload.none { it < 0 }) return false
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val text = try { decoder.decode(java.nio.ByteBuffer.wrap(payload)).toString() } catch (_: java.nio.charset.CharacterCodingException) { return false }
            return text.all { it == '\n' || it == '\r' || it == '\t' || (it.code >= 0x20 && it.code != 0x7F && it.code !in 0x80..0x9F && it != '\uFFFD') }
        }
        @Volatile
        private var cachedCodebook: MsvqscCodebook? = null
        private val codebookLock = Any()

        /**
         * Optional relay callback for forwarding received SMS to Hub.
         * Set by GatewayService to publish inbound SMS via MQTT.
         */
        @Volatile
        var relayCallback: SmsRelayCallback? = null

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
