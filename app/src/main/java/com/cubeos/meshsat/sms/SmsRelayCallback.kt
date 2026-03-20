package com.cubeos.meshsat.sms

/**
 * Callback for relaying received SMS messages to Hub.
 *
 * Set on [SmsReceiver.relayCallback] by GatewayService to forward
 * inbound SMS to Hub via MQTT (MESHSAT-196).
 */
fun interface SmsRelayCallback {
    /**
     * Called when an SMS is received and processed.
     *
     * @param sender Phone number of the sender
     * @param text Decoded/decrypted display text
     * @param rawText Original raw SMS body (before decoding)
     * @param wasEncrypted Whether AES-GCM decryption was applied
     * @param wasCompressed Whether MSVQ-SC decompression was applied
     */
    fun onSmsReceived(
        sender: String,
        text: String,
        rawText: String,
        wasEncrypted: Boolean,
        wasCompressed: Boolean,
    )
}
