package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NodePositionDao {

    @Insert
    suspend fun insert(position: NodePosition)

    /** Latest position per node. */
    @Query("""
        SELECT * FROM node_positions
        WHERE id IN (SELECT MAX(id) FROM node_positions GROUP BY nodeId)
        ORDER BY timestamp DESC
    """)
    fun getLatestPerNode(): Flow<List<NodePosition>>

    /** All positions for a specific node. */
    @Query("SELECT * FROM node_positions WHERE nodeId = :nodeId ORDER BY timestamp DESC LIMIT :limit")
    fun getByNode(nodeId: Long, limit: Int = 100): Flow<List<NodePosition>>

    @Query("DELETE FROM node_positions WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)
}
