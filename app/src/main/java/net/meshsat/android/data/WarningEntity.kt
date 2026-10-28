package net.meshsat.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A public warning a MeshSat kit relayed over the mesh, as this phone heard it (MESHSAT-1734).
 * One row per text taken in; the same words heard again within [net.meshsat.android.warnings.WarningIntake.DEDUP_MS]
 * (the kit's port-11 and port-1 copies, a second kit in range, a mesh rebroadcast) add no row.
 * A warning "stands" while [cancelledAt], [replacedAt] are 0 and it is not a cancel; the banner
 * shows the newest standing one not yet [acknowledgedAt].
 */
@Entity(tableName = "warnings", indices = [Index(value = ["received_at"]), Index(value = ["dedup_key"])])
data class WarningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    /** The Meshtastic port it came on: 11 (ALERT_APP) or 1 (a text). */
    val port: Int,
    /** The mesh node that sent it, "!a1b2c3d4". */
    val sender: String,
    /** That node's long name when known, else "". */
    @ColumnInfo(name = "sender_name") val senderName: String,
    /** "alert", "update" or "cancel". */
    val kind: String,
    val test: Boolean,
    val event: String,
    val severity: String,
    /** "HH:MM" UTC, or "". */
    @ColumnInfo(name = "until_utc") val until: String,
    val text: String,
    /** The kit's relay line, or "". */
    val label: String,
    /** The text as it came off the mesh, BEL dropped. */
    val raw: String,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "cancelled_at") val cancelledAt: Long = 0,
    @ColumnInfo(name = "replaced_at") val replacedAt: Long = 0,
    @ColumnInfo(name = "acknowledged_at") val acknowledgedAt: Long = 0,
) {
    val isCancel: Boolean get() = kind == KIND_CANCEL
    val standing: Boolean get() = !isCancel && cancelledAt == 0L && replacedAt == 0L

    companion object {
        const val KIND_ALERT = "alert"
        const val KIND_UPDATE = "update"
        const val KIND_CANCEL = "cancel"
    }
}

@Dao
interface WarningDao {
    @Insert
    suspend fun insert(w: WarningEntity): Long

    @Query("SELECT COUNT(*) FROM warnings WHERE dedup_key = :key AND received_at >= :since")
    suspend fun countRecent(key: String, since: Long): Int

    /** The warnings of one event that still stand, oldest first. */
    @Query("SELECT * FROM warnings WHERE kind != 'cancel' AND event = :event AND test = :test AND cancelled_at = 0 AND replaced_at = 0 ORDER BY received_at, id")
    suspend fun standingOf(event: String, test: Boolean): List<WarningEntity>

    @Query("UPDATE warnings SET replaced_at = :at WHERE id IN (:ids)")
    suspend fun markReplaced(ids: List<Long>, at: Long)

    @Query("UPDATE warnings SET cancelled_at = :at WHERE id IN (:ids)")
    suspend fun markCancelled(ids: List<Long>, at: Long)

    @Query("UPDATE warnings SET acknowledged_at = :at WHERE id = :id AND acknowledged_at = 0")
    suspend fun acknowledge(id: Long, at: Long)

    @Query("SELECT * FROM warnings WHERE id = :id")
    suspend fun byId(id: Long): WarningEntity?

    /** Newest first, every kind. */
    @Query("SELECT * FROM warnings ORDER BY received_at DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<WarningEntity>>

    /** The newest standing warning the person has not dismissed, for the banner. */
    @Query("SELECT * FROM warnings WHERE kind != 'cancel' AND cancelled_at = 0 AND replaced_at = 0 AND acknowledged_at = 0 ORDER BY received_at DESC, id DESC LIMIT 1")
    fun unacknowledged(): Flow<WarningEntity?>

    @Query("SELECT COUNT(*) FROM warnings")
    suspend fun count(): Int

    @Query("DELETE FROM warnings WHERE received_at < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
