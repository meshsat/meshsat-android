package com.cubeos.meshsat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Access rule bound to a channel interface, mirroring Go's database.AccessRule.
 * Cisco ASA-style: implicit deny — if no rule matches, the message is dropped.
 */
@Entity(tableName = "access_rules")
data class AccessRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "interface_id") val interfaceId: String,
    val direction: String,          // "ingress" or "egress"
    val priority: Int = 10,
    val name: String,
    val enabled: Boolean = true,
    val action: String = "forward", // "forward", "drop", "log"
    @ColumnInfo(name = "forward_to") val forwardTo: String = "",
    val filters: String = "{}",     // JSON: keyword, channels, nodes, portnums
    @ColumnInfo(name = "filter_node_group") val filterNodeGroup: String? = null,
    @ColumnInfo(name = "filter_sender_group") val filterSenderGroup: String? = null,
    @ColumnInfo(name = "filter_portnum_group") val filterPortnumGroup: String? = null,
    @ColumnInfo(name = "forward_options") val forwardOptions: String = "{}",
    @ColumnInfo(name = "qos_level") val qosLevel: Int = 1,
    @ColumnInfo(name = "rate_limit_per_min") val rateLimitPerMin: Int = 0,
    @ColumnInfo(name = "rate_limit_window") val rateLimitWindow: Int = 0,
    @ColumnInfo(name = "match_count") val matchCount: Long = 0,
    @ColumnInfo(name = "last_match_at") val lastMatchAt: String? = null,
)
