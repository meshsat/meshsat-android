package net.meshsat.android.hubmsg

import org.json.JSONObject

/**
 * A Hub that does what the Hub does with a frame (docs/bridge-hub-protocol.md "Messaging v1"; the
 * Hub's own tests run its real code), for the phone's route tests, as the Bridge's contractHub: a
 * submission is admitted, delivered on the recipient's msg/in with hub_forwarded to the sender, and
 * a repeat of one is answered with every event of it again; an answer becomes the sender's evidence
 * by C5/C6a. A link that is not connected receives nothing (the phone's session is clean: what is
 * published while it is off the Hub is gone). Frames wait in a queue the test pumps.
 */
class ContractHub {
    data class Frame(val principal: String, val kind: String, val payload: String)

    val queue = ArrayDeque<Frame>()
    val routes = HashMap<String, HubMsgRoute>()
    val links = HashMap<String, Link>()
    val ops = HashMap<String, HubSubmission>()
    val senders = HashMap<String, String>()
    val statuses = mutableListOf<Frame>()
    val deliveries = mutableListOf<Frame>()
    var replays = 0
    var refuse: ((HubSubmission) -> String?)? = null

    /** One principal's session at this Hub. */
    inner class Link(override val principal: String) : HubMsgRoute.Publisher {
        var up = true
        var answer: HubMsgRoute.Published = HubMsgRoute.Published.Ok
        override fun isConnected() = up
        override fun publishMsg(kind: String, payload: String): HubMsgRoute.Published {
            if (!up) return HubMsgRoute.Published.NotSent
            if (answer != HubMsgRoute.Published.NotSent) queue.addLast(Frame(principal, kind, payload))
            return answer
        }
    }

    fun link(principal: String) = Link(principal).also { links[principal] = it }

    private fun event(s: HubSubmission, kind: String, cls: String, authority: String, reason: String = "") =
        HubEvent(opId = s.opId, mid = s.mid, recipient = s.to, eventId = HubMsgIds.newV7(System.currentTimeMillis()), rev = 1,
            cls = cls, kind = kind, reason = reason, authority = authority, at = "2026-10-19T00:00:00Z").toJson()

    /** Publishes on [to]'s msg/[kind]; a link that is down never gets it. */
    suspend fun publish(to: String, kind: String, payload: String) {
        val f = Frame(to, kind, payload)
        if (kind == "in") deliveries += f else statuses += f
        if (links[to]?.up != true) return
        val r = routes[to] ?: return
        if (kind == "in") r.handleIn(payload) else r.handleStatus(payload)
    }

    /** Publishes [f] again (a QoS 1 redelivery). */
    suspend fun replay(f: Frame) {
        if (links[f.principal]?.up != true) return
        val r = routes[f.principal] ?: return
        if (f.kind == "in") r.handleIn(f.payload) else r.handleStatus(f.payload)
    }

    /** Carries every queued frame until none is left. */
    suspend fun pump() {
        while (pumpOne()) Unit
    }

    /** Carries the first queued frame; false when there was none. */
    suspend fun pumpOne(): Boolean {
        run {
            val f = queue.removeFirstOrNull() ?: return false
            when (f.kind) {
                "out" -> {
                    val s = HubSubmission.fromJson(f.payload) ?: return@run
                    val prev = ops[s.opId]
                    if (prev != null && senders[s.opId] == f.principal) {
                        if (prev != s) {
                            publish(f.principal, "status", HubRefusal(opId = s.opId, mid = s.mid, reason = "op_conflict").toJson())
                            return@run
                        }
                        replays++
                        statuses.filter { it.principal == f.principal && JSONObject(it.payload).optString("op_id") == s.opId }
                            .toList().forEach { replay(it) }
                        return@run
                    }
                    val reason = refuse?.invoke(s)
                    if (reason != null) {
                        publish(f.principal, "status", HubRefusal(opId = s.opId, mid = s.mid, reason = reason).toJson())
                        return@run
                    }
                    ops[s.opId] = s
                    senders[s.opId] = f.principal
                    publish(s.to, "in", HubDelivery(1, s.opId, s.mid, f.principal, s.to, s.contentType, s.midExpiresAt, s.expiresAt,
                        s.contentSha256, s.body).toJson())
                    publish(f.principal, "status", event(s, "hub_forwarded", "forwarded", "hub"))
                }
                "ack" -> {
                    val a = HubAck.fromJson(f.payload) ?: return@run
                    val s = ops[a.opId] ?: return@run
                    val sender = senders[a.opId] ?: return@run
                    if (s.to != f.principal || sender != a.from || s.mid != a.mid) return@run
                    when {
                        a.result == "stored" -> publish(sender, "status", event(s, "recipient_app_stored", "completed", "recipient"))
                        a.reason == "receiver_busy" -> publish(sender, "status", event(s, "recipient_busy", "unknown", "recipient", a.reason))
                        a.reason == "expired_at_recipient" -> publish(sender, "status", event(s, "recipient_refused", "expired", "recipient", a.reason))
                        else -> publish(sender, "status", event(s, "recipient_refused", "failed", "recipient", a.reason))
                    }
                }
            }
        }
        return true
    }

    /** Publishes [kind] for the only operation, as the Hub would on its own (an expiry, a late answer). */
    suspend fun emit(kind: String, cls: String, authority: String, reason: String = "") {
        val (opId, s) = ops.entries.single().toPair()
        publish(senders.getValue(opId), "status", event(s, kind, cls, authority, reason))
    }
}
