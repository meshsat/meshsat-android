package com.cubeos.meshsat.aprs

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * APRS directed message tracker with ACK/REJ and retry (MESHSAT-232).
 *
 * Manages outbound APRS messages: assigns sequential message IDs,
 * tracks delivery status, retries unacknowledged messages up to 3 times
 * at 30-second intervals (per APRS spec), and handles inbound ACK/REJ.
 */
class AprsMessageTracker(
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AprsMessageTracker"
        const val MAX_RETRIES = 3
        const val RETRY_INTERVAL_MS = 30_000L
    }

    /** Callback to transmit a message. */
    var onSend: ((to: String, text: String, msgId: String) -> Unit)? = null

    /** Callback when delivery status changes. */
    var onStatusChange: ((msgId: String, status: DeliveryStatus) -> Unit)? = null

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, PendingMessage>()

    enum class DeliveryStatus { PENDING, ACKED, REJECTED, FAILED }

    data class PendingMessage(
        val to: String,
        val text: String,
        val msgId: String,
        var retries: Int = 0,
        var status: DeliveryStatus = DeliveryStatus.PENDING,
        var retryJob: Job? = null,
    )

    /**
     * Send a directed message with ACK tracking.
     * @return The assigned message ID.
     */
    fun send(to: String, text: String): String {
        val msgId = nextId.getAndIncrement().toString()
        val msg = PendingMessage(to = to, text = text, msgId = msgId)
        pending[msgId] = msg

        // First send
        onSend?.invoke(to, text, msgId)

        // Start retry timer
        msg.retryJob = scope.launch {
            repeat(MAX_RETRIES) { attempt ->
                delay(RETRY_INTERVAL_MS)
                val current = pending[msgId] ?: return@launch
                if (current.status != DeliveryStatus.PENDING) return@launch

                current.retries = attempt + 1
                Log.d(TAG, "Retry ${attempt + 1}/$MAX_RETRIES for msg $msgId to $to")
                onSend?.invoke(to, text, msgId)
            }

            // All retries exhausted
            val current = pending[msgId] ?: return@launch
            if (current.status == DeliveryStatus.PENDING) {
                current.status = DeliveryStatus.FAILED
                Log.w(TAG, "Message $msgId to $to failed after $MAX_RETRIES retries")
                onStatusChange?.invoke(msgId, DeliveryStatus.FAILED)
            }
        }

        return msgId
    }

    /**
     * Handle an inbound ACK for a message we sent.
     */
    fun handleAck(msgId: String) {
        val msg = pending[msgId] ?: return
        msg.status = DeliveryStatus.ACKED
        msg.retryJob?.cancel()
        Log.i(TAG, "ACK received for message $msgId to ${msg.to}")
        onStatusChange?.invoke(msgId, DeliveryStatus.ACKED)
    }

    /**
     * Handle an inbound REJ for a message we sent.
     */
    fun handleRej(msgId: String) {
        val msg = pending[msgId] ?: return
        msg.status = DeliveryStatus.REJECTED
        msg.retryJob?.cancel()
        Log.w(TAG, "REJ received for message $msgId to ${msg.to}")
        onStatusChange?.invoke(msgId, DeliveryStatus.REJECTED)
    }

    /**
     * Process an inbound APRS packet for ACK/REJ handling.
     * Returns true if the packet was an ACK/REJ we handled.
     */
    fun processInbound(pkt: AprsPacket): Boolean {
        if (pkt.dataType != ':') return false
        val msg = pkt.message

        // Check for ACK: "ackNNN"
        if (msg.startsWith("ack")) {
            val ackId = msg.substring(3).trim()
            if (ackId.isNotEmpty() && pending.containsKey(ackId)) {
                handleAck(ackId)
                return true
            }
        }

        // Check for REJ: "rejNNN"
        if (msg.startsWith("rej")) {
            val rejId = msg.substring(3).trim()
            if (rejId.isNotEmpty() && pending.containsKey(rejId)) {
                handleRej(rejId)
                return true
            }
        }

        return false
    }

    /** Get the status of a pending message. */
    fun getStatus(msgId: String): DeliveryStatus? = pending[msgId]?.status

    /** Get all pending messages. */
    fun getPending(): List<PendingMessage> = pending.values.filter {
        it.status == DeliveryStatus.PENDING
    }

    /** Cancel all pending retries. */
    fun cancelAll() {
        pending.values.forEach { it.retryJob?.cancel() }
        pending.clear()
    }
}
