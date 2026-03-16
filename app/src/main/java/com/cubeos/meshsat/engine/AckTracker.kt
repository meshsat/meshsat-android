package com.cubeos.meshsat.engine

import android.util.Log
import com.cubeos.meshsat.data.MessageDeliveryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * AckTracker monitors pending ACKs and handles timeouts.
 * Port of Go's database.GetPendingAcks / SetDeliveryAck pattern.
 *
 * For QoS >= 1 deliveries:
 * 1. After successful send, delivery is marked ack_status='pending'
 * 2. AckTracker periodically checks for timed-out pending ACKs
 * 3. Timed-out deliveries get ack_status='timeout' and can be retried
 * 4. Incoming ACKs are correlated by (channel, seq_num) and promote to 'delivered'
 *
 * Per-channel timeout is configurable (default: 120s for satellite, 30s for others).
 */
class AckTracker(
    private val deliveryDao: MessageDeliveryDao,
    private val scope: CoroutineScope,
    private val defaultTimeout: Duration = DEFAULT_TIMEOUT,
) {
    private var checkerJob: Job? = null
    private val channelTimeouts = mutableMapOf<String, Duration>()

    /** Set a custom ACK timeout for a specific channel type. */
    fun setChannelTimeout(channelType: String, timeout: Duration) {
        channelTimeouts[channelType] = timeout
    }

    /** Start the periodic ACK timeout checker. */
    fun start() {
        checkerJob?.cancel()
        checkerJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL)
                checkTimeouts()
            }
        }
        Log.i(TAG, "ACK tracker started (check interval=${CHECK_INTERVAL.inWholeSeconds}s)")
    }

    /** Stop the ACK timeout checker. */
    fun stop() {
        checkerJob?.cancel()
        checkerJob = null
    }

    /**
     * Process an incoming ACK for a delivery.
     * Looks up the delivery by channel + sequence number and marks it as acked/delivered.
     *
     * @return true if the ACK was matched to a delivery, false otherwise.
     */
    suspend fun processAck(channel: String, seqNum: Long, positive: Boolean = true): Boolean {
        val delivery = deliveryDao.getByChannelAndSeq(channel, seqNum)
        if (delivery == null) {
            Log.d(TAG, "No delivery found for ACK channel=$channel seq=$seqNum")
            return false
        }

        if (delivery.ackStatus != "pending") {
            Log.d(TAG, "Delivery ${delivery.id} ack_status=${delivery.ackStatus}, ignoring ACK")
            return false
        }

        if (positive) {
            deliveryDao.setAcked(delivery.id)
            Log.i(TAG, "Delivery ${delivery.id} ACKed → delivered (channel=$channel seq=$seqNum)")
        } else {
            deliveryDao.setNacked(delivery.id)
            Log.w(TAG, "Delivery ${delivery.id} NACKed (channel=$channel seq=$seqNum)")
        }
        return true
    }

    /**
     * Check all channels for timed-out pending ACKs.
     */
    private suspend fun checkTimeouts() {
        // Check each known channel type
        val channels = listOf("mesh_0", "iridium_0", "sms_0")
        for (channel in channels) {
            val timeout = getTimeout(channel)
            val cutoff = System.currentTimeMillis() - timeout.inWholeMilliseconds
            val timedOut = deliveryDao.timeoutPendingAcks(cutoff)
            if (timedOut > 0) {
                Log.w(TAG, "$channel: $timedOut ACKs timed out (timeout=${timeout.inWholeSeconds}s)")
            }
        }
    }

    private fun getTimeout(channel: String): Duration {
        val channelType = channel.substringBeforeLast('_')
        return channelTimeouts[channelType] ?: when (channelType) {
            "iridium" -> SATELLITE_TIMEOUT
            else -> defaultTimeout
        }
    }

    companion object {
        private const val TAG = "AckTracker"
        private val CHECK_INTERVAL = 30.seconds
        private val DEFAULT_TIMEOUT = 30.seconds
        private val SATELLITE_TIMEOUT = 2.minutes
    }
}
