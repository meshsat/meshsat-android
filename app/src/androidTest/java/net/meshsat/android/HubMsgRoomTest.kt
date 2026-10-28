package net.meshsat.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.Message
import net.meshsat.android.data.RoomHubMsgStore
import net.meshsat.android.hubmsg.HubDelivery
import net.meshsat.android.hubmsg.HubEvent
import net.meshsat.android.hubmsg.HubMsgIds
import net.meshsat.android.hubmsg.HubMsgInbox
import net.meshsat.android.hubmsg.HubMsgStatus
import net.meshsat.android.hubmsg.HubOp
import net.meshsat.android.hubmsg.HubOpCursor
import net.meshsat.android.hubmsg.HubSubmission
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * The Hub app route's Room store (v21) against the app's real database on a device (MESHSAT-1584):
 * the release's 17 inbox cases (C3) on SQLite; an event kept and the chat row's status in one
 * transaction (a failing status write leaves no event); obligations named by event, ended only by
 * their event or a completion (A15), and the listings' cursors and bound (A12, HM-R7); a version-20
 * database upgraded to 21 with its messages.
 */
@RunWith(AndroidJUnit4::class)
class HubMsgRoomTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "hub-msg-test.db"
    private lateinit var db: AppDatabase
    private lateinit var store: RoomHubMsgStore

    @Before
    fun open() {
        context.deleteDatabase(name)
        db = AppDatabase.build(context, name)
        store = RoomHubMsgStore(db)
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun sql(q: String) = db.openHelper.writableDatabase.execSQL(q)

    private fun count(q: String): Int = db.openHelper.readableDatabase.query(q).use { it.moveToFirst(); it.getInt(0) }

    private val now = 1_792_400_000_000L

    private suspend fun outgoing(i: Int = 0, created: Long = now): HubOp {
        val t = created + i
        val opId = HubMsgIds.newV7(t)
        val mid = HubMsgIds.newV7(t)
        val sub = HubSubmission.create(opId, mid, "bridge:kit-b", "text $i", t + 3_600_000, t + 3_600_000)
        val op = HubOp(opId, mid, "bridge:kit-b", 0, t + 3_600_000, t, sub.toJson())
        val id = store.createOutgoing(op, "bridge:phone-a", "text $i")
        return op.copy(messageId = id)
    }

    private fun event(op: HubOp, cls: String, kind: String, authority: String, reason: String = "") =
        HubEvent(source = "status_topic", opId = op.opId, mid = op.mid, recipient = op.recipient, eventId = HubMsgIds.newV7(now),
            rev = 1, cls = cls, kind = kind, reason = reason, authority = authority)

    private fun status(op: HubOp): String = db.openHelper.readableDatabase
        .query("SELECT forwardedTo FROM messages WHERE id = ?", arrayOf<Any>(op.messageId)).use { it.moveToFirst(); it.getString(0) }

    @Test
    fun theReleaseInboxCasesHoldOnSqlite() = runBlocking {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("hemb-ng/inbox_cases.json").use { it.readBytes() }
        // The copy is the release's file (fixtures-v1.1, SHA256SUMS).
        assertEquals("d51e5344d45f5fc2f1911b3713a1170cdb5883fffe1ff4b106b9d8f91e169792",
            MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
        val cases = JSONObject(String(raw)).getJSONArray("cases")
        assertEquals(17, cases.length())
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            sql("DELETE FROM hub_inbox")
            val steps = case.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i)
                val sub = s.getJSONObject("submission")
                val hex = sub.getString("body_hex")
                val body = String(ByteArray(hex.length / 2) { j -> hex.substring(2 * j, 2 * j + 2).toInt(16).toByte() }, Charsets.UTF_8)
                val d = HubDelivery(1, sub.getString("op_id"), sub.getString("mid"), sub.getString("origin"), sub.getString("recipient"),
                    sub.getString("content_type"), sub.getLong("mid_expires_at"), sub.getLong("expires_at"), sub.getString("content_sha256"), body)
                val got = store.commitDelivery(d, s.getLong("now"), s.getBoolean("clock_established"), case.getInt("capacity")).copy(messageId = 0)
                val e = s.getJSONObject("expect")
                assertEquals("${case.getString("case")} step ${i + 1}",
                    HubMsgInbox.Answer(e.getString("result"), e.optString("reason"), e.optBoolean("stored_now"),
                        e.optBoolean("accepted_within_skew"), e.optBoolean("expiry_unjudged")), got)
            }
        }
    }

    @Test
    fun anEventAndItsChatRowAreOneTransaction() = runBlocking {
        val op = outgoing()
        assertEquals(HubMsgStatus.QUEUED, status(op))
        store.markPublished(op.opId, now)
        assertEquals(HubMsgStatus.ACCEPTED, status(op))
        val fwd = event(op, "forwarded", "hub_forwarded", "hub")
        sql("CREATE TRIGGER test_fail BEFORE UPDATE OF forwardedTo ON messages BEGIN SELECT RAISE(ABORT, 'test'); END")
        val failed = runCatching { store.keepEvent(fwd, true, now) }.isFailure
        sql("DROP TRIGGER test_fail")
        assertTrue("the status write failure did not reach the caller", failed)
        assertTrue("an event kept without the chat row taking it in", !store.eventKept(fwd.eventId))
        assertTrue(store.keepEvent(fwd, true, now).added)
        assertEquals(HubMsgStatus.FORWARDED, status(op))
        assertTrue(!store.keepEvent(fwd, true, now).added)
        store.keepEvent(event(op, "completed", "recipient_app_stored", "recipient"), true, now)
        assertEquals(HubMsgStatus.DELIVERED, status(op))
    }

    @Test
    fun anObligationEndsOnlyWithItsEventOrACompletion() = runBlocking {
        val op = outgoing()
        store.markPublished(op.opId, now)
        val expiry = event(op, "expired", "hub_expired", "hub", "expired")
        store.keepEvent(expiry, true, now)
        val completion = event(op, "completed", "recipient_app_stored", "recipient")
        store.wantReplay(op.opId, completion.eventId, now)
        store.wantReplay("not-ours", "x", now)
        assertEquals(listOf(completion.eventId), store.missing(op.opId))
        assertEquals(0, count("SELECT COUNT(*) FROM hub_op_missing WHERE op_id = 'not-ours'"))
        store.keepEvent(expiry, true, now) // a copy of the older terminal
        assertEquals(0, store.resolveKeptMissing())
        assertEquals(1, store.owed(now, 3_600_000, HubOpCursor(), 10).size)
        assertEquals(1, store.toReconcile(now, 3_600_000, HubOpCursor(), 10).size)
        assertEquals(HubMsgStatus.EXPIRED, status(op))
        store.keepEvent(completion, true, now)
        assertTrue(store.missing(op.opId).isEmpty())
        assertEquals(0L, store.op(op.opId)!!.replayAt)
        assertEquals(HubMsgStatus.DELIVERED, status(op))
        assertTrue(store.toReconcile(now, 3_600_000, HubOpCursor(), 10).isEmpty())
    }

    @Test
    fun theBoundGivesUpOnlyWhatIsReallyMissing() = runBlocking {
        val named = outgoing(0)
        val nothing = outgoing(1)
        val stale = outgoing(2)
        val expiredOnly = outgoing(3)
        store.wantReplay(named.opId, HubMsgIds.newV7(now), now)
        val e = event(stale, "expired", "hub_expired", "hub", "expired")
        store.wantReplay(stale.opId, e.eventId, now)
        // Kept while its strike cannot be written: a stale row.
        sql("CREATE TRIGGER test_fail BEFORE DELETE ON hub_op_missing BEGIN SELECT RAISE(ABORT, 'test'); END")
        runCatching { store.keepEvent(e, true, now) } // fails whole: the event is not kept either
        assertTrue(!store.eventKept(e.eventId))
        sql("INSERT INTO hub_op_events (event_id, op_id, mid, recipient, rev, class, kind, reason, authority, scope, detail, at, source, valid, received_at) " +
            "VALUES ('${e.eventId}', '${stale.opId}', '${stale.mid}', '${stale.recipient}', 1, 'expired', 'hub_expired', 'expired', 'hub', '', '', '', 'status_topic', 1, $now)")
        store.keepEvent(event(expiredOnly, "expired", "hub_expired", "hub", "expired"), true, now)
        val later = now + 3_600_000 + 12 * 3_600_000L + 10 // past the bound of all four (created 0 to 3 ms apart)
        val listed = store.toGiveUp(later, 12 * 3_600_000L, 10).map { it.opId }.toSet()
        sql("DROP TRIGGER test_fail")
        assertEquals(setOf(named.opId, nothing.opId), listed)
        assertTrue(store.owed(now, 12 * 3_600_000L, HubOpCursor(), 10).none { it.opId == stale.opId })
        store.giveUp(named.opId, later)
        assertEquals(HubMsgStatus.UNKNOWN, status(named))
        assertTrue(store.op(named.opId)!!.replayAt != 0L)
    }

    @Test
    fun theListingsPageWithACursor() = runBlocking {
        val ops = (0 until 7).map { outgoing(it, created = now) }
        val seen = mutableSetOf<String>()
        var c = HubOpCursor()
        var pages = 0
        while (pages++ < 5) {
            val page = store.toReconcile(now, 3_600_000, c, 3)
            page.forEach { assertTrue("listed twice", seen.add(it.opId)) }
            if (page.size < 3) break
            c = HubOpCursor(page.last().createdAt, page.last().opId)
        }
        assertEquals(ops.map { it.opId }.toSet(), seen)
    }

    @Test
    fun aVersion20DatabaseUpgradesTo21AndKeepsItsMessages() = runBlocking {
        val id = db.messageDao().insert(Message(transport = "sms", direction = "rx", sender = "+31600000000", text = "kept"))
        val path = context.getDatabasePath(name).path
        db.close()
        // Version 20 is today's without the hub_msg tables and lxmf_mids (MIGRATION_21_22); it
        // upgrades through 21 to the current version.
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            for (t in listOf("hub_ops", "hub_op_events", "hub_op_missing", "hub_inbox", "lxmf_mids")) it.execSQL("DROP TABLE $t")
            it.version = 20
        }
        db = AppDatabase.build(context, name)
        store = RoomHubMsgStore(db)
        assertEquals(24, db.openHelper.readableDatabase.version)
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE id = $id AND text = 'kept'"))
        val op = outgoing()
        store.keepEvent(event(op, "forwarded", "hub_forwarded", "hub"), true, now)
        assertEquals(HubMsgStatus.FORWARDED, status(op))
    }
}
