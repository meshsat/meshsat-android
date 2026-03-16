package com.cubeos.meshsat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Failover group — a named set of interfaces with priority ordering.
 * When the primary is offline, routing resolves to the next available member.
 */
@Entity(tableName = "failover_groups")
data class FailoverGroupEntity(
    @PrimaryKey val id: String,
    val label: String,
    val mode: String = "failover", // "failover" or "broadcast"
)

/**
 * A member of a failover group, with a priority (lower = higher priority).
 */
@Entity(
    tableName = "failover_members",
    primaryKeys = ["group_id", "interface_id"],
)
data class FailoverMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "interface_id") val interfaceId: String,
    val priority: Int,
)
