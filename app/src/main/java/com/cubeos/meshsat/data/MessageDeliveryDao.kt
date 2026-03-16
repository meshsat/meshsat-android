package com.cubeos.meshsat.data

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

    @Query("""
        SELECT * FROM message_deliveries
        WHERE channel = :channel AND status IN ('queued', 'retry')
          AND (next_retry IS NULL OR next_retry <= :now)
          AND (priority = 0 OR expires_at IS NULL OR expires_at > :now)
        ORDER BY priority ASC, created_at ASC
        LIMIT :limit
    """)
    suspend fun getPending(channel: String, now: Long, limit: Int = 10): List<MessageDeliveryEntity>

    @Query("UPDATE message_deliveries SET status = :status, last_error = :lastError, updated_at = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, lastError: String = "", now: Long = System.currentTimeMillis())

    @Query("UPDATE message_deliveries SET status = 'retry', retries = :retries, next_retry = :nextRetry, last_error = :lastError, updated_at = :now WHERE id = :id")
    suspend fun scheduleRetry(id: Long, retries: Int, nextRetry: Long, lastError: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE message_deliveries SET status = 'dead', last_error = 'cancelled', updated_at = :now WHERE id = :id AND status IN ('queued', 'retry')")
    suspend fun cancel(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE message_deliveries SET status = 'queued', next_retry = NULL, updated_at = :now WHERE id = :id AND status IN ('failed', 'dead')")
    suspend fun retryNow(id: Long, now: Long = System.currentTimeMillis())

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
    suspend fun cancelRunaway(safetyLimit: Int = 15, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE message_deliveries SET status = 'retry', last_error = 'recovered after restart', next_retry = :now, updated_at = :now WHERE status = 'sending'")
    suspend fun recoverStale(now: Long = System.currentTimeMillis()): Int

    /** Recent deliveries for UI display. */
    @Query("SELECT * FROM message_deliveries ORDER BY created_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<MessageDeliveryEntity>>

    /** Delivery stats by channel and status. */
    @Query("SELECT channel, status, COUNT(*) as cnt FROM message_deliveries GROUP BY channel, status ORDER BY channel, status")
    suspend fun stats(): List<DeliveryStatRow>

    // --- Phase C: Sequence numbers ---

    /** Assign a sequence number to a delivery (set before sending). */
    @Query("UPDATE message_deliveries SET seq_num = :seqNum, updated_at = :now WHERE id = :id")
    suspend fun setSeqNum(id: Long, seqNum: Long, now: Long = System.currentTimeMillis())

    // --- Phase C: ACK tracking ---

    /** Set ACK status to 'pending' after successful send (QoS >= 1). */
    @Query("UPDATE message_deliveries SET ack_status = 'pending', ack_timestamp = :now, updated_at = :now WHERE id = :id")
    suspend fun setAckPending(id: Long, now: Long = System.currentTimeMillis())

    /**
     * Mark delivery as ACKed → promotes status to 'delivered'.
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

    /** Mark timed-out pending ACKs as 'timeout'. */
    @Query("""
        UPDATE message_deliveries
        SET ack_status = 'timeout', updated_at = :now
        WHERE ack_status = 'pending' AND ack_timestamp IS NOT NULL AND ack_timestamp <= :cutoff
    """)
    suspend fun timeoutPendingAcks(cutoff: Long, now: Long = System.currentTimeMillis()): Int

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
}

data class DeliveryStatRow(
    val channel: String,
    val status: String,
    val cnt: Int,
)
