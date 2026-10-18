package net.meshsat.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The strongest delivery report the carrier has given for one part of one SMS send (MESHSAT-1584).
 * A long SMS goes out as several parts and each part has its own report; the message counts as
 * delivered only once every part of the same send has one that says so. Kept in the database
 * because a report can come hours later, after the app was stopped.
 */
@Entity(tableName = "sms_part_reports", primaryKeys = ["attempt", "part"])
data class SmsPartReport(
    /** A random id per send, so a report for one send never counts towards another. */
    val attempt: String,
    val part: Int,
    val parts: Int,
    @ColumnInfo(name = "message_id") val messageId: Long = -1,
    @ColumnInfo(name = "delivery_id") val deliveryId: Long = -1,
    /** An [net.meshsat.android.sms.SmsReport] name. */
    val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface SmsPartReportDao {

    @Query("SELECT * FROM sms_part_reports WHERE attempt = :attempt")
    suspend fun partsOf(attempt: String): List<SmsPartReport>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(report: SmsPartReport)

    @Query("DELETE FROM sms_part_reports WHERE updated_at < :before")
    suspend fun pruneBefore(before: Long): Int
}
