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

    @Query("UPDATE message_deliveries SET status = 'held', updated_at = :now WHERE channel = :channel AND status IN ('queued', 'retry')")
    suspend fun holdForChannel(channel: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE message_deliveries SET status = 'queued', updated_at = :now WHERE channel = :channel AND status = 'held'")
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
}

data class DeliveryStatRow(
    val channel: String,
    val status: String,
    val cnt: Int,
)
