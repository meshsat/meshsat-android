package com.cubeos.meshsat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Tracks Iridium satellite message costs for credit gauge visualization. */
@Entity(tableName = "iridium_credit_log")
data class IridiumCreditEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: String, // "mo", "mt", "burst"
    val costCents: Int,      // USD cents (5 = $0.05)
    val moMsn: Int = 0,
)

@Dao
interface IridiumCreditDao {
    @Insert
    suspend fun insert(entry: IridiumCreditEntry)

    @Query("SELECT SUM(costCents) FROM iridium_credit_log")
    suspend fun totalCostCents(): Int?

    @Query("SELECT SUM(costCents) FROM iridium_credit_log WHERE timestamp > :since")
    suspend fun costSince(since: Long): Int?

    @Query("SELECT COUNT(*) FROM iridium_credit_log WHERE timestamp > :since")
    suspend fun messagesSince(since: Long): Int

    @Query("SELECT * FROM iridium_credit_log ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<IridiumCreditEntry>>
}
