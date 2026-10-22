package net.meshsat.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction

/**
 * An LXMF message id this phone has taken in, per sender (HeMB-NG contract C2, MESHSAT-1641): a
 * repeat of the message, a retry with a new LXMF hash or the same message over another route, is
 * dropped, also after a restart. Kept [LXMF_MID_RETENTION_MS].
 */
@Entity(tableName = "lxmf_mids", primaryKeys = ["origin", "mid"], indices = [Index(value = ["received_at"])])
data class LxmfMidEntity(
    /** The sender's LXMF source hash, hex. */
    val origin: String,
    /** The message id, UUID text. */
    val mid: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

const val LXMF_MID_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

@Dao
interface LxmfMidDao {
    /** -1 when (origin, mid) is already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: LxmfMidEntity): Long

    @Query("DELETE FROM lxmf_mids WHERE received_at < :cutoff")
    suspend fun pruneBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM lxmf_mids")
    suspend fun count(): Int
}

/**
 * Files an inbound LXMF message that carries a mid, with its (origin, mid), in one transaction
 * (r40 review R40-R2): both or neither. The row id, or null for a repeat, with nothing written. A
 * failure throws and writes nothing, so the message can come again and be taken then.
 */
suspend fun AppDatabase.insertLxmfOnce(origin: String, mid: String, now: Long, message: Message): Long? =
    withTransaction {
        if (lxmfMidDao().insert(LxmfMidEntity(origin, mid, now)) == -1L) null else messageDao().insert(message)
    }
