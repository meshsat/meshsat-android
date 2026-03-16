package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccessRuleDao {

    @Query("SELECT * FROM access_rules ORDER BY priority ASC, id ASC")
    fun getAll(): Flow<List<AccessRuleEntity>>

    @Query("SELECT * FROM access_rules ORDER BY priority ASC, id ASC")
    suspend fun getAllSync(): List<AccessRuleEntity>

    @Query("SELECT * FROM access_rules WHERE interface_id = :interfaceId AND direction = :direction ORDER BY priority ASC, id ASC")
    suspend fun getByInterfaceAndDirection(interfaceId: String, direction: String): List<AccessRuleEntity>

    @Query("SELECT * FROM access_rules WHERE id = :id")
    suspend fun getById(id: Long): AccessRuleEntity?

    @Insert
    suspend fun insert(rule: AccessRuleEntity): Long

    @Update
    suspend fun update(rule: AccessRuleEntity)

    @Query("DELETE FROM access_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE access_rules SET match_count = match_count + 1, last_match_at = :timestamp WHERE id = :id")
    suspend fun recordMatch(id: Long, timestamp: String)

    @Query("DELETE FROM access_rules")
    suspend fun deleteAll()
}
