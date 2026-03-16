package com.cubeos.meshsat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tamper-evident audit log entry with SHA-256 hash chain.
 * Port of Go's database.AuditLogEntry.
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: String,
    @ColumnInfo(name = "interface_id") val interfaceId: String? = null,
    val direction: String? = null,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "delivery_id") val deliveryId: Long? = null,
    @ColumnInfo(name = "rule_id") val ruleId: Long? = null,
    val detail: String = "",
    @ColumnInfo(name = "prev_hash") val prevHash: String = "",
    val hash: String = "",
)
