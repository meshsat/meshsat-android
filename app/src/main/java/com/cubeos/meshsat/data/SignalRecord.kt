package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_history")
data class SignalRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,  // "iridium", "mesh", "cellular"
    val value: Int,      // signal strength (0-5 for iridium, dBm for others)
)
