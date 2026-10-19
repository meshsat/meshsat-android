package net.meshsat.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The Hub app route's tables (hub_msg, HeMB-NG stage 2, Room v21, MESHSAT-1584): the operations
 * this phone published and the events the Hub reported about them, kept once per event id; the
 * events found missing (an obligation names its event, A15); the receiver's inbox (C3). The same
 * tables as the Bridge's hub_ops, hub_op_events, hub_op_missing and hub_inbox; a time that is not
 * set is 0, not NULL.
 */

@Entity(tableName = "hub_ops", indices = [Index("expires_at")])
data class HubOpEntity(
    @PrimaryKey @ColumnInfo(name = "op_id") val opId: String,
    val mid: String,
    val recipient: String,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val submission: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long = 0,
    @ColumnInfo(name = "replay_at") val replayAt: Long = 0,
    @ColumnInfo(name = "replay_given_up_at") val replayGivenUpAt: Long = 0,
)

@Entity(tableName = "hub_op_events", indices = [Index("op_id")])
data class HubOpEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "op_id") val opId: String,
    val mid: String,
    val recipient: String,
    val rev: Int,
    @ColumnInfo(name = "class") val cls: String,
    val kind: String,
    val reason: String,
    val authority: String,
    val scope: String,
    val detail: String,
    val at: String,
    val source: String,
    val valid: Boolean,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Entity(tableName = "hub_op_missing", primaryKeys = ["op_id", "event_id"])
data class HubOpMissingEntity(
    @ColumnInfo(name = "op_id") val opId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    val since: Long,
)

@Entity(tableName = "hub_inbox", primaryKeys = ["origin", "op_id"], indices = [Index(value = ["origin", "mid"]), Index("expires_at")])
data class HubInboxEntity(
    val origin: String,
    @ColumnInfo(name = "op_id") val opId: String,
    val mid: String,
    val recipient: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "mid_expires_at") val midExpiresAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "committed_at") val committedAt: Long,
    @ColumnInfo(name = "within_skew") val withinSkew: Boolean,
    val unjudged: Boolean,
)

/**
 * Blocking queries for RoomHubMsgStore, which runs each of its methods as one transaction
 * (runInTransaction) off the main thread.
 */
@Dao
interface HubMsgDao {
    // ---- the chat rows ----
    @Insert
    fun insertMessage(m: Message): Long

    @Query("UPDATE messages SET forwardedTo = :status WHERE id = :id")
    fun setMessageStatus(id: Long, status: String)

    // ---- operations ----
    @Insert
    fun insertOp(op: HubOpEntity)

    @Query("SELECT * FROM hub_ops WHERE op_id = :opId")
    fun op(opId: String): HubOpEntity?

    @Query("SELECT * FROM hub_ops WHERE published_at = 0 ORDER BY created_at, op_id LIMIT :limit")
    fun unpublished(limit: Int): List<HubOpEntity>

    @Query("UPDATE hub_ops SET published_at = :at WHERE op_id = :opId AND published_at = 0")
    fun setPublished(opId: String, at: Long)

    @Query("UPDATE hub_ops SET replay_at = :at WHERE op_id = :opId AND replay_at = 0")
    fun setReplayAt(opId: String, at: Long)

    @Query("UPDATE hub_ops SET replay_given_up_at = :at WHERE op_id = :opId AND replay_given_up_at = 0")
    fun setGivenUp(opId: String, at: Long)

    @Query("UPDATE hub_ops SET replay_at = 0 WHERE op_id = :opId AND replay_at != 0 AND NOT EXISTS (SELECT 1 FROM hub_op_missing m WHERE m.op_id = hub_ops.op_id)")
    fun clearIdleReplay(opId: String)

    @Query("UPDATE hub_ops SET replay_at = 0 WHERE replay_at != 0 AND NOT EXISTS (SELECT 1 FROM hub_op_missing m WHERE m.op_id = hub_ops.op_id)")
    fun clearIdleReplays()

    // ---- events ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvent(e: HubOpEventEntity): Long

    @Query("SELECT * FROM hub_op_events WHERE event_id = :eventId")
    fun event(eventId: String): HubOpEventEntity?

    @Query("SELECT * FROM hub_op_events WHERE op_id = :opId ORDER BY rev, event_id")
    fun events(opId: String): List<HubOpEventEntity>

    @Query("SELECT COUNT(*) FROM hub_op_events WHERE event_id = :eventId")
    fun eventCount(eventId: String): Int

    @Query("SELECT COUNT(*) FROM hub_op_events WHERE op_id = :opId AND valid = 1 AND `class` = 'completed' AND event_id NOT LIKE 'refusal:%'")
    fun completedCount(opId: String): Int

    // ---- missing events ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMissing(m: HubOpMissingEntity)

    @Query("DELETE FROM hub_op_missing WHERE op_id = :opId AND event_id = :eventId")
    fun deleteMissing(opId: String, eventId: String)

    @Query("DELETE FROM hub_op_missing WHERE op_id = :opId")
    fun deleteMissingOf(opId: String)

    @Query("SELECT event_id FROM hub_op_missing WHERE op_id = :opId ORDER BY since, event_id")
    fun missing(opId: String): List<String>

    @Query("DELETE FROM hub_op_missing WHERE event_id != '' AND EXISTS (SELECT 1 FROM hub_op_events e WHERE e.event_id = hub_op_missing.event_id)")
    fun deleteKeptMissing(): Int

    @Query("""DELETE FROM hub_op_missing WHERE op_id IN (SELECT e.op_id FROM hub_op_events e WHERE e.valid = 1
        AND e.`class` = 'completed' AND e.event_id NOT LIKE 'refusal:%')""")
    fun deleteCompletedMissing(): Int

    // ---- the listings (still missing = a missing row whose event is not kept, A12/HM-R7) ----
    @Query("""SELECT * FROM hub_ops WHERE replay_at != 0 AND submission != '' AND replay_given_up_at = 0 AND expires_at + :within >= :now
        AND EXISTS (SELECT 1 FROM hub_op_missing m WHERE m.op_id = hub_ops.op_id
            AND NOT EXISTS (SELECT 1 FROM hub_op_events k WHERE k.event_id = m.event_id AND k.op_id = m.op_id))
        AND (replay_at > :key OR (replay_at = :key AND op_id > :afterId)) ORDER BY replay_at, op_id LIMIT :limit""")
    fun owed(now: Long, within: Long, key: Long, afterId: String, limit: Int): List<HubOpEntity>

    @Query("""SELECT * FROM hub_ops WHERE submission != '' AND replay_given_up_at = 0 AND expires_at + :within >= :now
        AND NOT EXISTS (SELECT 1 FROM hub_op_events e WHERE e.op_id = hub_ops.op_id AND e.valid = 1 AND e.`class` = 'completed'
            AND e.event_id NOT LIKE 'refusal:%')
        AND (created_at > :key OR (created_at = :key AND op_id > :afterId)) ORDER BY created_at, op_id LIMIT :limit""")
    fun toReconcile(now: Long, within: Long, key: Long, afterId: String, limit: Int): List<HubOpEntity>

    @Query("""SELECT * FROM hub_ops WHERE replay_given_up_at = 0 AND expires_at + :within < :now
        AND NOT EXISTS (SELECT 1 FROM hub_op_events e WHERE e.op_id = hub_ops.op_id AND e.valid = 1 AND e.`class` = 'completed'
            AND e.event_id NOT LIKE 'refusal:%')
        AND (EXISTS (SELECT 1 FROM hub_op_missing m WHERE m.op_id = hub_ops.op_id
                AND NOT EXISTS (SELECT 1 FROM hub_op_events k WHERE k.event_id = m.event_id AND k.op_id = m.op_id))
            OR NOT EXISTS (SELECT 1 FROM hub_op_events e WHERE e.op_id = hub_ops.op_id AND e.valid = 1
                AND e.`class` IN ('completed', 'failed', 'expired')))
        ORDER BY created_at, op_id LIMIT :limit""")
    fun toGiveUp(now: Long, within: Long, limit: Int): List<HubOpEntity>

    // ---- prune ----
    @Query("DELETE FROM hub_op_events WHERE op_id IN (SELECT op_id FROM hub_ops WHERE expires_at < :cutoff)")
    fun pruneEvents(cutoff: Long)

    @Query("DELETE FROM hub_op_missing WHERE op_id IN (SELECT op_id FROM hub_ops WHERE expires_at < :cutoff)")
    fun pruneMissing(cutoff: Long)

    @Query("DELETE FROM hub_ops WHERE expires_at < :cutoff")
    fun pruneOps(cutoff: Long): Int

    // ---- the inbox ----
    @Query("SELECT * FROM hub_inbox WHERE origin = :origin AND op_id = :opId")
    fun inboxByOp(origin: String, opId: String): HubInboxEntity?

    @Query("SELECT * FROM hub_inbox WHERE origin = :origin AND mid = :mid ORDER BY committed_at LIMIT 1")
    fun inboxByMid(origin: String, mid: String): HubInboxEntity?

    @Query("SELECT COUNT(*) FROM hub_inbox")
    fun inboxCount(): Int

    @Query("DELETE FROM hub_inbox WHERE expires_at + :after < :now")
    fun pruneInbox(now: Long, after: Long): Int

    @Insert
    fun insertInbox(e: HubInboxEntity)
}
