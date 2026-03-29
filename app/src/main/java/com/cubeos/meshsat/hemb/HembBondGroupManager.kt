package com.cubeos.meshsat.hemb

import android.content.Context
import android.util.Base64
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.HembBondGroupEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages HeMB bond groups — configuration for which bearers are bonded together.
 *
 * Sources:
 * 1. QR provisioning: scan a meshsat://bond/ URL to import a bond group
 * 2. Hub push: hemb_bond_create/hemb_bond_delete commands via MQTT
 * 3. Local: manual configuration in Settings UI
 */
object HembBondGroupManager {

    private const val QR_PREFIX = "meshsat://bond/"

    /**
     * Bond group QR binary format:
     * Version(1) | GroupID(16) | Label length(1) | Label(N) | MemberCount(1)
     * Per member: InterfaceIDLength(1) | InterfaceID(N)
     * CostBudget(8, IEEE 754 double, big-endian)
     */

    /**
     * Import a bond group from a meshsat://bond/ QR URL.
     * @return The imported bond group config, or null if parsing failed.
     */
    suspend fun importFromQr(url: String, context: Context): HembConfig? {
        if (!url.startsWith(QR_PREFIX)) return null

        return try {
            val encoded = url.substring(QR_PREFIX.length)
            val data = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val config = parseQrPayload(data) ?: return null

            // Persist to Room DB.
            val db = AppDatabase.getInstance(context)
            db.hembBondGroupDao().insert(
                HembBondGroupEntity(
                    id = config.id,
                    label = config.label,
                    members = JSONArray(config.members).toString(),
                    costBudget = config.costBudget,
                )
            )
            config
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseQrPayload(data: ByteArray): HembConfig? {
        if (data.isEmpty()) return null
        var offset = 0

        // Version
        val version = data[offset++].toInt() and 0xFF
        if (version != 1) return null

        // Group ID (16 bytes as hex)
        if (offset + 16 > data.size) return null
        val groupId = data.sliceArray(offset until offset + 16)
            .joinToString("") { "%02x".format(it) }
        offset += 16

        // Label
        if (offset >= data.size) return null
        val labelLen = data[offset++].toInt() and 0xFF
        if (offset + labelLen > data.size) return null
        val label = String(data, offset, labelLen, Charsets.UTF_8)
        offset += labelLen

        // Members
        if (offset >= data.size) return null
        val memberCount = data[offset++].toInt() and 0xFF
        val members = mutableListOf<String>()
        for (i in 0 until memberCount) {
            if (offset >= data.size) return null
            val idLen = data[offset++].toInt() and 0xFF
            if (offset + idLen > data.size) return null
            members.add(String(data, offset, idLen, Charsets.UTF_8))
            offset += idLen
        }

        // Cost budget (optional — 8 bytes IEEE 754 double)
        val costBudget = if (offset + 8 <= data.size) {
            java.nio.ByteBuffer.wrap(data, offset, 8)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .double
        } else 0.0

        return HembConfig(
            id = groupId,
            label = label,
            members = members,
            costBudget = costBudget,
        )
    }

    /**
     * Apply a Hub-pushed bond group create command.
     * Called from GatewayService when hemb_bond_create command arrives.
     */
    suspend fun applyHubCreate(payload: String, context: Context): HembConfig? {
        return try {
            val json = JSONObject(payload)
            val config = HembConfig(
                id = json.optString("bond_id", ""),
                label = json.optString("label", ""),
                members = json.optJSONArray("members")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                costBudget = json.optDouble("cost_budget", 0.0),
            )
            if (config.id.isEmpty()) return null

            val db = AppDatabase.getInstance(context)
            db.hembBondGroupDao().insert(
                HembBondGroupEntity(
                    id = config.id,
                    label = config.label,
                    members = JSONArray(config.members).toString(),
                    costBudget = config.costBudget,
                )
            )
            config
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Delete a bond group (Hub push or local).
     */
    suspend fun delete(bondId: String, context: Context) {
        val db = AppDatabase.getInstance(context)
        db.hembBondGroupDao().delete(bondId)
    }

    /**
     * Load all bond groups from the database.
     */
    suspend fun loadAll(context: Context): List<HembConfig> {
        val db = AppDatabase.getInstance(context)
        return db.hembBondGroupDao().getAll().map { entity ->
            val members = try {
                val arr = JSONArray(entity.members)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }
            HembConfig(
                id = entity.id,
                label = entity.label,
                members = members,
                costBudget = entity.costBudget,
            )
        }
    }

    /**
     * Build a HembBonder for a bond group using the BearerSelector.
     * Only includes bearers whose interface IDs are in the bond group members list.
     */
    fun buildBonder(
        config: HembConfig,
        selector: BearerSelector,
        deliverFn: ((ByteArray) -> Unit)? = null,
        eventListener: HembEventListener? = null,
    ): HembBonder? {
        val allBearers = selector.activeBearers()
        val groupBearers = if (config.members.isEmpty()) {
            allBearers // empty members = all bearers
        } else {
            allBearers.filter { it.interfaceId in config.members }
        }
        if (groupBearers.isEmpty()) return null

        return HembBonder(
            bearers = groupBearers,
            deliverFn = deliverFn,
            eventListener = eventListener,
        )
    }
}
