package com.cubeos.meshsat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for Reticulum TCP peers (MESHSAT-392).
 * Each row represents a remote RNS node to connect to over TCP.
 */
@Entity(tableName = "rns_tcp_peers")
data class RnsTcpPeer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val host: String,
    val port: Int = 4242,
    val enabled: Boolean = true,
    val label: String = "",
)

@Dao
interface RnsTcpPeerDao {
    @Query("SELECT * FROM rns_tcp_peers ORDER BY label, host")
    fun getAll(): Flow<List<RnsTcpPeer>>

    @Query("SELECT * FROM rns_tcp_peers WHERE enabled = 1")
    suspend fun getEnabled(): List<RnsTcpPeer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: RnsTcpPeer)

    @Query("DELETE FROM rns_tcp_peers WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM rns_tcp_peers")
    suspend fun count(): Int
}
