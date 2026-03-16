package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalDao {

    @Insert
    suspend fun insert(record: SignalRecord)

    @Query("SELECT * FROM signal_history WHERE source = :source ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(source: String, limit: Int = 360): Flow<List<SignalRecord>>

    @Query("SELECT * FROM signal_history WHERE source = :source AND timestamp > :since ORDER BY timestamp ASC")
    fun getSince(source: String, since: Long): Flow<List<SignalRecord>>

    /** Most recent signal record for a source (for health scorer). */
    @Query("SELECT * FROM signal_history WHERE source = :source ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForSource(source: String): SignalRecord?

    @Query("DELETE FROM signal_history WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)
}
