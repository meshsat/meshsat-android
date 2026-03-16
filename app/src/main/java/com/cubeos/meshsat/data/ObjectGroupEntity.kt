package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Object group for access rule membership filters.
 * Types: "node_group", "sender_group", "portnum_group", "contact_group".
 * Members is a JSON array of strings (node IDs, phone numbers, portnum strings, etc.).
 */
@Entity(tableName = "object_groups")
data class ObjectGroupEntity(
    @PrimaryKey val id: String,
    val type: String,
    val label: String,
    val members: String = "[]", // JSON array
)
