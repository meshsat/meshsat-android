package com.cubeos.meshsat.rules

/**
 * Transport-agnostic message envelope for unified rule evaluation.
 * Port of Go's rules.RouteMessage.
 */
data class RouteMessage(
    val text: String = "",
    val from: String = "",
    val to: String = "",
    val channel: Int = 0,       // mesh channel (0 if non-mesh)
    val portNum: Int = 1,       // portnum (1=text, 67=telemetry)
    val rawData: ByteArray? = null,
    val visited: List<String> = emptyList(),  // visited interface IDs for loop prevention
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouteMessage) return false
        return text == other.text && from == other.from && channel == other.channel && portNum == other.portNum
    }

    override fun hashCode(): Int = text.hashCode() xor from.hashCode()
}

/**
 * Result of an access rule match — the rule and the resolved forwarding target.
 */
data class AccessMatchResult(
    val rule: com.cubeos.meshsat.data.AccessRuleEntity,
    val forwardTo: String,
)
