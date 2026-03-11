package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationKeyDao {

    @Query("SELECT * FROM conversation_keys ORDER BY sender ASC")
    fun getAll(): Flow<List<ConversationKey>>

    @Query("SELECT * FROM conversation_keys WHERE sender = :sender LIMIT 1")
    suspend fun getBySender(sender: String): ConversationKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: ConversationKey)

    @Query("DELETE FROM conversation_keys WHERE sender = :sender")
    suspend fun deleteBySender(sender: String)
}
