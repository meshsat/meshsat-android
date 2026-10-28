package net.meshsat.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.meshsat.android.lxmf.LxAttachment

/**
 * A voice note or a photo kept with a message (MESHSAT-1735): the bytes as they travel in the
 * LXMF field, with the audio mode or the image format. Sent and received alike, in Room rather
 * than a file, so a message and its attachments live and die together and the iOS port can keep
 * the same table. The store is capped at [MessageAttachments.STORE_BYTES], oldest out first.
 */
@Entity(tableName = "message_attachments", indices = [Index(value = ["message_id"]), Index(value = ["created_at"])])
data class MessageAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    /** "audio" or "image". */
    val kind: String,
    /** Audio: the LXMF audio mode (Opus OGG = 0x10, Codec2 700C = 0x03). 0 for an image. */
    val mode: Int = 0,
    /** Image: the format name ("webp"). Empty for audio. */
    val format: String = "",
    val size: Int,
    val data: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    fun toAttachment(): LxAttachment = LxAttachment(kind, mode, format, data)

    override fun equals(other: Any?): Boolean = other is MessageAttachmentEntity && other.id == id && other.messageId == messageId && other.kind == kind
    override fun hashCode(): Int = id.hashCode()

    companion object {
        fun of(messageId: Long, a: LxAttachment, now: Long = System.currentTimeMillis()) =
            MessageAttachmentEntity(messageId = messageId, kind = a.kind, mode = a.mode, format = a.format, size = a.data.size, data = a.data, createdAt = now)
    }
}

object MessageAttachments {
    /** What the phone keeps of notes and photos, received and sent alike (the Bridge's default, 16 MiB). */
    const val STORE_BYTES = 16L * 1024 * 1024
}

@Dao
interface MessageAttachmentDao {
    @Insert
    suspend fun insert(e: MessageAttachmentEntity): Long

    @Query("SELECT * FROM message_attachments WHERE message_id = :messageId ORDER BY id")
    fun forMessage(messageId: Long): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE message_id = :messageId ORDER BY id")
    suspend fun forMessageOnce(messageId: Long): List<MessageAttachmentEntity>

    /** Every attachment in one conversation, keyed like MessageDao.getConversation, for the chat view. */
    @Query("SELECT a.* FROM message_attachments a JOIN messages m ON m.id = a.message_id WHERE m.sender = :peer OR (m.recipient = :peer AND m.direction = 'tx') ORDER BY a.id")
    fun forConversation(peer: String): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE id = :id")
    suspend fun get(id: Long): MessageAttachmentEntity?

    @Query("SELECT COALESCE(SUM(size), 0) FROM message_attachments")
    suspend fun totalBytes(): Long

    @Query("SELECT COUNT(*) FROM message_attachments")
    suspend fun count(): Int

    /** The oldest rows first: what goes when the store is over its cap. */
    @Query("SELECT id, size FROM message_attachments ORDER BY created_at, id LIMIT :limit")
    suspend fun oldest(limit: Int): List<IdSize>

    @Query("DELETE FROM message_attachments WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>): Int

    @Query("DELETE FROM message_attachments WHERE message_id = :messageId")
    suspend fun deleteForMessage(messageId: Long): Int

    data class IdSize(val id: Long, val size: Int)
}

/**
 * Files [atts] under [messageId] and then trims the store to [MessageAttachments.STORE_BYTES],
 * oldest first, in one transaction. Returns the ids written.
 */
suspend fun AppDatabase.insertAttachments(messageId: Long, atts: List<LxAttachment>, now: Long = System.currentTimeMillis(), cap: Long = MessageAttachments.STORE_BYTES): List<Long> =
    withTransaction {
        val dao = messageAttachmentDao()
        val ids = atts.map { dao.insert(MessageAttachmentEntity.of(messageId, it, now)) }
        var over = dao.totalBytes() - cap
        while (over > 0) {
            val victims = dao.oldest(32).filter { it.id !in ids }
            if (victims.isEmpty()) break
            val drop = ArrayList<Long>()
            for (v in victims) { if (over <= 0) break; drop.add(v.id); over -= v.size }
            dao.delete(drop)
        }
        ids
    }
