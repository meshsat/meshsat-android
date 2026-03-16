package com.cubeos.meshsat.rules

import android.util.Log
import com.cubeos.meshsat.data.AccessRuleDao
import com.cubeos.meshsat.data.AccessRuleEntity
import com.cubeos.meshsat.data.ObjectGroupDao
import com.cubeos.meshsat.ratelimit.TokenBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Evaluates v0.3.0 access rules for ingress/egress decisions.
 * Cisco ASA-style: implicit deny — if no rule matches, the message is dropped.
 * Port of Go's rules.AccessEvaluator.
 */
class AccessEvaluator(
    private val accessRuleDao: AccessRuleDao,
    private val objectGroupDao: ObjectGroupDao,
    private val scope: CoroutineScope,
) {
    private val lock = ReentrantReadWriteLock()
    private var rules: List<AccessRuleEntity> = emptyList()
    private val rates = mutableMapOf<Long, TokenBucket>()
    private val groups = mutableMapOf<String, List<String>>()

    /** Reload rules and object groups from the database. */
    suspend fun reloadFromDb() {
        val dbRules = accessRuleDao.getAllSync()
        val dbGroups = objectGroupDao.getAll()

        lock.write {
            rules = dbRules
            rates.clear()
            groups.clear()

            for (rule in dbRules) {
                if (rule.rateLimitPerMin > 0 && rule.rateLimitWindow > 0) {
                    val limiter = TokenBucket.ruleLimiter(rule.rateLimitPerMin, rule.rateLimitWindow)
                    if (limiter != null) {
                        rates[rule.id] = limiter
                    }
                }
            }

            for (group in dbGroups) {
                val members = parseJsonStringArray(group.members)
                if (members.isNotEmpty()) {
                    groups[group.id] = members
                }
            }
        }

        Log.i(TAG, "Access rules loaded: ${dbRules.size} rules, ${groups.size} groups")
    }

    /** Number of loaded rules. */
    fun ruleCount(): Int = lock.read { rules.size }

    /** Evaluate ingress rules for a message arriving on an interface. */
    fun evaluateIngress(interfaceId: String, msg: RouteMessage): List<AccessMatchResult> {
        return evaluate(interfaceId, "ingress", msg)
    }

    /** Evaluate egress rules before sending to a destination interface. */
    fun evaluateEgress(interfaceId: String, msg: RouteMessage): List<AccessMatchResult> {
        return evaluate(interfaceId, "egress", msg)
    }

    /** Check if any enabled egress rules exist for the given interface. */
    fun hasEgressRules(interfaceId: String): Boolean = lock.read {
        rules.any { it.enabled && it.interfaceId == interfaceId && it.direction == "egress" }
    }

    private fun evaluate(interfaceId: String, direction: String, msg: RouteMessage): List<AccessMatchResult> {
        lock.read {
            val results = mutableListOf<AccessMatchResult>()

            for (rule in rules) {
                if (!rule.enabled) continue
                if (rule.interfaceId != interfaceId) continue
                if (rule.direction != direction) continue

                // Self-loop prevention
                if (direction == "ingress" && rule.forwardTo == interfaceId) continue

                // Loop prevention: skip targets already in visited set
                if (direction == "ingress" && msg.visited.isNotEmpty()) {
                    if (rule.forwardTo in msg.visited) {
                        Log.d(TAG, "Rule ${rule.id}: loop prevented (${rule.forwardTo} in visited)")
                        continue
                    }
                }

                // Evaluate filters
                if (!matchFilters(rule, msg)) continue

                // Object group filters
                if (!matchObjectGroups(rule, msg)) continue

                // Rate limiter
                val limiter = rates[rule.id]
                if (limiter != null && !limiter.allow()) {
                    Log.d(TAG, "Rule ${rule.id} '${rule.name}': rate limited")
                    continue
                }

                // Action handling
                when (rule.action) {
                    "drop" -> {
                        Log.d(TAG, "Rule ${rule.id} '${rule.name}': explicit drop")
                        recordMatch(rule.id)
                        return emptyList()
                    }
                    "log" -> {
                        Log.i(TAG, "Rule ${rule.id} '${rule.name}': log match on $interfaceId/$direction")
                        recordMatch(rule.id)
                        continue
                    }
                    "forward" -> {
                        recordMatch(rule.id)
                        results.add(AccessMatchResult(rule = rule, forwardTo = rule.forwardTo))
                    }
                }
            }

            return results
        }
    }

    private fun matchFilters(rule: AccessRuleEntity, msg: RouteMessage): Boolean {
        if (rule.filters.isEmpty() || rule.filters == "{}") return true

        val filters = try {
            JSONObject(rule.filters)
        } catch (_: Exception) {
            return true // malformed = permissive
        }

        // Keyword filter
        val keyword = filters.optString("keyword", "")
        if (keyword.isNotEmpty()) {
            if (!msg.text.contains(keyword, ignoreCase = true)) return false
        }

        // Channel filter
        val channels = filters.optString("channels", "")
        if (channels.isNotEmpty() && channels != "[]") {
            val arr = parseJsonIntArray(channels)
            if (arr.isNotEmpty() && msg.channel !in arr) return false
        }

        // Node filter
        val nodes = filters.optString("nodes", "")
        if (nodes.isNotEmpty() && nodes != "[]") {
            val arr = parseJsonStringArray(nodes)
            if (arr.isNotEmpty() && msg.from !in arr) return false
        }

        // Portnum filter
        val portnums = filters.optString("portnums", "")
        if (portnums.isNotEmpty() && portnums != "[]") {
            val arr = parseJsonIntArray(portnums)
            if (arr.isNotEmpty() && msg.portNum !in arr) return false
        }

        return true
    }

    private fun matchObjectGroups(rule: AccessRuleEntity, msg: RouteMessage): Boolean {
        // Node group filter
        val nodeGroup = rule.filterNodeGroup
        if (!nodeGroup.isNullOrEmpty()) {
            val members = groups[nodeGroup]
            if (members != null && members.isNotEmpty() && msg.from !in members) return false
        }

        // Sender group filter
        val senderGroup = rule.filterSenderGroup
        if (!senderGroup.isNullOrEmpty()) {
            val members = groups[senderGroup]
            if (members != null && members.isNotEmpty() && msg.from !in members) return false
        }

        // Portnum group filter
        val portnumGroup = rule.filterPortnumGroup
        if (!portnumGroup.isNullOrEmpty()) {
            val members = groups[portnumGroup]
            if (members != null && members.isNotEmpty() && msg.portNum.toString() !in members) return false
        }

        return true
    }

    private fun recordMatch(ruleId: Long) {
        scope.launch {
            try {
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date())
                accessRuleDao.recordMatch(ruleId, ts)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record match for rule $ruleId: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AccessEvaluator"

        private fun parseJsonStringArray(json: String): List<String> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parseJsonIntArray(json: String): List<Int> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getInt(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
