package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BridgeTrustDao {

    @Query("SELECT * FROM bridge_trust WHERE bridgeHash = :hash")
    suspend fun get(hash: String): BridgeTrustEntity?

    @Query("SELECT * FROM bridge_trust ORDER BY lastSeen DESC")
    suspend fun getAll(): List<BridgeTrustEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BridgeTrustEntity)

    @Query("DELETE FROM bridge_trust WHERE bridgeHash = :hash")
    suspend fun delete(hash: String)

    @Query("SELECT COUNT(*) FROM bridge_trust")
    suspend fun count(): Int
}
