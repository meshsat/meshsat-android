package net.meshsat.android.hubmsg

import org.json.JSONObject

/**
 * The payloads of hub_msg on the wire (the Hub's docs/bridge-hub-protocol.md "Messaging v1"):
 * a sender's submission on its own msg/out (C4), the Hub's delivery on a recipient's msg/in, the
 * recipient's answer on its msg/ack (C5), and on a sender's msg/status an event (C6) or the Hub's
 * refusal record. The same fields as the Bridge's wire.go; this phone is a bridge principal
 * (C4.1), so its topics are the bridges' topics under its own id.
 */
object HubMsgWire {
    /** The one content type of stage 2. */
    const val CONTENT_TYPE_TEXT = "text/plain;charset=utf-8"

    /** The largest body the Hub admits, in bytes. */
    const val MAX_BODY = 16 * 1024

    /** The bridge id of a "bridge:<id>" principal, or "". */
    fun bridgeOf(principal: String): String {
        if (!principal.startsWith("bridge:")) return ""
        val id = principal.removePrefix("bridge:")
        return if (id.isEmpty() || id.any { it == '/' || it == '+' || it == '#' }) "" else id
    }

    /** The principal of a bridge id. */
    fun principal(bridgeId: String) = "bridge:$bridgeId"
}

/** A sender's submission (C4). [body] is the text; its UTF-8 bytes are what the digest covers. */
data class HubSubmission(
    val v: Int = 1,
    val opId: String,
    val mid: String,
    val to: String,
    val contentType: String = HubMsgWire.CONTENT_TYPE_TEXT,
    val midExpiresAt: Long,
    val expiresAt: Long,
    val contentSha256: String,
    val body: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", v); put("op_id", opId); put("mid", mid); put("to", to); put("content_type", contentType)
        put("mid_expires_at", midExpiresAt); put("expires_at", expiresAt); put("content_sha256", contentSha256); put("body", body)
    }.toString()

    companion object {
        /** A submission with its digest. */
        fun create(opId: String, mid: String, to: String, body: String, midExpiresAt: Long, expiresAt: Long): HubSubmission =
            HubSubmission(opId = opId, mid = mid, to = to, midExpiresAt = midExpiresAt, expiresAt = expiresAt, body = body,
                contentSha256 = HubMsgDigest.sha256Hex(mid, to, HubMsgWire.CONTENT_TYPE_TEXT, midExpiresAt, body.toByteArray(Charsets.UTF_8)))

        fun fromJson(s: String): HubSubmission? = try {
            val o = JSONObject(s)
            HubSubmission(o.optInt("v"), o.optString("op_id"), o.optString("mid"), o.optString("to"), o.optString("content_type"),
                o.optLong("mid_expires_at"), o.optLong("expires_at"), o.optString("content_sha256"), o.optString("body"))
        } catch (e: Exception) { null }
    }
}

/** The Hub's delivery on a recipient's msg/in: the submission's fields and the sender (C3's origin). */
data class HubDelivery(
    val v: Int = 1,
    val opId: String,
    val mid: String,
    val from: String,
    val to: String,
    val contentType: String,
    val midExpiresAt: Long,
    val expiresAt: Long,
    val contentSha256: String,
    val body: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", v); put("op_id", opId); put("mid", mid); put("from", from); put("to", to); put("content_type", contentType)
        put("mid_expires_at", midExpiresAt); put("expires_at", expiresAt); put("content_sha256", contentSha256); put("body", body)
    }.toString()

    companion object {
        fun fromJson(s: String): HubDelivery? = try {
            val o = JSONObject(s)
            HubDelivery(o.optInt("v"), o.optString("op_id"), o.optString("mid"), o.optString("from"), o.optString("to"),
                o.optString("content_type"), o.optLong("mid_expires_at"), o.optLong("expires_at"), o.optString("content_sha256"),
                o.optString("body"))
        } catch (e: Exception) { null }
    }
}

/** The recipient's answer on its own msg/ack (C5), naming the origin it committed under. */
data class HubAck(
    val v: Int = 1,
    val opId: String,
    val mid: String,
    val from: String,
    val result: String,
    val reason: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("v", v); put("op_id", opId); put("mid", mid); put("from", from); put("result", result); put("reason", reason)
    }.toString()

    companion object {
        fun fromJson(s: String): HubAck? = try {
            val o = JSONObject(s)
            HubAck(o.optInt("v"), o.optString("op_id"), o.optString("mid"), o.optString("from"), o.optString("result"), o.optString("reason"))
        } catch (e: Exception) { null }
    }
}

/**
 * One piece of evidence about an operation (C6). [source] is where this phone took it from
 * ("local" for its own broker session, "status_topic" for its msg/status); it is never published.
 */
data class HubEvent(
    val v: Int = 1,
    val source: String = "",
    val opId: String,
    val mid: String = "",
    val recipient: String = "",
    val eventId: String,
    val rev: Int = 0,
    val cls: String,
    val kind: String,
    val reason: String = "",
    val authority: String = "",
    val scope: String = "",
    val detail: String = "",
    val at: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("v", v); put("op_id", opId); put("mid", mid); put("recipient", recipient); put("event_id", eventId); put("rev", rev)
        put("class", cls); put("kind", kind); put("reason", reason); put("authority", authority); put("scope", scope)
        put("detail", detail); put("at", at)
    }.toString()

    companion object {
        fun fromJsonObject(o: JSONObject): HubEvent = HubEvent(
            v = o.optInt("v"), source = o.optString("source"), opId = o.optString("op_id"), mid = o.optString("mid"),
            recipient = o.optString("recipient"), eventId = o.optString("event_id"), rev = o.optInt("rev"), cls = o.optString("class"),
            kind = o.optString("kind"), reason = o.optString("reason"), authority = o.optString("authority"),
            scope = o.optString("scope"), detail = o.optString("detail"), at = o.optString("at"),
        )
    }
}

/** The Hub's answer to a submission it did not admit. Not evidence of an operation: no reducer takes it in. */
data class HubRefusal(val v: Int = 1, val opId: String, val mid: String, val reason: String) {
    fun toJson(): String = JSONObject().apply {
        put("v", v); put("type", "refusal"); put("op_id", opId); put("mid", mid); put("reason", reason)
    }.toString()
}

/** One msg/status payload: an event, or a refusal. */
sealed class HubStatus {
    data class Ev(val event: HubEvent) : HubStatus()
    data class Ref(val refusal: HubRefusal) : HubStatus()

    companion object {
        /** Null for a payload that is neither (not JSON, not version 1, another type). */
        fun parse(payload: String): HubStatus? {
            val o = try { JSONObject(payload) } catch (e: Exception) { return null }
            if (o.optInt("v") != 1) return null
            return when (o.optString("type")) {
                "refusal" -> Ref(HubRefusal(1, o.optString("op_id"), o.optString("mid"), o.optString("reason")))
                // The receiving party annotates where it took the event from.
                "" -> Ev(HubEvent.fromJsonObject(o).copy(source = ""))
                else -> null
            }
        }
    }
}

/**
 * The order-independent reducer of an operation's evidence (C6) and the receipt kinds (C6a), as
 * the Bridge's and the Hub's reducer.go: the set union of the valid events by event id; the state
 * is the strongest class in it; completed and failed together mark a conflict; rev decides nothing.
 */
object HubMsgReducer {
    data class Operation(val opId: String, val mid: String, val recipient: String)

    data class Projection(
        val state: String,
        val conflict: Boolean,
        val failedReasons: List<String>,
        val evidence: List<String>,
        val rejected: List<String>,
    )

    /** Which kind may carry which class under which authority (C6a). */
    val ALLOWED_KINDS: Set<Triple<String, String, String>> = setOf(
        Triple("hub_broker_puback", "accepted", "local"),
        Triple("hub_forwarded", "forwarded", "hub"),
        Triple("recipient_app_stored", "completed", "recipient"),
        Triple("recipient_refused", "failed", "recipient"),
        Triple("recipient_refused", "expired", "recipient"),
        Triple("hub_refused", "failed", "hub"),
        Triple("hub_expired", "expired", "hub"),
        Triple("hub_unknown", "unknown", "hub"),
        Triple("recipient_busy", "unknown", "recipient"),
    )

    /** Where evidence of each authority may come from. */
    val SOURCE_OF: Map<String, String> = mapOf("local" to "local", "hub" to "status_topic", "recipient" to "status_topic")

    /** The strength of the classes, strongest first. */
    val PROJECTION_ORDER: List<String> = listOf("completed", "failed", "expired", "forwarded", "unknown", "accepted", "pending")

    /** Whether [e] may join [op]'s evidence. */
    fun valid(op: Operation, e: HubEvent): Boolean =
        e.opId == op.opId && e.mid == op.mid && e.recipient == op.recipient &&
            Triple(e.kind, e.cls, e.authority) in ALLOWED_KINDS &&
            SOURCE_OF[e.authority] != null && SOURCE_OF[e.authority] == e.source && HubMsgIds.validV7(e.eventId)

    fun reduce(op: Operation, events: List<HubEvent>): Projection {
        val held = LinkedHashMap<String, HubEvent>()
        val rejected = HashSet<String>()
        for (e in events) {
            if (valid(op, e)) held.putIfAbsent(e.eventId, e) else rejected += e.eventId
        }
        val classes = held.values.map { it.cls }.toSet()
        val reasons = held.values.filter { (it.cls == "failed" || it.cls == "expired") && it.reason.isNotEmpty() }.map { it.reason }.toSortedSet()
        val state = PROJECTION_ORDER.firstOrNull { it in classes } ?: "pending"
        return Projection(
            state = state,
            conflict = "completed" in classes && "failed" in classes,
            failedReasons = reasons.toList(),
            evidence = held.keys.sorted(),
            rejected = rejected.filter { it !in held }.sorted(),
        )
    }
}
