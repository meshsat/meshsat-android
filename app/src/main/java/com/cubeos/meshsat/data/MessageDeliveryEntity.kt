package com.cubeos.meshsat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Delivery ledger entry — tracks per-(message, channel) lifecycle.
 * Statuses: queued → sending → sent → delivered / failed → retry → dead / expired / denied / held.
 * Port of Go's database.MessageDelivery.
 *
 * Phase C additions: held_at (TTL clock pausing), seq_num (per-interface sequence),
 * ack_status/ack_timestamp (delivery confirmation tracking).
 */
@Entity(tableName = "message_deliveries")
data class MessageDeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "msg_ref") val msgRef: String,
    @ColumnInfo(name = "rule_id") val ruleId: Long? = null,
    val channel: String,            // target interface ID (e.g. "iridium_0", "sms_0")
    val status: String = "queued",
    val priority: Int = 10,
    val payload: ByteArray? = null,
    @ColumnInfo(name = "text_preview") val textPreview: String = "",
    val retries: Int = 0,
    @ColumnInfo(name = "max_retries") val maxRetries: Int = 3,
    @ColumnInfo(name = "next_retry") val nextRetry: Long? = null,   // epoch millis
    @ColumnInfo(name = "last_error") val lastError: String = "",
    val visited: String = "[]",     // JSON array of visited interface IDs
    @ColumnInfo(name = "ttl_seconds") val ttlSeconds: Int = 0,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,   // epoch millis
    @ColumnInfo(name = "qos_level") val qosLevel: Int = 1,
    @ColumnInfo(name = "held_at") val heldAt: Long? = null,         // epoch millis — when moved to 'held'
    @ColumnInfo(name = "seq_num") val seqNum: Long = 0,             // per-interface monotonic sequence
    @ColumnInfo(name = "ack_status") val ackStatus: String? = null,  // null, "pending", "acked", "nacked", "timeout"
    @ColumnInfo(name = "ack_timestamp") val ackTimestamp: Long? = null, // epoch millis
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    // DTN custody transfer (MESHSAT-408)
    @ColumnInfo(name = "custody_status") val custodyStatus: String? = null,  // null, "offered", "accepted", "transferred"
    @ColumnInfo(name = "custodian_hash") val custodianHash: String? = null,  // hex of custody-accepting node
    @ColumnInfo(name = "bundle_id") val bundleId: String? = null,            // DTN bundle ID for fragmentation
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageDeliveryEntity) return false
        return id == other.id && msgRef == other.msgRef && channel == other.channel &&
                status == other.status && priority == other.priority
    }

    override fun hashCode(): Int = id.hashCode()
}
