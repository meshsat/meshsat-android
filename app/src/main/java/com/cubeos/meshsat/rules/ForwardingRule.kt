package com.cubeos.meshsat.rules

/**
 * Simple forwarding rules engine for MeshSat Android.
 *
 * Evaluates incoming messages against a set of rules and decides
 * whether to forward them to another transport (mesh→SMS, SMS→mesh, etc.).
 */
data class ForwardingRule(
    val id: Long = 0,
    val name: String,
    val direction: Direction,
    val sourceTransport: Transport,
    val destTransport: Transport,
    val enabled: Boolean = true,
    val encrypt: Boolean = false,
    val filterPattern: String? = null,  // regex to match message text
    val filterSender: String? = null,   // match specific sender (node ID or phone)
) {
    enum class Direction { INBOUND, OUTBOUND, BIDIRECTIONAL }
    enum class Transport { MESH, IRIDIUM, SMS }
}

data class ForwardingDecision(
    val shouldForward: Boolean,
    val rule: ForwardingRule? = null,
    val encrypt: Boolean = false,
)

class RulesEngine {

    private val rules = mutableListOf<ForwardingRule>()

    fun addRule(rule: ForwardingRule) {
        rules.add(rule)
    }

    fun removeRule(id: Long) {
        rules.removeAll { it.id == id }
    }

    fun getRules(): List<ForwardingRule> = rules.toList()

    fun setRules(newRules: List<ForwardingRule>) {
        rules.clear()
        rules.addAll(newRules)
    }

    /**
     * Evaluate whether an incoming message should be forwarded.
     *
     * @param source The transport the message arrived on
     * @param text The message text
     * @param sender Sender identifier (node ID for mesh, phone for SMS, IMEI for Iridium)
     * @return ForwardingDecision with the matching rule, or shouldForward=false
     */
    fun evaluate(
        source: ForwardingRule.Transport,
        text: String,
        sender: String? = null,
    ): List<ForwardingDecision> {
        return rules.filter { rule ->
            if (!rule.enabled) return@filter false
            if (rule.sourceTransport != source) return@filter false

            // Check direction
            val directionMatch = when (rule.direction) {
                ForwardingRule.Direction.INBOUND -> true
                ForwardingRule.Direction.OUTBOUND -> false
                ForwardingRule.Direction.BIDIRECTIONAL -> true
            }
            if (!directionMatch) return@filter false

            // Check sender filter
            if (rule.filterSender != null && sender != null) {
                if (!sender.contains(rule.filterSender, ignoreCase = true)) return@filter false
            }

            // Check text filter
            if (rule.filterPattern != null) {
                try {
                    if (!Regex(rule.filterPattern).containsMatchIn(text)) return@filter false
                } catch (_: Exception) {
                    return@filter false
                }
            }

            true
        }.map { rule ->
            ForwardingDecision(
                shouldForward = true,
                rule = rule,
                encrypt = rule.encrypt,
            )
        }
    }
}
