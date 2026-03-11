package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "node_positions")
data class NodePosition(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val nodeId: Long,
    val nodeName: String = "",
    val latitude: Double,
    val longitude: Double,
    val altitude: Int = 0,
)
