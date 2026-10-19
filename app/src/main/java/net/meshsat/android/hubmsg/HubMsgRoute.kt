package net.meshsat.android.hubmsg

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Hub app route on the phone (hub_msg, HeMB-NG stage 2, MESHSAT-1584): a text to a contact's
 * "bridge:<id>" goes to the Hub on this phone's msg/out, and what the Hub reports on msg/status is
 * its delivery status in the chat, up to "delivered to the MeshSat app"; what the Hub delivers on
 * msg/in is committed in the inbox (C3) with its chat row, then acknowledged on msg/ack. The phone
 * is a bridge principal (C4.1), so these are the bridges' topics under its own id. The contract is
 * the Hub's docs/bridge-hub-protocol.md "Messaging v1"; this is the Bridge's internal/hubroute as
 * its r12 review accepted it (A11-A15), with one difference that the phone's session imposes:
 *
 * The phone's MQTT session is clean, so nothing the Hub publishes while it is off the Hub reaches it
 * later. Every connect therefore walks once more over every operation without a completion inside
 * the bound and repeats its submission (the walk the Bridge makes once per process start, A12);
 * the Hub answers a repeat with every event of it under the same ids (C4).
 */
class HubMsgRoute(
    private val store: HubMsgStore,
    private val pub: Publisher,
    private val now: () -> Long = System::currentTimeMillis,
    /** A message the moment it is stored, once per message (a notification). */
    private val onStored: (from: String, text: String, messageId: Long) -> Unit = { _, _, _ -> },
) {
    /** What the route needs from the Hub session (HubReporter). */
    interface Publisher {
        /** This phone's principal, "bridge:<its id>". */
        val principal: String
        fun isConnected(): Boolean

        /**
         * Publishes on this phone's msg/[kind] at QoS 1 and waits for the broker: [Published.Ok]
         * once it took it, [Published.NotSent] when nothing left the phone (not connected),
         * [Published.Unknown] when it may have reached the broker (a timeout).
         */
        fun publishMsg(kind: String, payload: String): Published
    }

    enum class Published { Ok, NotSent, Unknown }

    /** Why a send was not taken, or null. */
    sealed class SendResult {
        data class Queued(val messageId: Long) : SendResult()
        data class Refused(val why: String) : SendResult()
    }

    /** An operation's repeats: how many, when, whether an answer failed since, and in which connect. */
    private class Ask(var n: Int = 0, var at: Long = 0, var failed: Boolean = false, var connect: Int = -1)

    private val lock = Any()
    private val replay = HashMap<String, MutableSet<String>>() // op id -> missing events held only in memory
    private val asks = HashMap<String, Ask>()
    private var walk = HubOpCursor()
    private var walkDone = false
    private var connect = 0
    private var owedAt = HubOpCursor()
    /** One pass at a time: the connect's and the periodic one wait for each other (PH-R1). */
    private val passLock = Mutex()
    private var lastPrune = 0L

    // ---- send ----

    /**
     * Takes a text for [to] ("bridge:<id>"): the chat row and the operation in one transaction,
     * then a publish when the Hub is connected; otherwise it waits in the queue for the next one.
     */
    suspend fun send(to: String, text: String): SendResult {
        if (HubMsgWire.bridgeOf(to).isEmpty()) return SendResult.Refused("\"$to\" is not a bridge:<id> address")
        if (to == pub.principal) return SendResult.Refused("the address is this phone")
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes == 0) return SendResult.Refused("an empty message")
        if (bytes > HubMsgWire.MAX_BODY) return SendResult.Refused("$bytes bytes, the Hub takes ${HubMsgWire.MAX_BODY}")
        val t = now()
        val mid = HubMsgIds.newV7(t)
        val opId = HubMsgIds.newV7(t)
        // No route follows the Hub on the phone, so the operation lives as long as the message.
        val expires = t + MESSAGE_LIFETIME_MS
        val sub = HubSubmission.create(opId, mid, to, text, expires, expires)
        val id = store.createOutgoing(
            HubOp(opId = opId, mid = mid, recipient = to, messageId = 0, expiresAt = expires, createdAt = t, submission = sub.toJson()),
            sender = pub.principal, text = text,
        )
        publishPending()
        return SendResult.Queued(id)
    }

    /**
     * Publishes the operations the broker has not taken yet. A publish whose answer did not come
     * may have reached the Hub: it goes again, unchanged, and the Hub takes the repeat as one (C4).
     */
    suspend fun publishPending() {
        if (!pub.isConnected()) return
        for (op in store.unpublished(BATCH)) {
            // The connection the publish goes out on, taken before it: a reconnect while it is in
            // flight must not make it count for the new connection (PH-R1).
            val gen = generation()
            when (pub.publishMsg("out", op.submission)) {
                Published.Ok -> {
                    val t = now()
                    // A first publish in this connect is as good as its repeat: the walk skips it.
                    synchronized(lock) { asks.getOrPut(op.opId) { Ask() }.apply { at = t; connect = gen } }
                    store.markPublished(op.opId, t)
                }
                Published.NotSent, Published.Unknown -> return
            }
        }
    }

    // ---- msg/status ----

    /**
     * One msg/status payload: an event of one of this phone's operations, or the Hub's refusal of a
     * submission. Kept and taken into the chat row in one transaction; an event that could not be
     * kept makes its operation owed a repeat.
     */
    suspend fun handleStatus(payload: String) {
        val st = HubStatus.parse(payload) ?: run {
            Log.w(TAG, "a status that is neither an event nor a refusal, ignored")
            return
        }
        val (opId, eventId) = when (st) {
            is HubStatus.Ev -> st.event.opId to st.event.eventId
            is HubStatus.Ref -> st.refusal.opId to HubMsgStatus.refusalEventId(st.refusal.opId, st.refusal.reason)
        }
        val op = try {
            store.op(opId)
        } catch (e: Exception) {
            Log.w(TAG, "status not read against its operation; a repeat is owed: ${e.message}")
            oweReplay(opId, eventId)
            return
        } ?: return // not one of this phone's
        val event: HubEvent
        val valid: Boolean
        when (st) {
            is HubStatus.Ref -> {
                if (st.refusal.mid != op.mid) return
                event = HubEvent(opId = op.opId, eventId = eventId, cls = "failed", kind = "hub_refusal",
                    reason = st.refusal.reason, authority = "hub")
                valid = true
            }
            is HubStatus.Ev -> {
                event = st.event.copy(source = "status_topic")
                valid = HubMsgReducer.valid(HubMsgReducer.Operation(op.opId, op.mid, op.recipient), event)
            }
        }
        try {
            val kept = store.keepEvent(event, valid, now())
            if (kept.valid && kept.event.cls == "completed") {
                synchronized(lock) { replay.remove(op.opId); asks.remove(op.opId) }
            } else {
                synchronized(lock) { replay[op.opId]?.let { it.remove(kept.event.eventId); if (it.isEmpty()) replay.remove(op.opId) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "event not kept; a repeat is owed: ${e.message}")
            oweReplay(op.opId, event.eventId)
        }
    }

    /** An event of [opId] is missing: owed in the database when it can be written, in memory when not. */
    private suspend fun oweReplay(opId: String, eventId: String) {
        synchronized(lock) { asks[opId]?.failed = true }
        try {
            store.wantReplay(opId, eventId, now())
            return
        } catch (e: Exception) {
            Log.w(TAG, "owed repeat not kept, held in memory: ${e.message}")
        }
        synchronized(lock) { replay.getOrPut(opId) { HashSet() }.add(eventId) }
    }

    // ---- repeats ----

    /**
     * A connect: the walk starts again over every operation without a completion (the session is
     * clean), and asks each of them once in this connect, whatever its wait.
     */
    fun onConnected() {
        synchronized(lock) { walk = HubOpCursor(); walkDone = false; connect++ }
    }

    private fun generation(): Int = synchronized(lock) { connect }

    /** Whether [opId] was asked on connection [gen] already. */
    private fun askedOn(opId: String, gen: Int): Boolean = synchronized(lock) { asks[opId]?.connect == gen }

    private fun due(opId: String, t: Long): Boolean = synchronized(lock) {
        val a = asks[opId] ?: return true
        if (a.failed) return true
        var wait = 30_000L
        for (i in 1 until a.n) {
            if (wait >= 30 * 60_000L) break
            wait *= 2
        }
        t - a.at >= minOf(wait, 30 * 60_000L)
    }

    /** Repeats [op]'s submission, counted for connection [gen], the one the pass began on. */
    private suspend fun repeat(op: HubOp, t: Long, gen: Int): Boolean {
        // From here a failure to keep an answer counts against this repeat, one that comes before
        // the publish returns included.
        val a = synchronized(lock) { asks.getOrPut(op.opId) { Ask() }.also { it.failed = false } }
        if (pub.publishMsg("out", op.submission) != Published.Ok) {
            synchronized(lock) { a.failed = true } // nothing went out: not a reason to wait
            return false
        }
        synchronized(lock) { a.n++; a.at = t; a.connect = gen }
        if (op.publishedAt == 0L) runCatching { store.markPublished(op.opId, t) }
        return true
    }

    /**
     * Asks the Hub again, with the kept submission unchanged, for the events of every operation that
     * may be missing one, a bounded batch of each kind per pass: the owed (in the database, round
     * and round, and in memory), and while this connect's walk is not done, the next operations
     * without a completion. An obligation ends when its event or a completion is kept, or at the
     * bound, where the chat row reads "outcome unknown".
     */
    suspend fun requestReplays() {
        val t = now()
        // Everything this pass does counts for the connection it began on (PH-R1).
        val gen = generation()
        runCatching { store.resolveKeptMissing() }
        giveUpPastBound(t)
        if (!pub.isConnected()) return
        val asked = HashSet<String>() // repeated in this pass; one not due is not among them
        // An owed operation is asked as its wait allows; the walk asks each operation once per
        // connect, since what the Hub said while the phone was away is gone, whatever its wait.
        suspend fun ask(op: HubOp, walking: Boolean = false): Boolean {
            if (op.opId in asked) return true
            if (walking) {
                if (askedOn(op.opId, gen)) return true
            } else if (!due(op.opId, t)) {
                return true
            }
            val ok = repeat(op, t, gen)
            if (ok) asked.add(op.opId)
            return ok
        }

        val cur = synchronized(lock) { owedAt }
        val owed = runCatching { store.owed(t, REPLAY_WITHIN_MS, cur, OWED_BATCH) }.getOrDefault(emptyList())
        owed.forEach { ask(it) }
        synchronized(lock) {
            owedAt = if (owed.size < OWED_BATCH) HubOpCursor() else owed.last().let { HubOpCursor(it.replayAt, it.opId) }
        }

        for (id in synchronized(lock) { replay.keys.toList() }) {
            val op = try { store.op(id) } catch (e: Exception) { continue } // still owed
            if (op == null || op.submission.isEmpty() || op.replayGivenUpAt > 0) {
                synchronized(lock) { replay.remove(id) }
                continue
            }
            if (op.expiresAt + REPLAY_WITHIN_MS < t) {
                if (stillMissing(id)) giveUp(op, t)
                continue
            }
            if (stillMissing(id)) ask(op)
        }

        // The walk belongs to one connection: a pass that began before a reconnect leaves the new
        // connection's walk alone, its position and its end included (PH-R1).
        val (done, wcur) = synchronized(lock) { if (connect != gen) return else walkDone to walk }
        if (done) return
        val ops = try { store.toReconcile(t, REPLAY_WITHIN_MS, wcur, WALK_BATCH) } catch (e: Exception) { return }
        for (op in ops) {
            if (generation() != gen) return
            if (!ask(op, walking = true)) return // the walk stays here and goes on from it at the next pass
            synchronized(lock) { if (connect == gen) walk = HubOpCursor(op.createdAt, op.opId) else return }
        }
        if (ops.size < WALK_BATCH) synchronized(lock) { if (connect == gen) walkDone = true }
    }

    /** Strikes from an operation's in-memory missing events those kept by now; whether any is left. */
    private suspend fun stillMissing(opId: String): Boolean {
        if (runCatching { store.completed(opId) }.getOrDefault(false)) {
            synchronized(lock) { replay.remove(opId) }
            return false
        }
        val ids = synchronized(lock) { replay[opId]?.toList().orEmpty() }
        var left = 0
        for (id in ids) {
            if (runCatching { store.eventKept(id) }.getOrDefault(false)) {
                synchronized(lock) { replay[opId]?.remove(id) }
            } else left++
        }
        if (left == 0) synchronized(lock) { replay.remove(opId) }
        return left > 0
    }

    private suspend fun giveUpPastBound(t: Long) {
        val ops = runCatching { store.toGiveUp(t, REPLAY_WITHIN_MS, OWED_BATCH) }.getOrDefault(emptyList())
        ops.forEach { giveUp(it, t) }
    }

    private suspend fun giveUp(op: HubOp, t: Long) {
        try {
            store.giveUp(op.opId, t)
        } catch (e: Exception) {
            return // given up at the next pass
        }
        synchronized(lock) { replay.remove(op.opId); asks.remove(op.opId) }
        Log.w(TAG, "the Hub's outcome of an operation never reached this phone inside the bound; stopped asking")
    }

    // ---- msg/in ----

    /** One delivery: commit (C3, the chat row with it), then the answer on msg/ack, then the notice. */
    suspend fun handleIn(payload: String) {
        val d = HubDelivery.fromJson(payload)
        if (d == null || d.v != 1) {
            Log.w(TAG, "a delivery that does not parse, ignored")
            return
        }
        if (d.to != pub.principal || HubMsgWire.bridgeOf(d.from).isEmpty()) {
            Log.w(TAG, "a delivery not addressed to this phone, ignored")
            return
        }
        val ans = try {
            store.commitDelivery(d, now(), true, HubMsgInbox.CAPACITY)
        } catch (e: Exception) {
            // Nothing committed, nothing answered: the Hub delivers it again.
            Log.w(TAG, "delivery not committed: ${e.message}")
            return
        }
        // Committed; a lost answer is given again from the inbox at the Hub's next delivery.
        pub.publishMsg("ack", HubAck(opId = d.opId, mid = d.mid, from = d.from, result = ans.result, reason = ans.reason).toJson())
        if (ans.storedNow) onStored(d.from, d.body, ans.messageId)
    }

    // ---- the pass ----

    /** What the service runs at each connect and every [PASS_MS]: the queue, the repeats, and the hourly prune. */
    suspend fun pass() {
        passLock.withLock {
            publishPending()
            requestReplays()
            val t = now()
            if (t - lastPrune >= 60 * 60_000L) {
                runCatching { store.pruneInbox(t) }
                runCatching { store.pruneOps(t) }
                lastPrune = t
            }
        }
    }

    companion object {
        private const val TAG = "HubMsg"

        /** How long the Hub keeps trying a message from this phone. */
        const val MESSAGE_LIFETIME_MS = 24L * 60 * 60 * 1000

        /**
         * Repeats stop this long after an operation's deadline. The Hub answers a repeat from what it
         * stores for a day past the deadline (C4), so a repeat inside this bound is always a replay.
         */
        const val REPLAY_WITHIN_MS = 12L * 60 * 60 * 1000

        const val PASS_MS = 30_000L
        const val BATCH = 50
        const val OWED_BATCH = 50
        const val WALK_BATCH = 100
    }
}
