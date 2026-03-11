package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ForwardingRuleDao {

    @Query("SELECT * FROM forwarding_rules ORDER BY id ASC")
    fun getAll(): Flow<List<ForwardingRuleEntity>>

    @Query("SELECT * FROM forwarding_rules ORDER BY id ASC")
    suspend fun getAllSync(): List<ForwardingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ForwardingRuleEntity): Long

    @Update
    suspend fun update(rule: ForwardingRuleEntity)

    @Query("DELETE FROM forwarding_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM forwarding_rules")
    suspend fun deleteAll()
}
