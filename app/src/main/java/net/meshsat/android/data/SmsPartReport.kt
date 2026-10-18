package net.meshsat.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One SMS send that asked for delivery reports, written before it goes out: its part count and
 * whose receipt it is are fixed here, so a report naming another count or another owner never
 * changes this send's evidence (review 2026-10-18 19:34, FE-R3, MESHSAT-1584).
 */
@Entity(tableName = "sms_sends")
data class SmsSend(
    @PrimaryKey val attempt: String,
    val parts: Int,
    @ColumnInfo(name = "message_id") val messageId: Long = -1,
    @ColumnInfo(name = "delivery_id") val deliveryId: Long = -1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

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

    /** Registers a send once; a second registration of the same id changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun register(send: SmsSend): Long

    @Query("SELECT * FROM sms_sends WHERE attempt = :attempt")
    suspend fun sendOf(attempt: String): SmsSend?

    @Query("DELETE FROM sms_sends WHERE created_at < :before")
    suspend fun pruneSendsBefore(before: Long): Int
}
