package net.meshsat.android.hubmsg

/**
 * The route's store in memory, for the JVM tests, with the semantics of the Room store
 * (RoomHubMsgStore): every method is one transaction, rolled back whole when a write in it fails.
 * [fail] names the write to fail ("event", "missing", "message", "inbox", "op:read"), as the
 * Bridge's tests used SQLite triggers.
 */
class MemHubMsgStore : HubMsgStore {
    data class Row(val id: Long, val transport: String, val direction: String, val sender: String, val recipient: String,
                   val text: String, val forwardedTo: String)

    private data class State(
        val rows: LinkedHashMap<Long, Row> = LinkedHashMap(),
        val ops: LinkedHashMap<String, HubOp> = LinkedHashMap(),
        val events: LinkedHashMap<String, KeptHubEvent> = LinkedHashMap(),
        val missing: LinkedHashMap<Pair<String, String>, Long> = LinkedHashMap(),
        val inbox: MutableList<HubMsgInbox.Entry> = mutableListOf(),
        var nextRow: Long = 1,
    ) {
        fun copyDeep() = State(LinkedHashMap(rows), LinkedHashMap(ops), LinkedHashMap(events), LinkedHashMap(missing), inbox.toMutableList(), nextRow)
    }

    private var s = State()
    @Volatile var fail: (String) -> Boolean = { false }

    /** Fails the write of one event (the Bridge tests' WHEN NEW.kind = ... triggers). */
    @Volatile var failEvent: (HubEvent) -> Boolean = { false }

    private fun check(write: String) {
        if (fail(write)) throw IllegalStateException("test: the $write write failed")
    }

    @Synchronized
    private fun <T> tx(block: State.() -> T): T {
        val before = s.copyDeep()
        return try {
            s.block()
        } catch (e: Exception) {
            s = before
            throw e
        }
    }

    // ---- what the tests read ----

    @Synchronized fun rows(): List<Row> = s.rows.values.toList()
    @Synchronized fun row(id: Long): Row? = s.rows[id]
    @Synchronized fun ops(): List<HubOp> = s.ops.values.toList()
    @Synchronized fun keptEvents(opId: String): List<KeptHubEvent> = s.events.values.filter { it.event.opId == opId }

    // ---- the status ----

    private fun State.restatus(opId: String) {
        val op = ops[opId] ?: return
        val r = rows[op.messageId] ?: return
        check("message")
        rows[r.id] = r.copy(forwardedTo = HubMsgStatus.of(op, events.values.filter { it.event.opId == opId }))
    }

    private fun State.completedOp(opId: String) =
        events.values.any { it.event.opId == opId && it.valid && it.event.cls == "completed" && !HubMsgStatus.isRefusal(it.event) }

    private fun State.terminalOp(opId: String) =
        events.values.any { it.event.opId == opId && it.valid && it.event.cls in setOf("completed", "failed", "expired") }

    private fun State.stillMissing(opId: String) =
        missing.keys.any { (o, e) -> o == opId && events[e]?.event?.opId != opId }

    private fun State.clearIdle(opId: String) {
        val op = ops[opId] ?: return
        if (op.replayAt != 0L && missing.keys.none { it.first == opId }) ops[opId] = op.copy(replayAt = 0)
    }

    // ---- the sender ----

    override suspend fun createOutgoing(op: HubOp, sender: String, text: String): Long = tx {
        check("message")
        val id = nextRow++
        rows[id] = Row(id, "hub", "tx", sender, op.recipient, text, HubMsgStatus.QUEUED)
        ops[op.opId] = op.copy(messageId = id)
        id
    }

    override suspend fun op(opId: String): HubOp? = tx { check("op:read"); ops[opId] }

    override suspend fun unpublished(limit: Int) = tx { ops.values.filter { it.publishedAt == 0L }.sortedBy { it.createdAt }.take(limit) }

    override suspend fun markPublished(opId: String, atMs: Long) = tx {
        val op = ops[opId] ?: return@tx
        if (op.publishedAt == 0L) ops[opId] = op.copy(publishedAt = atMs)
        restatus(opId)
    }

    override suspend fun keepEvent(e: HubEvent, valid: Boolean, atMs: Long): KeptHubEvent = tx {
        val prev = events[e.eventId]
        val kept = if (prev != null) prev.copy(added = false) else {
            check("event")
            if (failEvent(e)) throw IllegalStateException("test: the event ${e.kind} write failed")
            KeptHubEvent(e, valid, true).also { events[e.eventId] = it.copy(added = false) }
        }
        if (kept.valid && kept.event.cls == "completed" && !HubMsgStatus.isRefusal(kept.event)) {
            missing.keys.filter { it.first == kept.event.opId }.forEach { missing.remove(it) }
        } else {
            missing.remove(kept.event.opId to kept.event.eventId)
        }
        clearIdle(kept.event.opId)
        restatus(kept.event.opId)
        kept
    }

    override suspend fun wantReplay(opId: String, eventId: String, atMs: Long) = tx {
        check("missing")
        val op = ops[opId] ?: return@tx
        if (op.replayAt == 0L) ops[opId] = op.copy(replayAt = atMs)
        missing.putIfAbsent(opId to eventId, atMs)
        Unit
    }

    override suspend fun resolveKeptMissing(): Int = tx {
        val gone = missing.keys.filter { (o, e) -> (e.isNotEmpty() && events.containsKey(e)) || completedOp(o) }
        gone.forEach { missing.remove(it) }
        ops.keys.toList().forEach { clearIdle(it) }
        gone.size
    }

    override suspend fun eventKept(eventId: String) = tx { events.containsKey(eventId) }
    override suspend fun completed(opId: String) = tx { completedOp(opId) }
    override suspend fun missing(opId: String) = tx { missing.keys.filter { it.first == opId }.map { it.second } }

    private fun after(key: Long, id: String, c: HubOpCursor) = key > c.key || (key == c.key && id > c.opId)

    override suspend fun owed(nowMs: Long, withinMs: Long, after: HubOpCursor, limit: Int) = tx {
        ops.values.filter { it.replayAt != 0L && stillMissing(it.opId) && it.submission.isNotEmpty() && it.replayGivenUpAt == 0L &&
            it.expiresAt + withinMs >= nowMs && after(it.replayAt, it.opId, after) }
            .sortedWith(compareBy({ it.replayAt }, { it.opId })).take(limit)
    }

    override suspend fun toReconcile(nowMs: Long, withinMs: Long, after: HubOpCursor, limit: Int) = tx {
        ops.values.filter { it.submission.isNotEmpty() && it.replayGivenUpAt == 0L && it.expiresAt + withinMs >= nowMs &&
            !completedOp(it.opId) && after(it.createdAt, it.opId, after) }
            .sortedWith(compareBy({ it.createdAt }, { it.opId })).take(limit)
    }

    override suspend fun toGiveUp(nowMs: Long, withinMs: Long, limit: Int) = tx {
        ops.values.filter { it.replayGivenUpAt == 0L && it.expiresAt + withinMs < nowMs && !completedOp(it.opId) &&
            (stillMissing(it.opId) || !terminalOp(it.opId)) }
            .sortedWith(compareBy({ it.createdAt }, { it.opId })).take(limit)
    }

    override suspend fun giveUp(opId: String, atMs: Long) = tx {
        val op = ops[opId] ?: return@tx
        if (op.replayGivenUpAt == 0L) ops[opId] = op.copy(replayGivenUpAt = atMs)
        restatus(opId)
    }

    override suspend fun pruneOps(nowMs: Long): Int = tx {
        val gone = ops.values.filter { HubMsgInbox.retainedUntil(it.expiresAt) < nowMs }.map { it.opId }.toSet()
        gone.forEach { ops.remove(it) }
        events.keys.filter { events[it]!!.event.opId in gone }.forEach { events.remove(it) }
        missing.keys.filter { it.first in gone }.forEach { missing.remove(it) }
        gone.size
    }

    // ---- the receiver ----

    override suspend fun commitDelivery(d: HubDelivery, nowMs: Long, clockEstablished: Boolean, capacity: Int) = tx {
        val state = this
        HubMsgInbox.commit(object : HubMsgInbox.Tx {
            override fun byOp(origin: String, opId: String) = state.inbox.firstOrNull { it.origin == origin && it.opId == opId }
            override fun byMid(origin: String, mid: String) = state.inbox.filter { it.origin == origin && it.mid == mid }.minByOrNull { it.committedAt }
            override fun count() = state.inbox.size
            override fun pruneBefore(nowMs: Long): Int {
                val g = state.inbox.filter { HubMsgInbox.retainedUntil(it.expiresAt) < nowMs }
                state.inbox.removeAll(g)
                return g.size
            }
            override fun insert(e: HubMsgInbox.Entry, store: Boolean, body: String): HubMsgInbox.Entry {
                var kept = e
                if (store) {
                    check("message")
                    val id = state.nextRow++
                    state.rows[id] = Row(id, "hub", "rx", e.origin, e.recipient, body, "")
                    kept = e.copy(messageId = id)
                }
                check("inbox")
                state.inbox += kept
                return kept
            }
        }, d, nowMs, clockEstablished, capacity)
    }

    override suspend fun pruneInbox(nowMs: Long) = tx {
        val g = inbox.filter { HubMsgInbox.retainedUntil(it.expiresAt) < nowMs }
        inbox.removeAll(g)
        g.size
    }
}
