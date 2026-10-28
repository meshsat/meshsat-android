package net.meshsat.android.warnings

import net.meshsat.android.data.WarningDao
import net.meshsat.android.data.WarningEntity

/**
 * What the phone does with a relayed warning it hears on the mesh (MESHSAT-1734): the state,
 * kept in the `warnings` table through [WarningDao] (an in-memory one in the unit tests).
 *
 * - The same words within [DEDUP_MS] are one warning: the kit sends them on port 11 and as a
 *   text, a second kit in range sends them again, the mesh may rebroadcast. One alarm.
 * - An alert or an update of an event replaces the warnings of that event that still stood:
 *   the newest is the one that stands, the list keeps the earlier ones as replaced.
 * - A cancel clears the standing warnings of its event, and is kept as a row of its own so the
 *   list shows the all-clear. A test cancel clears test warnings only.
 */
class WarningIntake(
    private val dao: WarningDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed class Outcome {
        /** A new warning stands: alarm (the normal sound for a test). */
        data class Alarm(val warning: WarningEntity) : Outcome()

        /** A cancel: [cleared] no longer stand; their alarms go. */
        data class Cleared(val cancel: WarningEntity, val cleared: List<WarningEntity>) : Outcome()

        /** The same words again within the window: nothing to do. */
        data object Duplicate : Outcome()

        /** Not a relayed warning: an ordinary text, left to the chat. */
        data object NotAWarning : Outcome()
    }

    /** One mesh packet. [sender] is "!a1b2c3d4", [senderName] its long name or "". */
    suspend fun onMesh(sender: String, senderName: String, port: Int, raw: String): Outcome {
        val p = WarningText.recognise(raw, port) ?: return Outcome.NotAWarning
        val t = now()
        val key = WarningText.dedupKey(raw)
        if (dao.countRecent(key, t - DEDUP_MS) > 0) return Outcome.Duplicate
        val standing = dao.standingOf(p.event, p.test)
        val row = WarningEntity(
            receivedAt = t, port = port, sender = sender, senderName = senderName,
            kind = p.kind.wire, test = p.test, event = p.event, severity = p.severity, until = p.until,
            text = p.text, label = p.label, raw = WarningText.clean(raw), dedupKey = key,
            // A cancel is history from the start: it never asks to be dismissed.
            acknowledgedAt = if (p.kind == WarningText.Kind.CANCEL) t else 0,
        )
        return if (p.kind == WarningText.Kind.CANCEL) {
            if (standing.isNotEmpty()) dao.markCancelled(standing.map { it.id }, t)
            val id = dao.insert(row)
            Outcome.Cleared(row.copy(id = id), standing.map { it.copy(cancelledAt = t) })
        } else {
            if (standing.isNotEmpty()) dao.markReplaced(standing.map { it.id }, t)
            val id = dao.insert(row)
            Outcome.Alarm(row.copy(id = id))
        }
    }

    /** The person dismissed the banner: the warning stands, it just stops asking. */
    suspend fun acknowledge(id: Long) = dao.acknowledge(id, now())

    companion object {
        /** The same words heard again within this are the same warning. */
        const val DEDUP_MS = 15 * 60 * 1000L

        /** Rows older than this go, as the kit's own table is pruned. */
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
