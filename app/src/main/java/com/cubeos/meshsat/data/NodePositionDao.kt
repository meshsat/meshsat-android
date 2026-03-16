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

    /** All recent positions across all nodes (for track lines). */
    @Query("SELECT * FROM node_positions WHERE nodeId != 0 ORDER BY nodeId, timestamp ASC LIMIT :limit")
    suspend fun getAllRecentByNode(limit: Int = 500): List<NodePosition>

    /** Most recent position across all nodes (for deadman switch). */
    @Query("SELECT * FROM node_positions ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): NodePosition?

    @Query("DELETE FROM node_positions WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)
}
