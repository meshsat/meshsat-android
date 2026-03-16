package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tle_cache")
data class TleCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val satelliteName: String,
    val line1: String,
    val line2: String,
    val fetchedAt: Long,  // unix seconds
)
