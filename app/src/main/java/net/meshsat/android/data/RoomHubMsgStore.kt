package net.meshsat.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.meshsat.android.hubmsg.HubDelivery
import net.meshsat.android.hubmsg.HubEvent
import net.meshsat.android.hubmsg.HubMsgInbox
import net.meshsat.android.hubmsg.HubMsgStatus
import net.meshsat.android.hubmsg.HubMsgStore
import net.meshsat.android.hubmsg.HubOp
import net.meshsat.android.hubmsg.HubOpCursor
import net.meshsat.android.hubmsg.KeptHubEvent

/**
 * The Hub app route's store on the app's Room database (v21): every method is one transaction,
 * off the main thread. The chat row is the ledger: an event is kept and the row's status
 * recomputed in the same transaction. [MESHSAT-1584]
 */
class RoomHubMsgStore(private val db: AppDatabase) : HubMsgStore {
    private val dao = db.hubMsgDao()

    private suspend fun <T> tx(block: () -> T): T = withContext(Dispatchers.IO) { db.runInTransaction<T> { block() } }

    private fun HubOpEntity.op() = HubOp(opId, mid, recipient, messageId, expiresAt, createdAt, submission, publishedAt, replayAt, replayGivenUpAt)

    private fun HubOpEventEntity.kept() = KeptHubEvent(
        HubEvent(v = 1, source = source, opId = opId, mid = mid, recipient = recipient, eventId = eventId, rev = rev, cls = cls,
            kind = kind, reason = reason, authority = authority, scope = scope, detail = detail, at = at),
        valid = valid, added = false,
    )

    private fun restatus(opId: String) {
        val op = dao.op(opId)?.op() ?: return
        dao.setMessageStatus(op.messageId, HubMsgStatus.of(op, dao.events(opId).map { it.kept() }))
    }

    // ---- the sender ----

    override suspend fun createOutgoing(op: HubOp, sender: String, text: String): Long = tx {
        val id = dao.insertMessage(Message(transport = "hub", direction = "tx", sender = sender, recipient = op.recipient, text = text,
            encrypted = false, timestamp = op.createdAt, forwarded = true, forwardedTo = HubMsgStatus.QUEUED))
        dao.insertOp(HubOpEntity(op.opId, op.mid, op.recipient, id, op.expiresAt, op.createdAt, op.submission))
        id
    }

    override suspend fun op(opId: String): HubOp? = tx { dao.op(opId)?.op() }

    override suspend fun unpublished(limit: Int) = tx { dao.unpublished(limit).map { it.op() } }

    override suspend fun markPublished(opId: String, atMs: Long) = tx {
        dao.setPublished(opId, atMs)
        restatus(opId)
    }

    override suspend fun keepEvent(e: HubEvent, valid: Boolean, atMs: Long): KeptHubEvent = tx {
        val added = dao.insertEvent(HubOpEventEntity(e.eventId, e.opId, e.mid, e.recipient, e.rev, e.cls, e.kind, e.reason, e.authority,
            e.scope, e.detail, e.at, e.source, valid, atMs)) != -1L
        val kept = requireNotNull(dao.event(e.eventId)).kept().copy(added = added)
        if (kept.valid && kept.event.cls == "completed" && !HubMsgStatus.isRefusal(kept.event)) {
            dao.deleteMissingOf(kept.event.opId)
        } else {
            dao.deleteMissing(kept.event.opId, kept.event.eventId)
        }
        dao.clearIdleReplay(kept.event.opId)
        restatus(kept.event.opId)
        kept
    }

    override suspend fun wantReplay(opId: String, eventId: String, atMs: Long) = tx {
        if (dao.op(opId) == null) return@tx
        dao.setReplayAt(opId, atMs)
        dao.insertMissing(HubOpMissingEntity(opId, eventId, atMs))
    }

    override suspend fun resolveKeptMissing(): Int = tx {
        val n = dao.deleteKeptMissing() + dao.deleteCompletedMissing()
        dao.clearIdleReplays()
        n
    }

    override suspend fun eventKept(eventId: String) = tx { dao.eventCount(eventId) > 0 }
    override suspend fun completed(opId: String) = tx { dao.completedCount(opId) > 0 }
    override suspend fun missing(opId: String) = tx { dao.missing(opId) }

    override suspend fun owed(nowMs: Long, withinMs: Long, after: HubOpCursor, limit: Int) =
        tx { dao.owed(nowMs, withinMs, after.key, after.opId, limit).map { it.op() } }

    override suspend fun toReconcile(nowMs: Long, withinMs: Long, after: HubOpCursor, limit: Int) =
        tx { dao.toReconcile(nowMs, withinMs, after.key, after.opId, limit).map { it.op() } }

    override suspend fun toGiveUp(nowMs: Long, withinMs: Long, limit: Int) = tx { dao.toGiveUp(nowMs, withinMs, limit).map { it.op() } }

    override suspend fun giveUp(opId: String, atMs: Long) = tx {
        dao.setGivenUp(opId, atMs)
        restatus(opId)
    }

    override suspend fun pruneOps(nowMs: Long): Int = tx {
        val cutoff = nowMs - HubMsgInbox.SKEW_MS - HubMsgInbox.RETENTION_AFTER_EXPIRY_MS
        dao.pruneEvents(cutoff)
        dao.pruneMissing(cutoff)
        dao.pruneOps(cutoff)
    }

    // ---- the receiver ----

    override suspend fun commitDelivery(d: HubDelivery, nowMs: Long, clockEstablished: Boolean, capacity: Int) = tx {
        HubMsgInbox.commit(inboxTx(), d, nowMs, clockEstablished, capacity)
    }

    /** The inbox inside the running transaction (also what the fixture cases run against). */
    fun inboxTx(): HubMsgInbox.Tx = object : HubMsgInbox.Tx {
        private fun HubInboxEntity.entry() = HubMsgInbox.Entry(origin, opId, mid, recipient, contentType, midExpiresAt, expiresAt,
            contentSha256, messageId, committedAt, withinSkew, unjudged)

        override fun byOp(origin: String, opId: String) = dao.inboxByOp(origin, opId)?.entry()
        override fun byMid(origin: String, mid: String) = dao.inboxByMid(origin, mid)?.entry()
        override fun count() = dao.inboxCount()
        override fun pruneBefore(nowMs: Long) = dao.pruneInbox(nowMs, HubMsgInbox.SKEW_MS + HubMsgInbox.RETENTION_AFTER_EXPIRY_MS)
        override fun insert(e: HubMsgInbox.Entry, store: Boolean, body: String): HubMsgInbox.Entry {
            var kept = e
            if (store) {
                val id = dao.insertMessage(Message(transport = "hub", direction = "rx", sender = e.origin, recipient = e.recipient,
                    text = body, encrypted = false, timestamp = e.committedAt))
                kept = e.copy(messageId = id)
            }
            dao.insertInbox(HubInboxEntity(kept.origin, kept.opId, kept.mid, kept.recipient, kept.contentType, kept.midExpiresAt,
                kept.expiresAt, kept.contentSha256, kept.messageId, kept.committedAt, kept.withinSkew, kept.unjudged))
            return kept
        }
    }

    override suspend fun pruneInbox(nowMs: Long) = tx { dao.pruneInbox(nowMs, HubMsgInbox.SKEW_MS + HubMsgInbox.RETENTION_AFTER_EXPIRY_MS) }
}
