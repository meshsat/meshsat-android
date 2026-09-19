package net.meshsat.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: Message): Long

    /** Where a sent message went, e.g. "iridium:queued" -> "iridium:sbd" (MESHSAT-1243). */
    @Query("UPDATE messages SET forwardedTo = :forwardedTo WHERE id = :id")
    suspend fun setForwardedTo(id: Long, forwardedTo: String)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE transport = :transport ORDER BY timestamp DESC LIMIT :limit")
    fun getByTransport(transport: String, limit: Int = 100): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE sender = :sender ORDER BY timestamp DESC LIMIT :limit")
    fun getBySender(sender: String, limit: Int = 500): Flow<List<Message>>

    /**
     * One row per conversation, keyed by the other party: the recipient of what the phone sent,
     * the sender of what it received (MESHSAT-1249). Grouping by sender filed every sent message
     * under "self".
     */
    @Query("""
        SELECT CASE WHEN direction = 'tx' AND recipient != '' THEN recipient ELSE sender END AS sender,
               text AS lastMessage, MAX(timestamp) AS lastTimestamp,
               COUNT(*) AS messageCount, transport,
               MAX(CASE WHEN encrypted = 1 THEN 1 ELSE 0 END) AS hasEncrypted
        FROM messages
        GROUP BY CASE WHEN direction = 'tx' AND recipient != '' THEN recipient ELSE sender END
        ORDER BY lastTimestamp DESC
    """)
    fun getConversations(): Flow<List<ConversationSummary>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE transport = :transport")
    suspend fun countByTransport(transport: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE encrypted = 1")
    suspend fun countEncrypted(): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT * FROM messages WHERE text LIKE '%' || :query || '%' OR sender LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    fun search(query: String, limit: Int = 100): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'tx' AND forwarded = 1")
    fun countForwarded(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'rx'")
    fun countIncoming(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'tx'")
    fun countOutgoing(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE timestamp > :since")
    fun countSince(since: Long): Flow<Int>

    /** Messages on one transport since a moment, for Home's lanes. */
    @Query("SELECT COUNT(*) FROM messages WHERE transport = :transport AND timestamp > :since")
    suspend fun countByTransportSince(transport: String, since: Long): Int

    /** Messages for a conversation (both directions). */
    @Query("SELECT * FROM messages WHERE sender = :peer OR (recipient = :peer AND direction = 'tx') ORDER BY timestamp DESC LIMIT :limit")
    fun getConversation(peer: String, limit: Int = 500): Flow<List<Message>>
}
