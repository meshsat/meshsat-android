package net.meshsat.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.channel.ChannelRegistry
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.MessageDeliveryDao
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.engine.AckTracker
import net.meshsat.android.engine.Dispatcher
import net.meshsat.android.rules.AccessEvaluator
import net.meshsat.android.sms.SmsCompletion
import net.meshsat.android.sms.SmsPartKey
import net.meshsat.android.sms.SmsPartLedger
import net.meshsat.android.sms.SmsReceipt
import net.meshsat.android.sms.SmsReceipts
import net.meshsat.android.sms.SmsReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The delivery worker and the SMS delivery reports on the app's real Room database (review
 * 2026-10-18 23:31, FE-R4, MESHSAT-1584). The worker is the real [Dispatcher] with its real DAO
 * calls; only the radio is replaced by a callback the test holds open. A report that commits at any
 * point while a send is out (before its outcome, at the old gap between "sent" and the ACK wait,
 * or while a later attempt is in flight) leaves the delivery confirmed, and nothing selects it to be
 * sent again. The database is a file, closed and opened again where a test says restart.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryWorkerRoomTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "delivery-worker-test.db"
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private val now = System.currentTimeMillis()

    /** The real DAO, counting the worker's batches: a new batch means the last send fully returned. */
    private class Watched(private val d: MessageDeliveryDao) : MessageDeliveryDao by d {
        val batches = AtomicInteger()
        override suspend fun getPending(channel: String, now: Long, limit: Int): List<MessageDeliveryEntity> {
            batches.incrementAndGet()
            return d.getPending(channel, now, limit)
        }
    }

    @Before
    fun open() {
        context.deleteDatabase(name)
        db = AppDatabase.build(context, name)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
        context.deleteDatabase(name)
    }

    private fun restart() {
        db.close()
        db = AppDatabase.build(context, name)
    }

    private fun row(id: Long): Pair<String, String?> =
        db.openHelper.readableDatabase.query("SELECT status, ack_status FROM message_deliveries WHERE id = ?", arrayOf<Any>(id)).use {
            it.moveToFirst()
            it.getString(0) to it.getString(1)
        }

    private fun retries(id: Long): Int =
        db.openHelper.readableDatabase.query("SELECT retries FROM message_deliveries WHERE id = ?", arrayOf<Any>(id)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun delivered(attempt: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sms_part_reports WHERE attempt = ? AND state = 'Delivered'", arrayOf<Any>(attempt),
        ).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun worker(dao: MessageDeliveryDao, callback: Dispatcher.DeliveryCallback) = Dispatcher(
        deliveryDao = dao,
        accessEvaluator = AccessEvaluator(db.accessRuleDao(), db.objectGroupDao(), scope),
        failoverResolver = null,
        registry = ChannelRegistry(),
        deliveryCallback = callback,
        scope = scope,
    )

    /** Waits for the worker to start another batch after [seen] batches: the send before it has returned. */
    private suspend fun afterBatch(dao: Watched, seen: Int) = withTimeout(20_000) {
        while (dao.batches.get() <= seen) delay(20)
    }

    /** A queued SMS delivery as the queue writes it (QoS 1: it waits for its ACK). */
    private suspend fun queued(status: String = "queued", retries: Int = 0, maxRetries: Int = 3, channel: String = "sms_0", ack: String? = null): Long =
        db.messageDeliveryDao().insert(
            MessageDeliveryEntity(
                msgRef = "msg:x", channel = channel, textPreview = "x", status = status,
                retries = retries, maxRetries = maxRetries, qosLevel = 1, ackStatus = ack,
            ),
        )

    /** What SmsSender does before a send goes out: the send is registered under its owner. */
    private suspend fun register(attempt: String, parts: Int, delivery: Long) =
        SmsPartLedger.register(db.smsPartReportDao(), attempt, parts, SmsReceipt(deliveryId = delivery), now)

    /** Every part of [attempt] reported delivered, through the receipt transaction. */
    private suspend fun allDelivered(attempt: String, parts: Int, delivery: Long): SmsCompletion {
        var c = SmsCompletion.Open
        for (p in 0 until parts) c = SmsReceipts.apply(db, SmsPartKey(attempt, p, parts), SmsReport.Delivered, -1, delivery, now)
        return c
    }

    private suspend fun noTimeoutUndoesIt() = AckTracker(db.messageDeliveryDao(), scope).checkTimeouts(now + 3_600_000)

    private suspend fun nothingToSend(channel: String = "sms_0") =
        assertEquals(emptyList<MessageDeliveryEntity>(), db.messageDeliveryDao().getPending(channel, now + 86_400_000))

    @Test
    fun reportsThatCommitWhileTheSendIsOutStayAfterItReturns() = runBlocking {
        val d = queued()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = Watched(db.messageDeliveryDao())
        worker(dao) { _, _, _, _, id, _ ->
            register("a", 2, id)
            out.complete(Unit)
            release.await()
            null
        }.startWorker("sms_0")
        withTimeout(20_000) { out.await() }
        val seen = dao.batches.get()
        assertEquals("sending" to null, row(d))

        assertEquals(SmsCompletion.Delivered, allDelivered("a", 2, d))
        release.complete(Unit)
        afterBatch(dao, seen)

        assertEquals("delivered" to "acked", row(d))
        noTimeoutUndoesIt()
        nothingToSend()
        scope.cancel()
        restart()
        assertEquals("delivered" to "acked", row(d))
        assertEquals(2, delivered("a"))
    }

    @Test
    fun aReportAtTheOldGapBetweenSentAndTheAckWaitStays() = runBlocking {
        // onSent runs where the old worker had written "sent" and not yet the ACK wait.
        val d = queued()
        val dao = Watched(db.messageDeliveryDao())
        val atGap = CompletableDeferred<SmsCompletion>()
        val w = worker(dao) { _, _, _, _, id, _ ->
            register("a", 1, id)
            null
        }
        w.onSent = { del -> atGap.complete(allDelivered("a", 1, del.id)) }
        w.startWorker("sms_0")
        assertEquals(SmsCompletion.Delivered, withTimeout(20_000) { atGap.await() })
        afterBatch(dao, dao.batches.get())

        assertEquals("delivered" to "acked", row(d))
        noTimeoutUndoesIt()
        assertEquals("delivered" to "acked", row(d))
        nothingToSend()
        scope.cancel()
        restart()
        assertEquals("delivered" to "acked", row(d))
        assertEquals(1, delivered("a"))
    }

    /** An earlier send's reports confirm the delivery while a later send is out, which then fails locally. */
    private fun earlierSendConfirmsWhileALaterOneFails(retriesBefore: Int) = runBlocking {
        val d = queued(status = "retry", retries = retriesBefore, maxRetries = 3)
        register("a1", 2, d) // the earlier send: its parts went out although the phone reported a failure
        val sends = AtomicInteger()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = Watched(db.messageDeliveryDao())
        worker(dao) { _, _, _, _, id, _ ->
            sends.incrementAndGet()
            register("a2", 2, id)
            out.complete(Unit)
            release.await()
            "Not sent: generic failure"
        }.startWorker("sms_0")
        withTimeout(20_000) { out.await() }
        val seen = dao.batches.get()

        assertEquals(SmsCompletion.Delivered, allDelivered("a1", 2, d))
        release.complete(Unit)
        afterBatch(dao, seen + 1) // two more batches: the worker had the chance to send it again

        assertEquals("delivered" to "acked", row(d))
        assertEquals(retriesBefore, retries(d))
        assertEquals(1, sends.get())
        nothingToSend()
        scope.cancel()
        restart()
        assertEquals("delivered" to "acked", row(d))
        assertEquals(2, delivered("a1"))
        nothingToSend()
    }

    @Test
    fun aLaterSendThatFailsDoesNotRetryAConfirmedDelivery() = earlierSendConfirmsWhileALaterOneFails(retriesBefore = 1)

    @Test
    fun aLaterSendThatFailsOnItsLastTryDoesNotDropAConfirmedDelivery() = earlierSendConfirmsWhileALaterOneFails(retriesBefore = 2)

    @Test
    fun theHubsSatelliteReceiptWhileTheSendIsOutStays() = runBlocking {
        val d = queued(channel = "iridium_0")
        val dao = Watched(db.messageDeliveryDao())
        val out = CompletableDeferred<Unit>()
        worker(dao) { _, _, _, _, id, _ ->
            db.messageDeliveryDao().setSatRef(id, "300434060000000:42")
            // The Hub's receipt for the session lands before the worker records the send.
            assertEquals(1, db.messageDeliveryDao().markAckedBySatRef("300434060000000:42"))
            out.complete(Unit)
            null
        }.startWorker("iridium_0")
        withTimeout(20_000) { out.await() }
        afterBatch(dao, dao.batches.get())

        assertEquals("sent" to "acked", row(d))
        noTimeoutUndoesIt()
        assertEquals("sent" to "acked", row(d))
        nothingToSend("iridium_0")
    }

    @Test
    fun aConfirmedDeliveryAnOlderWorkerSetBackToRetryIsNotSentAgain() = runBlocking {
        val d = queued(status = "retry", ack = "acked") // what the worker before FE-R4 left behind
        nothingToSend()
        val sends = AtomicInteger()
        val dao = Watched(db.messageDeliveryDao())
        worker(dao) { _, _, _, _, _, _ ->
            sends.incrementAndGet()
            null
        }.start(mapOf("sms_0" to "sms"))
        afterBatch(dao, 1)

        assertEquals(0, sends.get())
        assertEquals("sent" to "acked", row(d))
    }

    @Test
    fun aVersion19DatabaseUpgradesTo20AndKeepsItsDeliveries() = runBlocking {
        val d = queued(status = "sent")
        val path = context.getDatabasePath(name).path
        db.close()
        // The version-19 schema is today's without sms_sends (MIGRATION_19_20), the hub_msg
        // tables (MIGRATION_20_21) and lxmf_mids (MIGRATION_21_22); it upgrades through 20 and 21
        // to the current version.
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            for (t in listOf("sms_sends", "hub_ops", "hub_op_events", "hub_op_missing", "hub_inbox", "lxmf_mids")) it.execSQL("DROP TABLE $t")
            it.version = 19
        }
        db = AppDatabase.build(context, name)
        assertEquals(23, db.openHelper.readableDatabase.version)
        assertEquals("sent" to null, row(d))

        register("a", 1, d)
        assertEquals(SmsCompletion.Delivered, allDelivered("a", 1, d))
        restart()
        assertEquals("delivered" to "acked", row(d))
        assertEquals(1, delivered("a"))
    }

    @Test
    fun aConfirmationTakesTheRowFromTheWorkerAndItsLaterWritesFindNothing() = runBlocking {
        val dao = db.messageDeliveryDao()
        val out = queued(channel = "iridium_0")
        assertEquals(1, dao.claimForSend(out))
        dao.setSatRef(out, "300434060000000:1")
        val waiting = queued(status = "retry", channel = "iridium_0")
        dao.setSatRef(waiting, "300434060000000:2")
        assertEquals(1, dao.markAckedBySatRef("300434060000000:1"))
        assertEquals(1, dao.markAckedBySatRef("300434060000000:2"))
        assertEquals("sent" to "acked", row(out))
        assertEquals("sent" to "acked", row(waiting))
        assertEquals(0, dao.markSent(out, 7, true))
        assertEquals(0, dao.scheduleRetry(out, 1, now, "x"))
        assertEquals(0, dao.deferRetry(out, now, "x"))
        assertEquals(0, dao.finishSend(out, "dead", "x"))
        assertEquals(0, dao.claimForSend(waiting))
        assertEquals("sent" to "acked", row(out))
        // A row some future writer leaves 'sending' with its confirmation: the send's outcome keeps it.
        val odd = queued(status = "sending", ack = "acked")
        assertEquals(1, dao.markSent(odd, 8, true))
        assertEquals("sent" to "acked", row(odd))
    }

    @Test
    fun aClaimTakesOnlyAWaitingUnconfirmedDelivery() = runBlocking {
        val dao = db.messageDeliveryDao()
        val waiting = queued()
        val confirmed = queued(status = "retry", ack = "acked")
        val done = queued(status = "delivered", ack = "acked")
        assertEquals(1, dao.claimForSend(waiting))
        assertEquals(0, dao.claimForSend(waiting)) // taken: a second worker gets nothing
        assertEquals(0, dao.claimForSend(confirmed))
        assertEquals(0, dao.claimForSend(done))
        assertEquals(0, dao.dropWaiting(confirmed, "dead", "cancelled"))
        assertEquals(0, dao.cancelWaiting(waiting)) // a cancel cannot take back a send that is out
        assertEquals("retry" to "acked", row(confirmed))
        assertEquals("delivered" to "acked", row(done))
    }
}
