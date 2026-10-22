package net.meshsat.android.hub.directory

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** One address of a directory contact. */
data class DirAddress(
    val kind: String,
    val value: String,
    val label: String = "",
    val primaryRank: Int = 0,
)

/** One contact of the tenant's directory, as the Hub signed it. */
data class DirContact(
    val id: String,
    val displayName: String,
    val org: String = "",
    val role: String = "",
    val team: String = "",
    val addresses: List<DirAddress> = emptyList(),
)

/** One group of the tenant's directory; [memberIds] are contacts of the same snapshot. */
data class DirGroup(
    val id: String,
    val name: String,
    val kind: String,
    val memberIds: List<String> = emptyList(),
)

/** A row of a snapshot that was not taken, and why (the Bridge's HubSkip). */
data class DirSkip(val kind: String, val id: String, val reason: String, val detail: String = "")

/**
 * What the phone holds of the Hub's directory: the key it trusts, the snapshot it applied last
 * and the version of the last rotation it followed. A snapshot and a rotation share the tenant's
 * version sequence but are counted apart (contract C7.6, as on the Bridge since r19).
 */
data class DirectoryState(
    /**
     * The provisioning context the directory belongs to (broker URL, bridge id, topic root, tenant;
     * HubDirectoryClient.provisioningContext): a provisioning that changes it starts the directory
     * over, even with the same signing key (r40 review R40-R3).
     */
    val context: String = "",
    /** The tenant the provisioning bundle names (tenant_id); empty for a bundle without it. */
    val expectedTenant: String = "",
    /** The trusted key, PKIX DER in base64; empty while none is pinned. */
    val pinnedKey: String = "",
    /** Where the key came from: "bundle", "https" or "rotation". */
    val pinSource: String = "",
    val tenantId: String = "",
    val version: Long = 0,
    val keyVersion: Long = 0,
    val contacts: List<DirContact> = emptyList(),
    val groups: List<DirGroup> = emptyList(),
    val skipped: List<DirSkip> = emptyList(),
    val appliedAt: Long = 0,
    val checkedAt: Long = 0,
    val refusedReason: String = "",
    val refusedDetail: String = "",
    val refusedAt: Long = 0,
) {
    val pinnedPkix: ByteArray? get() = if (pinnedKey.isEmpty()) null else Base64.getDecoder().decode(pinnedKey)
    val keyId: String get() = pinnedPkix?.let { DirectoryEnvelope.keyId(it) } ?: ""

    fun toJson(): JSONObject = JSONObject()
        .put("context", context)
        .put("expected_tenant", expectedTenant)
        .put("pinned_key", pinnedKey)
        .put("pin_source", pinSource)
        .put("tenant_id", tenantId)
        .put("version", version)
        .put("key_version", keyVersion)
        .put("contacts", JSONArray(contacts.map { c ->
            JSONObject().put("id", c.id).put("display_name", c.displayName).put("org", c.org)
                .put("role", c.role).put("team", c.team)
                .put("addresses", JSONArray(c.addresses.map { a ->
                    JSONObject().put("kind", a.kind).put("value", a.value).put("label", a.label)
                        .put("primary_rank", a.primaryRank)
                }))
        }))
        .put("groups", JSONArray(groups.map { g ->
            JSONObject().put("id", g.id).put("name", g.name).put("kind", g.kind).put("member_ids", JSONArray(g.memberIds))
        }))
        .put("skipped", JSONArray(skipped.map { s ->
            JSONObject().put("kind", s.kind).put("id", s.id).put("reason", s.reason).put("detail", s.detail)
        }))
        .put("applied_at", appliedAt)
        .put("checked_at", checkedAt)
        .put("refused_reason", refusedReason)
        .put("refused_detail", refusedDetail)
        .put("refused_at", refusedAt)

    companion object {
        fun fromJson(o: JSONObject): DirectoryState = DirectoryState(
            context = o.optString("context", ""),
            expectedTenant = o.optString("expected_tenant", ""),
            pinnedKey = o.optString("pinned_key", ""),
            pinSource = o.optString("pin_source", ""),
            tenantId = o.optString("tenant_id", ""),
            version = o.optLong("version", 0),
            keyVersion = o.optLong("key_version", 0),
            contacts = objects(o.optJSONArray("contacts")).map { c ->
                DirContact(
                    id = c.optString("id", ""),
                    displayName = c.optString("display_name", ""),
                    org = c.optString("org", ""),
                    role = c.optString("role", ""),
                    team = c.optString("team", ""),
                    addresses = objects(c.optJSONArray("addresses")).map { a ->
                        DirAddress(a.optString("kind", ""), a.optString("value", ""), a.optString("label", ""), a.optInt("primary_rank", 0))
                    },
                )
            },
            groups = objects(o.optJSONArray("groups")).map { g ->
                DirGroup(g.optString("id", ""), g.optString("name", ""), g.optString("kind", ""), strings(g.optJSONArray("member_ids")))
            },
            skipped = objects(o.optJSONArray("skipped")).map { s ->
                DirSkip(s.optString("kind", ""), s.optString("id", ""), s.optString("reason", ""), s.optString("detail", ""))
            },
            appliedAt = o.optLong("applied_at", 0),
            checkedAt = o.optLong("checked_at", 0),
            refusedReason = o.optString("refused_reason", ""),
            refusedDetail = o.optString("refused_detail", ""),
            refusedAt = o.optLong("refused_at", 0),
        )

        internal fun objects(a: JSONArray?): List<JSONObject> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

        internal fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }
    }
}

/**
 * The Bridge's apply rules (internal/directory/hubapply.go) for a phone, which holds only what the
 * Hub sent: a snapshot replaces the whole set. Pure, so the rules have tests. [MESHSAT-1642]
 */
object DirectoryApply {
    const val OTHER_TENANT = "other_tenant"
    const val NOT_NEWER = "not_newer"
    const val NOT_A_ROTATION = "not_a_rotation"
    const val NOT_A_SNAPSHOT = "not_a_snapshot"
    const val SKIP_UNKNOWN_KIND = "unknown_kind"
    const val SKIP_ADDRESS_TAKEN = "address_taken"
    const val SKIP_NO_NAME = "no_display_name"
    const val SKIP_UNKNOWN_MEMBER = "unknown_member"

    /** The Bridge's address kinds (internal/directory/store.go AllKinds). */
    val ADDRESS_KINDS = setOf(
        "SMS", "MESHTASTIC", "APRS", "IRIDIUM_SBD", "IRIDIUM_IMT", "CELLULAR", "TAK", "RETICULUM",
        "ZIGBEE", "BLE", "WEBHOOK", "EMAIL", "MQTT", "TETRA", "CELL",
    )
    val GROUP_KINDS = setOf("TEAM", "ROLE", "LIST", "MLS_GROUP")

    /** The state after one record, snapshot or answer; [applied] false carries the refusal. */
    data class Result(val state: DirectoryState, val applied: Boolean, val reason: String = "", val detail: String = "")

    private fun refuse(state: DirectoryState, now: Long, reason: String, detail: String) =
        Result(state.copy(refusedReason = reason, refusedDetail = detail, refusedAt = now), false, reason, detail)

    // checkHubVersion: the tenant must be named and the one this phone follows, the version above held.
    private fun check(state: DirectoryState, what: String, tenant: String, version: Long, held: Long): Pair<String, String>? = when {
        tenant.isEmpty() -> OTHER_TENANT to "the $what names no tenant"
        state.expectedTenant.isNotEmpty() && state.expectedTenant != tenant ->
            OTHER_TENANT to "this phone was provisioned for tenant \"${state.expectedTenant}\", the $what is \"$tenant\""
        state.tenantId.isNotEmpty() && state.tenantId != tenant ->
            OTHER_TENANT to "this phone follows tenant \"${state.tenantId}\", the $what is \"$tenant\""
        version <= held -> NOT_NEWER to "$what version $version, this phone holds $held"
        else -> null
    }

    private fun pinned(state: DirectoryState): ByteArray =
        state.pinnedPkix ?: throw DirectoryEnvelope.Refused(DirectoryEnvelope.UNKNOWN_KEY, "no directory key pinned")

    /**
     * A rotation record: signed by the key this phone trusts, naming a P-256 key that hashes to
     * its id, for this phone's tenant, above the last rotation followed. The new key is pinned.
     */
    fun rotation(state: DirectoryState, env: JSONObject, now: Long): Result {
        val rec = try {
            JSONObject(String(DirectoryEnvelope.open(env, pinned(state)), Charsets.UTF_8))
        } catch (e: DirectoryEnvelope.Refused) {
            return refuse(state, now, e.reason, e.message ?: "")
        } catch (e: Exception) {
            return refuse(state, now, DirectoryEnvelope.FORMAT, "the record is not JSON")
        }
        if (rec.optString("type", "") != "rotation") {
            return refuse(state, now, NOT_A_ROTATION, "the payload is a \"${rec.optString("type", "")}\", not a rotation")
        }
        val newKey = DirectoryEnvelope.bytes(rec, "new_key_pkix")
        if (newKey == null || newKey.isEmpty() || DirectoryEnvelope.keyId(newKey) != rec.optString("new_key_id", "")) {
            return refuse(state, now, DirectoryEnvelope.UNKNOWN_KEY, "the new key does not hash to its id")
        }
        try {
            DirectoryEnvelope.p256(newKey)
        } catch (e: DirectoryEnvelope.Refused) {
            return refuse(state, now, e.reason, "new key: ${e.message}")
        }
        val tenant = rec.optString("tenant_id", "")
        val version = rec.optLong("version", 0)
        check(state, "rotation", tenant, version, state.keyVersion)?.let { (r, d) -> return refuse(state, now, r, d) }
        return Result(
            state.copy(
                pinnedKey = Base64.getEncoder().encodeToString(newKey), pinSource = "rotation",
                tenantId = tenant, keyVersion = version, refusedReason = "", refusedDetail = "", refusedAt = 0,
            ),
            true,
        )
    }

    /** A full snapshot, verified under the pinned key, replaces what the phone holds. */
    fun snapshot(state: DirectoryState, env: JSONObject, now: Long): Result {
        val p = try {
            JSONObject(String(DirectoryEnvelope.open(env, pinned(state)), Charsets.UTF_8))
        } catch (e: DirectoryEnvelope.Refused) {
            return refuse(state, now, e.reason, e.message ?: "")
        } catch (e: Exception) {
            return refuse(state, now, DirectoryEnvelope.FORMAT, "the snapshot is not JSON")
        }
        val type = p.optString("type", "")
        if (type != "" && type != "snapshot") return refuse(state, now, NOT_A_SNAPSHOT, "the payload is a \"$type\"")
        val tenant = p.optString("tenant_id", "")
        val version = p.optLong("version", 0)
        check(state, "snapshot", tenant, version, state.version)?.let { (r, d) -> return refuse(state, now, r, d) }

        val skipped = mutableListOf<DirSkip>()
        val contacts = mutableListOf<DirContact>()
        val taken = mutableSetOf<Pair<String, String>>()
        val ids = mutableSetOf<String>()
        for (c in DirectoryState.objects(p.optJSONArray("contacts"))) {
            val id = c.optString("id", "")
            if (id.isEmpty() || !ids.add(id)) continue
            val name = c.optString("display_name", "")
            if (name.isEmpty()) {
                skipped += DirSkip("contact", id, SKIP_NO_NAME)
                ids.remove(id)
                continue
            }
            val addresses = mutableListOf<DirAddress>()
            for (a in DirectoryState.objects(c.optJSONArray("addresses"))) {
                val kind = a.optString("kind", "")
                val value = a.optString("value", "")
                val aid = a.optString("id", "")
                when {
                    kind !in ADDRESS_KINDS -> skipped += DirSkip("address", aid, SKIP_UNKNOWN_KIND, kind)
                    value.isEmpty() -> skipped += DirSkip("address", aid, SKIP_UNKNOWN_KIND, "no value")
                    !taken.add(kind to value) -> skipped += DirSkip("address", aid, SKIP_ADDRESS_TAKEN, "$kind $value")
                    else -> addresses += DirAddress(kind, value, a.optString("label", ""), a.optInt("primary_rank", 0))
                }
            }
            contacts += DirContact(
                id, name, c.optString("org", ""), c.optString("role", ""), c.optString("team", ""),
                addresses.sortedBy { it.primaryRank },
            )
        }
        val groups = mutableListOf<DirGroup>()
        for (g in DirectoryState.objects(p.optJSONArray("groups"))) {
            val id = g.optString("id", "")
            if (id.isEmpty() || groups.any { it.id == id }) continue
            val kind = g.optString("kind", "").uppercase().ifEmpty { "LIST" }
            if (kind !in GROUP_KINDS) {
                skipped += DirSkip("group", id, SKIP_UNKNOWN_KIND, g.optString("kind", ""))
                continue
            }
            val members = mutableListOf<String>()
            for (m in DirectoryState.strings(g.optJSONArray("member_ids"))) {
                if (m in ids) members += m else skipped += DirSkip("member", "$id/$m", SKIP_UNKNOWN_MEMBER, "not a contact of this snapshot")
            }
            groups += DirGroup(id, g.optString("name", "").ifEmpty { id }, kind, members.distinct())
        }
        return Result(
            state.copy(
                tenantId = tenant, version = version, contacts = contacts.sortedBy { it.displayName.lowercase() },
                groups = groups.sortedBy { it.name.lowercase() }, skipped = skipped, appliedAt = now,
                refusedReason = "", refusedDetail = "", refusedAt = 0,
            ),
            true,
        )
    }

    /**
     * The answer of GET /api/relay/directory: the rotation records in order, each moving the pin,
     * then the snapshot under the key they lead to. The first refusal stops it; what was applied
     * before it stays.
     */
    fun answer(state: DirectoryState, answer: JSONObject, now: Long): Result {
        var s = state
        var applied = false
        for (env in DirectoryState.objects(answer.optJSONArray("rotations"))) {
            val r = rotation(s, env, now)
            if (!r.applied) return r.copy(applied = applied)
            s = r.state
            applied = true
        }
        val snap = answer.optJSONObject("snapshot") ?: return Result(s, applied)
        val r = snapshot(s, snap, now)
        return if (r.applied) r else r.copy(applied = applied)
    }
}
