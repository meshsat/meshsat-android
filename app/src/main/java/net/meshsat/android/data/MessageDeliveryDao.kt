package net.meshsat.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDeliveryDao {

    @Insert
    suspend fun insert(delivery: MessageDeliveryEntity): Long

    @Query("SELECT * FROM message_deliveries WHERE id = :id")
    suspend fun getById(id: Long): MessageDeliveryEntity?

    /** What the worker may send now. Never a delivery the far end has confirmed (FE-R4, below). */
    @Query("""
        SELECT * FROM message_deliveries
        WHERE channel = :channel AND status IN ('queued', 'retry')
          AND (next_retry IS NULL OR next_retry <= :now)
          AND (priority = 0 OR expires_at IS NULL OR expires_at > :now)
          AND COALESCE(ack_status, '') != 'acked'
        ORDER BY priority ASC, created_at ASC
        LIMIT :limit
    """)
    suspend fun getPending(channel: String, now: Long, limit: Int = 10): List<MessageDeliveryEntity>

    /** The LXMF ledger's state (GatewayService); the delivery worker writes through the guarded calls below. */
    @Query("UPDATE message_deliveries SET status = :status, last_error = :lastError, updated_at = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, lastError: String = "", now: Long = System.currentTimeMillis())

    // --- The delivery worker's writes (review 2026-10-18 23:31, FE-R4, MESHSAT-1584) ---
    //
    // A confirmation (an SMS delivery report, an ACK, the Hub's satellite receipt) can commit at any
    // moment while a send is in flight, from another thread. The worker therefore takes a row in one
    // statement ([claimForSend]) and every later write of that send matches only a row that is still
    // 'sending'. Every confirmation moves the row out of 'sending' in its own statement ([setAcked],
    // [markAckedBySatRef]), so the worker's later writes find nothing to change and a confirmed
    // delivery is never set back to sent, pending, retry or dead. No transaction is held while a send
    // waits for the modem or the carrier.

    /**
     * Take a waiting delivery for one send. Returns 0 when it is no longer waiting (confirmed,
     * cancelled, held or taken since it was read); then nothing is sent.
     */
    @Query("""
        UPDATE message_deliveries SET status = 'sending', updated_at = :now
        WHERE id = :id AND status IN ('queued', 'retry') AND COALESCE(ack_status, '') != 'acked'
    """)
    suspend fun claimForSend(id: Long, now: Long = System.currentTimeMillis()): Int

    /**
     * The send went out: sent, its sequence number and, when [ackPending], the wait for its ACK, in
     * one statement. Returns 0 when a confirmation came in first; it stays.
     */
    @Query("""
        UPDATE message_deliveries
        SET status = 'sent', seq_num = :seqNum, last_error = '',
            ack_status = CASE WHEN :ackPending AND COALESCE(ack_status, '') != 'acked' THEN 'pending' ELSE ack_status END,
            ack_timestamp = CASE WHEN :ackPending AND COALESCE(ack_status, '') != 'acked' THEN :now ELSE ack_timestamp END,
            updated_at = :now
        WHERE id = :id AND status = 'sending'
    """)
    suspend fun markSent(id: Long, seqNum: Long, ackPending: Boolean, now: Long = System.currentTimeMillis()): Int

    /**
     * A waiting delivery stops before it is sent ('dead' when cancelled, 'denied', 'expired'). 0 when
     * it was confirmed, cancelled or taken since it was read.
     */
    @Query("""
        UPDATE message_deliveries SET status = :status, last_error = :lastError, updated_at = :now
        WHERE id = :id AND status IN ('queued', 'retry') AND COALESCE(ack_status, '') != 'acked'
    """)
    suspend fun dropWaiting(id: Long, status: String, lastError: String, now: Long = System.currentTimeMillis()): Int

    /** This send ends for good ('dead'). 0 when it was confirmed meanwhile. */
    @Query("UPDATE message_deliveries SET status = :status, last_error = :lastError, updated_at = :now WHERE id = :id AND status = 'sending'")
    suspend fun finishSend(id: Long, status: String, lastError: String, now: Long = System.currentTimeMillis()): Int

    /** This send failed and is tried again at [nextRetry]. 0 when it was confirmed meanwhile: no retry. */
    @Query("UPDATE message_deliveries SET status = 'retry', retries = :retries, next_retry = :nextRetry, last_error = :lastError, updated_at = :now WHERE id = :id AND status = 'sending'")
    suspend fun scheduleRetry(id: Long, retries: Int, nextRetry: Long, lastError: String, now: Long = System.currentTimeMillis()): Int

    /** Wait until [nextRetry] without counting a try: the channel could not take it just now. 0 when it was confirmed meanwhile. */
    @Query("UPDATE message_deliveries SET status = 'retry', next_retry = :nextRetry, last_error = :lastError, updated_at = :now WHERE id = :id AND status = 'sending'")
    suspend fun deferRetry(id: Long, nextRetry: Long, lastError: String, now: Long = System.currentTimeMillis()): Int

    /**
     * A confirmed delivery left waiting by a worker from before FE-R4 (set back to retry after its
     * delivery report) is sent, not sent again. Sent is the claim that certainly holds; ack_status
     * keeps the confirmation. Runs at start, before the first send.
     */
    @Query("""
        UPDATE message_deliveries SET status = 'sent', next_retry = NULL, updated_at = :now
        WHERE COALESCE(ack_status, '') = 'acked' AND status IN ('queued', 'retry', 'held', 'sending')
    """)
    suspend fun settleConfirmed(now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE message_deliveries SET status = 'dead', last_error = 'cancelled', updated_at = :now WHERE id = :id AND status IN ('queued', 'retry')")
    suspend fun cancel(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE message_deliveries SET status = 'queued', next_retry = NULL, updated_at = :now WHERE id = :id AND status IN ('failed', 'dead')")
    suspend fun retryNow(id: Long, now: Long = System.currentTimeMillis())

    /**
     * Cancel a delivery that is still waiting, including one on hold until its link is back
     * (MESHSAT-1249: the queue offers Cancel on every waiting message, and [cancel] leaves a held
     * one untouched). Returns the rows changed: 0 when it was sent or stopped in the meantime.
     */
    @Query("UPDATE message_deliveries SET status = 'dead', last_error = 'cancelled', held_at = NULL, updated_at = :now WHERE id = :id AND status IN ('queued', 'retry', 'held')")
    suspend fun cancelWaiting(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM message_deliveries WHERE channel = :channel AND status IN ('queued', 'retry', 'held', 'sending')")
    suspend fun queueDepth(channel: String): Int

    @Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM message_deliveries WHERE channel = :channel AND status IN ('queued', 'retry', 'held', 'sending')")
    suspend fun queueBytes(channel: String): Long

    @Query("""
        UPDATE message_deliveries SET status = 'expired', updated_at = :now
        WHERE status IN ('queued', 'retry') AND expires_at IS NOT NULL AND expires_at <= :now AND priority > 0
    """)
    suspend fun expireDeliveries(now: Long = System.currentTimeMillis()): Int

    /**
     * Hold deliveries for a channel going offline. Records held_at for TTL clock pausing.
     */
    @Query("UPDATE message_deliveries SET status = 'held', held_at = :now, updated_at = :now WHERE channel = :channel AND status IN ('queued', 'retry')")
    suspend fun holdForChannel(channel: String, now: Long = System.currentTimeMillis()): Int

    /**
     * Unhold deliveries when channel comes back online.
     * Extends expires_at by the duration spent in held state (TTL clock pauses while held).
     * P0 messages (priority=0) have no expiry so held_at arithmetic is harmless.
     */
    @Query("""
        UPDATE message_deliveries
        SET status = 'queued',
            expires_at = CASE
                WHEN expires_at IS NOT NULL AND held_at IS NOT NULL
                THEN expires_at + (:now - held_at)
                ELSE expires_at
            END,
            held_at = NULL,
            updated_at = :now
        WHERE channel = :channel AND status = 'held'
    """)
    suspend fun unholdForChannel(channel: String, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE message_deliveries SET status = 'dead', last_error = 'cancelled: exceeded retry limit', updated_at = :now
        WHERE status IN ('queued', 'retry')
          AND ((max_retries > 0 AND retries >= max_retries) OR (max_retries = 0 AND retries >= :safetyLimit))
    """)
    suspend fun cancelRunaway(safetyLimit: Int = RUNAWAY_SAFETY_LIMIT, now: Long = System.currentTimeMillis()): Int

    /** Make every waiting retry of [channel] due now: the interface has just come online. */
    @Query("UPDATE message_deliveries SET next_retry = :now, updated_at = :now WHERE channel = :channel AND status = 'retry'")
    suspend fun retryNowForChannel(channel: String, now: Long = System.currentTimeMillis()): Int

    /** A send the app stopped in the middle of (the row still 'sending' at start) is tried again; never a confirmed one. */
    @Query("UPDATE message_deliveries SET status = 'retry', last_error = 'recovered after restart', next_retry = :now, updated_at = :now WHERE status = 'sending' AND COALESCE(ack_status, '') != 'acked'")
    suspend fun recoverStale(now: Long = System.currentTimeMillis()): Int

    /** The satellite session ("imei:momsn") a delivery went out in (MESHSAT-1246). */
    @Query("UPDATE message_deliveries SET sat_ref = :ref, updated_at = :now WHERE id = :id")
    suspend fun setSatRef(id: Long, ref: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM message_deliveries WHERE sat_ref = :ref")
    suspend fun getBySatRef(ref: String): List<MessageDeliveryEntity>

    /**
     * The Hub has the satellite session's message: the delivery is acknowledged. A row the worker
     * still holds or would retry becomes sent in the same statement, so the worker's later writes
     * leave the confirmation alone (FE-R4).
     */
    @Query("""
        UPDATE message_deliveries
        SET ack_status = 'acked', ack_timestamp = :now, updated_at = :now,
            status = CASE WHEN status IN ('queued', 'retry', 'held', 'sending') THEN 'sent' ELSE status END
        WHERE sat_ref = :ref
    """)
    suspend fun markAckedBySatRef(ref: String, now: Long = System.currentTimeMillis()): Int

    /** The deliveries of one SOS (msg_ref "sos:<run>:..."), for its result screen (MESHSAT-1249). */
    @Query("SELECT * FROM message_deliveries WHERE msg_ref LIKE :prefix || '%' ORDER BY created_at ASC, id ASC")
    fun observeByRefPrefix(prefix: String): Flow<List<MessageDeliveryEntity>>

    @Query("SELECT * FROM message_deliveries WHERE msg_ref LIKE :prefix || '%' ORDER BY created_at ASC, id ASC")
    suspend fun getByRefPrefix(prefix: String): List<MessageDeliveryEntity>

    /** Stop every delivery of one SOS that has not gone out yet, as [cancelWaiting] does for one. */
    @Query("UPDATE message_deliveries SET status = 'dead', last_error = 'cancelled', held_at = NULL, updated_at = :now WHERE msg_ref LIKE :prefix || '%' AND status IN ('queued', 'retry', 'held')")
    suspend fun cancelWaitingByRefPrefix(prefix: String, now: Long = System.currentTimeMillis()): Int

    /** Recent deliveries for UI display. */
    @Query("SELECT * FROM message_deliveries ORDER BY created_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<MessageDeliveryEntity>>

    /** Recent deliveries (suspend, for API server). */
    @Query("SELECT * FROM message_deliveries ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentSync(limit: Int = 50): List<MessageDeliveryEntity>

    /** Delivery stats by channel and status. */
    @Query("SELECT channel, status, COUNT(*) as cnt FROM message_deliveries GROUP BY channel, status ORDER BY channel, status")
    suspend fun stats(): List<DeliveryStatRow>

    // --- Phase C: Sequence numbers ---

    // The sequence number is written with the send's outcome ([markSent]).

    // --- Phase C: ACK tracking ---

    // The wait for an ACK starts with the send's outcome ([markSent]).

    /**
     * Mark delivery as ACKed → promotes status to 'delivered', from any state, the worker's included:
     * its later writes match only 'sending' and so leave this alone (FE-R4).
     */
    @Query("UPDATE message_deliveries SET ack_status = 'acked', ack_timestamp = :now, status = 'delivered', updated_at = :now WHERE id = :id")
    suspend fun setAcked(id: Long, now: Long = System.currentTimeMillis())

    /** Mark delivery as NACKed (negative acknowledgment). */
    @Query("UPDATE message_deliveries SET ack_status = 'nacked', ack_timestamp = :now, updated_at = :now WHERE id = :id")
    suspend fun setNacked(id: Long, now: Long = System.currentTimeMillis())

    /**
     * Find deliveries with pending ACKs that have timed out.
     * Returns deliveries where ack_status='pending' and ack_timestamp is older than timeoutMs.
     */
    @Query("""
        SELECT * FROM message_deliveries
        WHERE channel = :channel AND ack_status = 'pending'
          AND ack_timestamp IS NOT NULL AND ack_timestamp <= :cutoff
        ORDER BY created_at ASC
    """)
    suspend fun getPendingAcks(channel: String, cutoff: Long): List<MessageDeliveryEntity>

    /**
     * Mark one channel's timed-out pending ACKs as 'timeout'. Per channel: the sweep used to have no
     * channel filter, so the first channel's 30 s timed out every pending ACK, the satellite's too,
     * and its 2-minute timeout never applied (MESHSAT-1584).
     */
    @Query("""
        UPDATE message_deliveries
        SET ack_status = 'timeout', updated_at = :now
        WHERE ack_status = 'pending' AND ack_timestamp IS NOT NULL AND ack_timestamp <= :cutoff
          AND channel = :channel
    """)
    suspend fun timeoutPendingAcksOn(channel: String, cutoff: Long, now: Long = System.currentTimeMillis()): Int

    /** The channels with a delivery waiting for its ACK. */
    @Query("SELECT DISTINCT channel FROM message_deliveries WHERE ack_status = 'pending'")
    suspend fun pendingAckChannels(): List<String>

    /** Get delivery by channel and sequence number (for ACK correlation). */
    @Query("SELECT * FROM message_deliveries WHERE channel = :channel AND seq_num = :seqNum AND seq_num > 0 LIMIT 1")
    suspend fun getByChannelAndSeq(channel: String, seqNum: Long): MessageDeliveryEntity?

    // --- Phase D: Health scorer queries ---

    /** Count sent/delivered messages for a channel since a timestamp. */
    @Query("SELECT COUNT(*) FROM message_deliveries WHERE channel = :channel AND status IN ('sent', 'delivered') AND created_at >= :since")
    suspend fun countSentSince(channel: String, since: Long): Int

    /** Count failed/dead messages for a channel since a timestamp. */
    @Query("SELECT COUNT(*) FROM message_deliveries WHERE channel = :channel AND status IN ('failed', 'dead') AND created_at >= :since")
    suspend fun countFailedSince(channel: String, since: Long): Int

    /** Average delivery latency in ms for a channel since a timestamp. */
    @Query("SELECT COALESCE(AVG(updated_at - created_at), 0) FROM message_deliveries WHERE channel = :channel AND status IN ('sent', 'delivered') AND created_at >= :since")
    suspend fun avgLatencyMsSince(channel: String, since: Long): Long

    companion object {
        /**
         * How many tries a delivery with no retry cap of its own (a message the user wrote) may
         * have before the guard at start-up calls it a runaway. A satellite retry that found no
         * network comes every 3 minutes (Dispatcher.satelliteRetryAt), so this is about six hours
         * of trying. It was 15, which became 45 minutes once the retry stopped stretching to 30
         * (MESHSAT-1249), and a message the user wrote is meant to keep trying.
         */
        const val RUNAWAY_SAFETY_LIMIT = 120
    }
}

data class DeliveryStatRow(
    val channel: String,
    val status: String,
    val cnt: Int,
)
