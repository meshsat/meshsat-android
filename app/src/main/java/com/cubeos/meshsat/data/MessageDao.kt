package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE transport = :transport ORDER BY timestamp DESC LIMIT :limit")
    fun getByTransport(transport: String, limit: Int = 100): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE transport = :transport")
    suspend fun countByTransport(transport: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE encrypted = 1")
    suspend fun countEncrypted(): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)
}
