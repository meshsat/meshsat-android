package net.meshsat.android.hubmsg

/**
 * What the Hub app route keeps (contract C3-C6, A11-A15; the Bridge's hub_ops, hub_op_events,
 * hub_op_missing and hub_inbox). On the phone the chat row is the ledger: an event is kept and the
 * row's delivery status recomputed from every kept event in one transaction, so an event can
 * never be kept without the status taking it in (the Bridge's HM-R1 has no counterpart here).
 */

/** One operation this phone publishes on its msg/out. */
data class HubOp(
    val opId: String,
    val mid: String,
    val recipient: String,
    /** The chat row it is the delivery of. */
    val messageId: Long,
    val expiresAt: Long,
    val createdAt: Long,
    /** The payload published, kept to publish again unchanged: a repeat asks for every event again (C4). */
    val submission: String,
    /** When the broker took it (PUBACK), 0 before. */
    val publishedAt: Long = 0,
    /** When an event of it was first found missing, 0 when none is. */
    val replayAt: Long = 0,
    /** When this phone stopped asking at the bound, 0 while it asks. */
    val replayGivenUpAt: Long = 0,
)

/** An event as kept: the first copy under its id decides what it says and whether it is valid. */
data class KeptHubEvent(val event: HubEvent, val valid: Boolean, val added: Boolean)

/** A position in a listing of operations: the sort key and the op id of the last one taken, zero for the start. */
data class HubOpCursor(val key: Long = 0, val opId: String = "")

interface HubMsgStore {
    // ---- the sender ----

    /** One transaction: the chat row (status queued) and the operation. Returns the chat row. */
    suspend fun createOutgoing(op: HubOp, sender: String, text: String): Long

    suspend fun op(opId: String): HubOp?

    /** The operations the broker has not taken yet, oldest first. */
    suspend fun unpublished(limit: Int): List<HubOp>

    /** One transaction: published_at (once) and the chat row's status recomputed. */
    suspend fun markPublished(opId: String, atMs: Long)

    /**
     * One transaction: the event kept once per event id; the chat row's status recomputed from the
     * operation's kept events; the obligations the event ends (its own id, and every obligation of
     * the operation for a valid completion, C6's strongest class). Returns the event as kept.
     */
    suspend fun keepEvent(e: HubEvent, valid: Boolean, atMs: Long): KeptHubEvent

    /** One transaction: replay_at (when first owed) and the missing event named. Nothing for an operation not kept. */
    suspend fun wantReplay(opId: String, eventId: String, atMs: Long)

    /** Ends the obligations whose event is kept by now, and those of completed operations. */
    suspend fun resolveKeptMissing(): Int

    suspend fun eventKept(eventId: String): Boolean

    suspend fun completed(opId: String): Boolean

    suspend fun missing(opId: String): List<String>

    /** After [after], by (replay_at, op_id): an event still missing (not one kept by now), not given up, inside the bound. */
    suspend fun owed(nowMs: Long, withinMs: Long, after: HubOpCursor, limit: Int): List<HubOp>

    /** After [after], by (created_at, op_id): a kept submission, no completion, not given up, inside the bound. */
    suspend fun toReconcile(nowMs: Long, withinMs: Long, after: HubOpCursor, limit: Int): List<HubOp>

    /** Past the bound, no completion, not given up: an event still missing, or no outcome kept at all. */
    suspend fun toGiveUp(nowMs: Long, withinMs: Long, limit: Int): List<HubOp>

    /** One transaction: given up, and the chat row's status recomputed. Its obligations stay. */
    suspend fun giveUp(opId: String, atMs: Long)

    /** Deletes the operations, with their events and obligations, past their deadline plus the inbox's retention. */
    suspend fun pruneOps(nowMs: Long): Int

    // ---- the receiver ----

    /** One transaction (C3): the inbox entry and, for a message not stored before, its chat row. */
    suspend fun commitDelivery(d: HubDelivery, nowMs: Long, clockEstablished: Boolean, capacity: Int): HubMsgInbox.Answer

    suspend fun pruneInbox(nowMs: Long): Int
}

/**
 * The delivery status a chat row shows, from what this phone keeps of its operation: the C6
 * projection of the valid Hub events; the Hub's refusal (a record, not evidence, so no reducer
 * takes it in) only when no event says more; the broker's PUBACK; the bound. Stored in
 * messages.forwardedTo.
 */
object HubMsgStatus {
    const val QUEUED = "hub:queued"
    const val ACCEPTED = "hub:accepted"
    const val FORWARDED = "hub:forwarded"
    const val BUSY = "hub:busy"
    const val DELIVERED = "hub:delivered"
    const val EXPIRED = "hub:expired"
    const val UNKNOWN = "hub:unknown"
    const val FAILED_PREFIX = "hub:failed:"
    const val REFUSED_PREFIX = "hub:refused:"

    /** A refusal is kept among the events under this id: one per reason. A Hub event id is a UUID, never this. */
    fun refusalEventId(opId: String, reason: String) = "refusal:$opId:$reason"
    fun isRefusal(e: HubEvent) = e.eventId.startsWith("refusal:")

    fun of(op: HubOp, kept: List<KeptHubEvent>): String {
        val operation = HubMsgReducer.Operation(op.opId, op.mid, op.recipient)
        val events = kept.filter { it.valid && !isRefusal(it.event) }.map { it.event }
        val p = HubMsgReducer.reduce(operation, events)
        val terminal = when (p.state) {
            "completed" -> DELIVERED
            "failed" -> FAILED_PREFIX + p.failedReasons.joinToString(",")
            "expired" -> EXPIRED
            else -> null
        }
        if (terminal != null) return terminal
        if (op.replayGivenUpAt > 0) return UNKNOWN
        when (p.state) {
            "forwarded" -> return FORWARDED
            "unknown" -> return BUSY
        }
        kept.firstOrNull { it.valid && isRefusal(it.event) }?.let { return REFUSED_PREFIX + it.event.reason }
        return if (op.publishedAt > 0) ACCEPTED else QUEUED
    }
}
