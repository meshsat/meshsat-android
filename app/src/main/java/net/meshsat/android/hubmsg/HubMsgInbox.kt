package net.meshsat.android.hubmsg

/**
 * The receiver's inbox (C3), as the Bridge's inbox.go: what a delivery on this phone's msg/in
 * commits, and what the phone answers on its msg/ack. The commit key is (origin, op_id), with an
 * index (origin, mid); the message is stored once per (origin, mid). On the phone the stored
 * message is the chat row: it goes in the transaction that commits the operation.
 */
object HubMsgInbox {
    /** The clock skew a commit allows past an operation's deadline. */
    const val SKEW_MS = 120_000L

    /** How long after deadline plus skew an entry is kept, so a late repeat is acknowledged, not refused. */
    const val RETENTION_AFTER_EXPIRY_MS = 24L * 60 * 60 * 1000

    /** The target number of live entries: at it, with nothing past its retention, a new one is refused receiver_busy. */
    const val CAPACITY = 50_000

    data class Entry(
        val origin: String,
        val opId: String,
        val mid: String,
        val recipient: String,
        val contentType: String,
        val midExpiresAt: Long,
        val expiresAt: Long,
        val contentSha256: String,
        /** The stored message's row; operations of one (origin, mid) share it. */
        val messageId: Long = 0,
        val committedAt: Long,
        val withinSkew: Boolean = false,
        val unjudged: Boolean = false,
    )

    /** The inbox inside one transaction: Commit's reads and its write are atomic. */
    interface Tx {
        fun byOp(origin: String, opId: String): Entry?
        fun byMid(origin: String, mid: String): Entry?
        fun count(): Int

        /** Deletes the entries whose deadline plus skew plus retention is before [nowMs]; returns how many. */
        fun pruneBefore(nowMs: Long): Int

        /** Writes [e]; with [store] it also stores the message ([body]) and returns the entry with its row. */
        fun insert(e: Entry, store: Boolean, body: String): Entry
    }

    data class Answer(
        val result: String,
        val reason: String = "",
        val storedNow: Boolean = false,
        val withinSkew: Boolean = false,
        val unjudged: Boolean = false,
        /** The stored message's row on a stored answer. */
        val messageId: Long = 0,
    )

    private fun refused(reason: String) = Answer(result = "refused", reason = reason)

    /**
     * Decides one delivery in the contract's order (C3): valid ids, the digest recomputed from the
     * delivered fields, the deadline within the message's, a known (origin, op_id), a known
     * (origin, mid), the deadline at commit, the capacity. Without [clockEstablished] the deadline
     * is not judged: a wrong clock must not drop messages. [capacity] <= 0 is [CAPACITY].
     */
    fun commit(tx: Tx, d: HubDelivery, nowMs: Long, clockEstablished: Boolean, capacity: Int = CAPACITY): Answer {
        val cap = if (capacity <= 0) CAPACITY else capacity
        if (!HubMsgIds.validV7(d.opId) || !HubMsgIds.validV7(d.mid)) return refused("bad_id")
        val digest = HubMsgDigest.sha256HexOrNull(d.mid, d.to, d.contentType, d.midExpiresAt, d.body.toByteArray(Charsets.UTF_8))
        if (digest == null || digest != d.contentSha256) return refused("digest_mismatch")
        if (d.expiresAt > d.midExpiresAt) return refused("bad_expiry")
        tx.byOp(d.from, d.opId)?.let { e ->
            // Committed before: acknowledged again, after its deadline too (it was in time).
            return if (e.mid == d.mid && e.contentSha256 == d.contentSha256) Answer(result = "stored", messageId = e.messageId)
            else refused("op_conflict")
        }
        val known = tx.byMid(d.from, d.mid)
        // Never an acknowledgement of the stored message for another payload.
        if (known != null && known.contentSha256 != d.contentSha256) return refused("mid_conflict")
        var withinSkew = false
        var unjudged = false
        when {
            !clockEstablished -> unjudged = true
            nowMs > d.expiresAt + SKEW_MS -> return refused("expired_at_recipient")
            nowMs > d.expiresAt -> withinSkew = true
        }
        if (tx.count() >= cap) {
            // An entry past its retention goes first; a live entry is never evicted.
            tx.pruneBefore(nowMs)
            if (tx.count() >= cap) return refused("receiver_busy")
        }
        val store = known == null
        val entry = Entry(
            origin = d.from, opId = d.opId, mid = d.mid, recipient = d.to, contentType = d.contentType,
            midExpiresAt = d.midExpiresAt, expiresAt = d.expiresAt, contentSha256 = d.contentSha256,
            messageId = known?.messageId ?: 0, committedAt = nowMs, withinSkew = withinSkew, unjudged = unjudged,
        )
        val kept = tx.insert(entry, store, d.body)
        return Answer(result = "stored", storedNow = store, withinSkew = withinSkew, unjudged = unjudged, messageId = kept.messageId)
    }

    /** When an entry with this deadline may go. */
    fun retainedUntil(expiresAt: Long) = expiresAt + SKEW_MS + RETENTION_AFTER_EXPIRY_MS
}
