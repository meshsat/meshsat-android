package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TelemetryDao {

    @Insert
    suspend fun insert(entry: TelemetryEntity): Long

    @Query("SELECT * FROM telemetry WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int): List<TelemetryEntity>

    @Query("SELECT * FROM telemetry ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<TelemetryEntity>

    @Query("SELECT * FROM telemetry WHERE id > :sinceId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getSince(sinceId: Long, limit: Int): List<TelemetryEntity>

    @Query("SELECT COUNT(*) FROM telemetry WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("SELECT COUNT(*) FROM telemetry")
    suspend fun count(): Int

    /**
     * Trim the oldest rows of a given type, keeping only the most recent [keep] entries.
     * Called periodically by [com.cubeos.meshsat.engine.TelemetryLogger] to bound storage.
     */
    @Query("""
        DELETE FROM telemetry
        WHERE type = :type
        AND id NOT IN (
            SELECT id FROM telemetry WHERE type = :type ORDER BY timestamp DESC LIMIT :keep
        )
    """)
    suspend fun trimType(type: String, keep: Int)

    @Query("DELETE FROM telemetry")
    suspend fun deleteAll()

    @Query("DELETE FROM telemetry WHERE type = :type")
    suspend fun deleteByType(type: String)
}
