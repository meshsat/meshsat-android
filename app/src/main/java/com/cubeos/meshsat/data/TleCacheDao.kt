package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TleCacheDao {

    @Query("SELECT * FROM tle_cache ORDER BY satelliteName")
    suspend fun getAll(): List<TleCacheEntity>

    @Query("SELECT MIN(fetchedAt) FROM tle_cache")
    suspend fun getOldestFetchTime(): Long?

    @Query("DELETE FROM tle_cache")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(entries: List<TleCacheEntity>)
}
