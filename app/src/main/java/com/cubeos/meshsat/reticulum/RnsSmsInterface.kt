package com.cubeos.meshsat.reticulum

import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * Reticulum interface over native Android SMS.
 *
 * Encapsulates Reticulum packets as base64-encoded SMS messages with a
 * magic prefix to distinguish from normal SMS traffic. Fragmentation
 * is handled for packets exceeding the single-SMS binary capacity.
 *
 * Single SMS capacity: 160 GSM-7 chars → ~120 bytes binary (base64).
 * Multipart SMS extends this via Android SmsManager.divideMessage().
 *
 * Cost: ~$0.01/message (carrier dependent).
 * Latency: 1-5s typical.
 *
 * [MESHSAT-215]
 */
class RnsSmsInterface(
    private val context: Context,
    private val phoneNumber: () -> String?,  // destination phone number from settings
    override val interfaceId: String = "sms_0",
) : RnsInterface {

    override val name: String = "Native SMS"
    override val mtu: Int = SMS_BINARY_MTU
    override val costCents: Int = 1
    override val latencyMs: Int = 3000

    // SMS is always online if phone number is configured
    override val isOnline: Boolean
        get() = phoneNumber() != null

    private var receiveCallback: RnsReceiveCallback? = null

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    /**
     * Send a Reticulum packet via SMS.
     *
     * The packet is base64-encoded with a magic prefix (RNS:) and sent
     * to the configured phone number. Android SmsManager handles
     * multipart splitting if needed.
     */
    override suspend fun send(packet: ByteArray): String? {
        val dest = phoneNumber() ?: return "no SMS destination configured"
        if (packet.size > mtu) return "packet exceeds SMS MTU ($mtu bytes)"

        return try {
            val encoded = SMS_PREFIX + Base64.encodeToString(packet, Base64.NO_WRAP)
            val smsManager = android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(encoded)
            smsManager.sendMultipartTextMessage(dest, null, parts, null, null)
            Log.d(TAG, "RNS packet sent via SMS: ${packet.size}B → ${encoded.length} chars")
            null
        } catch (e: Exception) {
            Log.w(TAG, "SMS send failed: ${e.message}")
            e.message ?: "SMS send failed"
        }
    }

    /**
     * Process an incoming SMS that may contain a Reticulum packet.
     *
     * Called from SmsReceiver when an SMS with the RNS: prefix is detected.
     * This is a push-based interface — the SmsReceiver broadcasts to us.
     *
     * @param smsBody The full SMS body text
     * @return true if this was a Reticulum packet, false if normal SMS
     */
    fun processIncomingSms(smsBody: String): Boolean {
        if (!smsBody.startsWith(SMS_PREFIX)) return false

        return try {
            val base64Part = smsBody.removePrefix(SMS_PREFIX)
            val packet = Base64.decode(base64Part, Base64.NO_WRAP)
            receiveCallback?.onReceive(interfaceId, packet)
            Log.d(TAG, "RNS packet received via SMS: ${packet.size}B")
            true
        } catch (e: Exception) {
            Log.d(TAG, "SMS RNS decode failed: ${e.message}")
            false
        }
    }

    override suspend fun start() {
        // SMS is always listening via SmsReceiver BroadcastReceiver
    }

    override suspend fun stop() {
        // SMS lifecycle managed by Android system
    }

    companion object {
        private const val TAG = "RnsSmsInterface"

        /**
         * Magic prefix for Reticulum packets in SMS.
         * Distinguishes RNS traffic from normal SMS messages.
         */
        const val SMS_PREFIX = "RNS:"

        /**
         * Binary MTU for SMS transport.
         *
         * Single SMS = 160 GSM-7 chars.
         * "RNS:" prefix = 4 chars.
         * Remaining = 156 chars for base64.
         * Base64 ratio = 3/4 → 156 * 3/4 = 117 bytes.
         *
         * With multipart SMS (up to ~7 parts = 1120 chars):
         * (1120 - 4) * 3/4 = 837 bytes.
         *
         * Use single-SMS capacity as MTU; multipart for fragmented packets.
         */
        const val SMS_BINARY_MTU = 117

        /**
         * Extended MTU with multipart SMS (up to 7 parts).
         */
        const val SMS_MULTIPART_MTU = 837
    }
}
