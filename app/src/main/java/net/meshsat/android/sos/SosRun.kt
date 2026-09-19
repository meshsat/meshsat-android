package net.meshsat.android.sos

import net.meshsat.android.data.MessageDeliveryEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * One SOS, or one test of the alarm (MESHSAT-1249). Its deliveries sit in the ordinary delivery queue
 * with msg_ref "sos:<id>:<route>", so they survive a restart and are retried like any message; the
 * cancellation that follows a cancelled SOS goes as "sos:<id>:cancel:<route>". The run itself is kept
 * in settings so the banner, the result screen and the veto on a cancelled SOS survive a restart too.
 */
data class SosRun(
    val id: Long,
    val test: Boolean,
    /** "button", or "checkin" when the check-in timer (dead man's switch) ran out. */
    val trigger: String,
    val name: String,
    val fix: SosMessages.Fix?,
    /** What was queued, in order: [Route.key] is the msg_ref suffix. */
    val routes: List<Route>,
    /** Routes that were not possible, each as a sentence for the result screen. */
    val skipped: List<String>,
    /** The Hub is set up on this phone, so it is told over the internet as well. */
    val hubWanted: Boolean,
    val deviceId: String,
    val hubAlertId: String,
    val hubSentAt: Long? = null,
    val cancelledAt: Long? = null,
    val cancelHubSentAt: Long? = null,
    /** A test that has run its course or was stopped. */
    val finishedAt: Long? = null,
) {
    data class Route(val key: String, val label: String)

    val active: Boolean get() = cancelledAt == null && finishedAt == null
    val refPrefix: String get() = refPrefix(id)

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("test", test)
        put("trigger", trigger)
        put("name", name)
        fix?.let {
            put("lat", it.lat); put("lon", it.lon); put("fix_time", it.timeMs)
            it.accuracyM?.let { a -> put("acc", a.toDouble()) }
        }
        put("routes", JSONArray().apply { routes.forEach { put(JSONObject().put("key", it.key).put("label", it.label)) } })
        put("skipped", JSONArray(skipped))
        put("hub_wanted", hubWanted)
        put("device_id", deviceId)
        put("hub_alert_id", hubAlertId)
        hubSentAt?.let { put("hub_sent_at", it) }
        cancelledAt?.let { put("cancelled_at", it) }
        cancelHubSentAt?.let { put("cancel_hub_sent_at", it) }
        finishedAt?.let { put("finished_at", it) }
    }.toString()

    companion object {
        fun refPrefix(id: Long) = "sos:$id:"

        /** The run id in a msg_ref, and whether the delivery is a cancellation; null if not an SOS delivery. */
        fun parseRef(ref: String): Pair<Long, Boolean>? {
            if (!ref.startsWith("sos:")) return null
            val parts = ref.split(':')
            val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return id to (parts.getOrNull(2) == "cancel")
        }

        fun fromJson(s: String): SosRun? = try {
            if (s.isBlank()) null else {
                val o = JSONObject(s)
                val routes = o.optJSONArray("routes") ?: JSONArray()
                val skipped = o.optJSONArray("skipped") ?: JSONArray()
                SosRun(
                    id = o.getLong("id"),
                    test = o.optBoolean("test"),
                    trigger = o.optString("trigger", "button"),
                    name = o.optString("name"),
                    fix = if (o.has("lat") && o.has("lon")) SosMessages.Fix(
                        o.getDouble("lat"), o.getDouble("lon"),
                        if (o.has("acc")) o.getDouble("acc").toFloat() else null,
                        o.optLong("fix_time"),
                    ) else null,
                    routes = (0 until routes.length()).map { routes.getJSONObject(it).let { r -> Route(r.getString("key"), r.getString("label")) } },
                    skipped = (0 until skipped.length()).map { skipped.getString(it) },
                    hubWanted = o.optBoolean("hub_wanted"),
                    deviceId = o.optString("device_id"),
                    hubAlertId = o.optString("hub_alert_id"),
                    hubSentAt = if (o.has("hub_sent_at")) o.getLong("hub_sent_at") else null,
                    cancelledAt = if (o.has("cancelled_at")) o.getLong("cancelled_at") else null,
                    cancelHubSentAt = if (o.has("cancel_hub_sent_at")) o.getLong("cancel_hub_sent_at") else null,
                    finishedAt = if (o.has("finished_at")) o.getLong("finished_at") else null,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Where one route of an SOS stands, as the result screen and the notification say it. */
data class SosRouteStatus(
    val label: String,
    val state: State,
    val detail: String,
    /** The same route's cancellation, once the SOS was cancelled. */
    val cancel: State? = null,
    /** The route in a sentence: "satellite", "SMS to Anna". */
    val short: String = label,
) {
    enum class State { Waiting, Sending, Sent, Stopped, Failed }
}

object SosProgress {

    private const val HUB_LABEL = "Hub, over the internet"
    private const val HUB_SHORT = "the Hub online"

    private fun sentence(items: List<String>) =
        if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /** One status per route of [run], from its deliveries, plus the Hub's internet link. */
    fun routes(run: SosRun, deliveries: List<MessageDeliveryEntity>): List<SosRouteStatus> {
        val byRef = deliveries.associateBy { it.msgRef }
        val out = run.routes.map { route ->
            val del = byRef[run.refPrefix + route.key]
            val cancel = byRef[run.refPrefix + "cancel:" + route.key]?.let { stateOf(it) }
            val short = when (route.key) {
                "sat" -> "satellite"
                "mesh" -> "mesh"
                else -> route.label
            }
            if (del == null) SosRouteStatus(route.label, SosRouteStatus.State.Failed, "Could not be queued", short = short)
            else SosRouteStatus(route.label, stateOf(del), detailOf(del), cancel, short)
        }.toMutableList()
        if (run.hubWanted) {
            out += when {
                run.hubSentAt != null -> SosRouteStatus(
                    HUB_LABEL, SosRouteStatus.State.Sent, "Sent",
                    if (run.cancelledAt == null || run.test) null
                    else if (run.cancelHubSentAt != null) SosRouteStatus.State.Sent else SosRouteStatus.State.Waiting,
                    HUB_SHORT,
                )
                !run.active -> SosRouteStatus(HUB_LABEL, SosRouteStatus.State.Stopped, "Stopped before the Hub could be reached", short = HUB_SHORT)
                else -> SosRouteStatus(HUB_LABEL, SosRouteStatus.State.Waiting, "Waiting for the Hub connection", short = HUB_SHORT)
            }
        }
        return out
    }

    fun stateOf(d: MessageDeliveryEntity): SosRouteStatus.State = when (d.status) {
        "sent", "delivered" -> SosRouteStatus.State.Sent
        "sending" -> SosRouteStatus.State.Sending
        "queued", "retry", "held" -> SosRouteStatus.State.Waiting
        "dead" -> if (d.lastError == "cancelled") SosRouteStatus.State.Stopped else SosRouteStatus.State.Failed
        else -> SosRouteStatus.State.Failed
    }

    private fun detailOf(d: MessageDeliveryEntity): String = when (stateOf(d)) {
        // A confirmation from the far end (MESHSAT-1246): the Hub's receipt by satellite, the
        // carrier's delivery report by SMS.
        SosRouteStatus.State.Sent -> when {
            d.ackStatus != "acked" -> "Sent"
            d.channel.startsWith("iridium") -> "Sent, and the Hub has it"
            d.channel.startsWith("sms") -> "Delivered to their phone"
            else -> "Sent"
        }
        SosRouteStatus.State.Sending -> "Sending now"
        SosRouteStatus.State.Stopped -> "Stopped"
        SosRouteStatus.State.Waiting ->
            if (d.retries == 0 && d.lastError.isBlank()) "Waiting to send"
            else "Trying again: ${d.lastError.ifBlank { "not sent yet" }}"
        SosRouteStatus.State.Failed -> d.lastError.ifBlank { "Not sent" }
    }

    /** One line for the notification: "Sent by SMS to Anna and the Hub online. Still trying satellite." */
    fun summary(statuses: List<SosRouteStatus>): String {
        if (statuses.isEmpty()) return "No way to send it: add emergency contacts or connect your node."
        val sent = statuses.filter { it.state == SosRouteStatus.State.Sent }.map { it.short }
        val waiting = statuses.filter { it.state == SosRouteStatus.State.Waiting || it.state == SosRouteStatus.State.Sending }.map { it.short }
        val parts = mutableListOf<String>()
        if (sent.isNotEmpty()) parts += "Sent by ${sentence(sent)}."
        if (waiting.isNotEmpty()) parts += "Still trying ${sentence(waiting)}."
        if (parts.isEmpty()) parts += "Nothing could be sent."
        return parts.joinToString(" ")
    }

    /** Everything a test sent has finished one way or the other. */
    fun allSettled(run: SosRun, statuses: List<SosRouteStatus>): Boolean =
        statuses.none { it.state == SosRouteStatus.State.Waiting || it.state == SosRouteStatus.State.Sending } &&
            (!run.hubWanted || run.hubSentAt != null)
}
