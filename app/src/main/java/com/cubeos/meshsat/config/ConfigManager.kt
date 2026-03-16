package com.cubeos.meshsat.config

import android.util.Log
import com.cubeos.meshsat.data.AccessRuleDao
import com.cubeos.meshsat.data.AccessRuleEntity
import com.cubeos.meshsat.data.FailoverGroupDao
import com.cubeos.meshsat.data.FailoverGroupEntity
import com.cubeos.meshsat.data.FailoverMemberEntity
import com.cubeos.meshsat.data.ObjectGroupDao
import com.cubeos.meshsat.data.ObjectGroupEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Configuration export/import manager.
 * Port of meshsat/internal/api/config_export.go — Cisco-style "show running-config".
 *
 * Uses JSON (not YAML) since Android has native JSON support.
 * Exports/imports: access rules, object groups, failover groups.
 */
class ConfigManager(
    private val accessRuleDao: AccessRuleDao,
    private val objectGroupDao: ObjectGroupDao,
    private val failoverGroupDao: FailoverGroupDao,
) {
    /**
     * Export the current configuration as a JSON string.
     */
    suspend fun export(): String {
        val rules = accessRuleDao.getAllSync()
        val groups = objectGroupDao.getAll()
        val fgroups = failoverGroupDao.getAllGroups()

        val root = JSONObject()
        root.put("version", CONFIG_VERSION)
        root.put("exported_at", utcNow())

        // Access rules
        val rulesArr = JSONArray()
        for (r in rules) {
            rulesArr.put(JSONObject().apply {
                put("interface_id", r.interfaceId)
                put("direction", r.direction)
                put("priority", r.priority)
                put("name", r.name)
                put("enabled", r.enabled)
                put("action", r.action)
                put("forward_to", r.forwardTo)
                put("filters", r.filters)
                if (r.filterNodeGroup != null) put("filter_node_group", r.filterNodeGroup)
                if (r.filterSenderGroup != null) put("filter_sender_group", r.filterSenderGroup)
                if (r.filterPortnumGroup != null) put("filter_portnum_group", r.filterPortnumGroup)
                put("forward_options", r.forwardOptions)
                put("qos_level", r.qosLevel)
                put("rate_limit_per_min", r.rateLimitPerMin)
                put("rate_limit_window", r.rateLimitWindow)
            })
        }
        root.put("access_rules", rulesArr)

        // Object groups
        val groupsArr = JSONArray()
        for (g in groups) {
            groupsArr.put(JSONObject().apply {
                put("id", g.id)
                put("type", g.type)
                put("label", g.label)
                put("members", g.members)
            })
        }
        root.put("object_groups", groupsArr)

        // Failover groups
        val fgroupsArr = JSONArray()
        for (fg in fgroups) {
            val members = failoverGroupDao.getMembers(fg.id)
            fgroupsArr.put(JSONObject().apply {
                put("id", fg.id)
                put("label", fg.label)
                put("mode", fg.mode)
                val membersArr = JSONArray()
                for (m in members) {
                    membersArr.put(JSONObject().apply {
                        put("interface_id", m.interfaceId)
                        put("priority", m.priority)
                    })
                }
                put("members", membersArr)
            })
        }
        root.put("failover_groups", fgroupsArr)

        return root.toString(2)
    }

    /**
     * Validate an incoming config JSON. Returns null if valid, or an error message.
     */
    fun validate(json: String): String? {
        return try {
            val root = JSONObject(json)
            if (!root.has("version")) return "missing version field"

            // Validate access rules reference consistency
            val ruleIfaceIds = mutableSetOf<String>()
            val rulesArr = root.optJSONArray("access_rules") ?: JSONArray()
            for (i in 0 until rulesArr.length()) {
                val r = rulesArr.getJSONObject(i)
                val ifaceId = r.optString("interface_id", "")
                if (ifaceId.isBlank()) return "access rule at index $i missing interface_id"
                ruleIfaceIds.add(ifaceId)
            }

            // Validate failover group members
            val fgroupsArr = root.optJSONArray("failover_groups") ?: JSONArray()
            for (i in 0 until fgroupsArr.length()) {
                val fg = fgroupsArr.getJSONObject(i)
                if (fg.optString("id", "").isBlank()) return "failover group at index $i missing id"
            }

            null
        } catch (e: Exception) {
            "invalid JSON: ${e.message}"
        }
    }

    /**
     * Import a config JSON, replacing all existing config.
     * Returns a map of entity counts imported.
     */
    suspend fun import(json: String): Map<String, Int> {
        val error = validate(json)
        if (error != null) throw IllegalArgumentException(error)

        val root = JSONObject(json)
        val counts = mutableMapOf<String, Int>()

        // Clear existing config in reverse dependency order
        failoverGroupDao.deleteAllMembersGlobal()
        failoverGroupDao.deleteAllGroups()
        accessRuleDao.deleteAll()
        objectGroupDao.deleteAll()

        // Import object groups
        val groupsArr = root.optJSONArray("object_groups") ?: JSONArray()
        for (i in 0 until groupsArr.length()) {
            val g = groupsArr.getJSONObject(i)
            objectGroupDao.upsert(ObjectGroupEntity(
                id = g.getString("id"),
                type = g.getString("type"),
                label = g.getString("label"),
                members = g.optString("members", "[]"),
            ))
        }
        counts["object_groups"] = groupsArr.length()

        // Import access rules
        val rulesArr = root.optJSONArray("access_rules") ?: JSONArray()
        for (i in 0 until rulesArr.length()) {
            val r = rulesArr.getJSONObject(i)
            accessRuleDao.insert(AccessRuleEntity(
                interfaceId = r.getString("interface_id"),
                direction = r.getString("direction"),
                priority = r.optInt("priority", 10),
                name = r.getString("name"),
                enabled = r.optBoolean("enabled", true),
                action = r.optString("action", "forward"),
                forwardTo = r.optString("forward_to", ""),
                filters = r.optString("filters", "{}"),
                filterNodeGroup = r.optString("filter_node_group", null),
                filterSenderGroup = r.optString("filter_sender_group", null),
                filterPortnumGroup = r.optString("filter_portnum_group", null),
                forwardOptions = r.optString("forward_options", "{}"),
                qosLevel = r.optInt("qos_level", 1),
                rateLimitPerMin = r.optInt("rate_limit_per_min", 0),
                rateLimitWindow = r.optInt("rate_limit_window", 0),
            ))
        }
        counts["access_rules"] = rulesArr.length()

        // Import failover groups
        val fgroupsArr = root.optJSONArray("failover_groups") ?: JSONArray()
        for (i in 0 until fgroupsArr.length()) {
            val fg = fgroupsArr.getJSONObject(i)
            failoverGroupDao.upsertGroup(FailoverGroupEntity(
                id = fg.getString("id"),
                label = fg.getString("label"),
                mode = fg.optString("mode", "failover"),
            ))
            val members = fg.optJSONArray("members") ?: JSONArray()
            for (j in 0 until members.length()) {
                val m = members.getJSONObject(j)
                failoverGroupDao.upsertMember(FailoverMemberEntity(
                    groupId = fg.getString("id"),
                    interfaceId = m.getString("interface_id"),
                    priority = m.optInt("priority", 0),
                ))
            }
        }
        counts["failover_groups"] = fgroupsArr.length()

        Log.i(TAG, "config import complete: $counts")
        return counts
    }

    /**
     * Preview what would change between current config and incoming JSON.
     */
    suspend fun diff(json: String): DiffResult {
        val error = validate(json)
        if (error != null) throw IllegalArgumentException(error)

        val root = JSONObject(json)

        // Current config
        val currentRules = accessRuleDao.getAllSync().map { it.name }.toSet()
        val currentGroups = objectGroupDao.getAll().map { it.id }.toSet()
        val currentFGroups = failoverGroupDao.getAllGroups().map { it.id }.toSet()

        // Incoming config
        val incomingRules = mutableSetOf<String>()
        val rulesArr = root.optJSONArray("access_rules") ?: JSONArray()
        for (i in 0 until rulesArr.length()) {
            incomingRules.add(rulesArr.getJSONObject(i).getString("name"))
        }

        val incomingGroups = mutableSetOf<String>()
        val groupsArr = root.optJSONArray("object_groups") ?: JSONArray()
        for (i in 0 until groupsArr.length()) {
            incomingGroups.add(groupsArr.getJSONObject(i).getString("id"))
        }

        val incomingFGroups = mutableSetOf<String>()
        val fgroupsArr = root.optJSONArray("failover_groups") ?: JSONArray()
        for (i in 0 until fgroupsArr.length()) {
            incomingFGroups.add(fgroupsArr.getJSONObject(i).getString("id"))
        }

        return DiffResult(
            accessRules = diffSets(currentRules, incomingRules),
            objectGroups = diffSets(currentGroups, incomingGroups),
            failoverGroups = diffSets(currentFGroups, incomingFGroups),
        )
    }

    private fun diffSets(current: Set<String>, incoming: Set<String>): DiffCounts {
        val add = incoming.count { it !in current }
        val remove = current.count { it !in incoming }
        val change = incoming.count { it in current }
        return DiffCounts(add = add, remove = remove, change = change)
    }

    private fun utcNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    companion object {
        private const val TAG = "ConfigManager"
        const val CONFIG_VERSION = "0.3.0"
    }
}

data class DiffCounts(
    val add: Int = 0,
    val remove: Int = 0,
    val change: Int = 0,
)

data class DiffResult(
    val accessRules: DiffCounts = DiffCounts(),
    val objectGroups: DiffCounts = DiffCounts(),
    val failoverGroups: DiffCounts = DiffCounts(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("access_rules", JSONObject().apply {
            put("add", accessRules.add)
            put("remove", accessRules.remove)
            put("change", accessRules.change)
        })
        put("object_groups", JSONObject().apply {
            put("add", objectGroups.add)
            put("remove", objectGroups.remove)
            put("change", objectGroups.change)
        })
        put("failover_groups", JSONObject().apply {
            put("add", failoverGroups.add)
            put("remove", failoverGroups.remove)
            put("change", failoverGroups.change)
        })
    }
}
