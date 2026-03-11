package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cubeos.meshsat.rules.ForwardingRule

@Entity(tableName = "forwarding_rules")
data class ForwardingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val direction: String,       // INBOUND, OUTBOUND, BIDIRECTIONAL
    val sourceTransport: String, // MESH, IRIDIUM, SMS
    val destTransport: String,   // MESH, IRIDIUM, SMS
    val enabled: Boolean = true,
    val encrypt: Boolean = false,
    val filterPattern: String? = null,
    val filterSender: String? = null,
) {
    fun toRule() = ForwardingRule(
        id = id,
        name = name,
        direction = ForwardingRule.Direction.valueOf(direction),
        sourceTransport = ForwardingRule.Transport.valueOf(sourceTransport),
        destTransport = ForwardingRule.Transport.valueOf(destTransport),
        enabled = enabled,
        encrypt = encrypt,
        filterPattern = filterPattern,
        filterSender = filterSender,
    )

    companion object {
        fun fromRule(rule: ForwardingRule) = ForwardingRuleEntity(
            id = rule.id,
            name = rule.name,
            direction = rule.direction.name,
            sourceTransport = rule.sourceTransport.name,
            destTransport = rule.destTransport.name,
            enabled = rule.enabled,
            encrypt = rule.encrypt,
            filterPattern = rule.filterPattern,
            filterSender = rule.filterSender,
        )
    }
}
