package net.meshsat.android

import android.content.Context
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
import net.meshsat.android.data.Message
import net.meshsat.android.data.MessageDeliveryDao
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.engine.Dispatcher
import net.meshsat.android.rules.AccessEvaluator
import net.meshsat.android.service.GatewayService
import net.meshsat.android.service.SatelliteChat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The satellite chat label against the app's real Room database (review 2026-10-19 00:36, FE-R5,
 * MESHSAT-1584). The real Dispatcher runs with the hook GatewayService installs
 * (SatelliteChat.unconfirmed) and the Hub's receipt goes through SatelliteChat.hubReceipt, as in
 * GatewayService.onHubReceipt. An earlier session's receipt, before or after a later attempt that
 * ends uncertain, leaves "The Hub has it"; with no receipt the label is "May have been sent".
 */
@RunWith(AndroidJUnit4::class)
class SatelliteChatRoomTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "satellite-chat-test.db"
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private val ref = "300434060000000:7" // the earlier session the Hub has

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
        scope.cancel()
        db.close()
        db = AppDatabase.build(context, name)
    }

    private fun chat(id: Long): String =
        db.openHelper.readableDatabase.query("SELECT forwardedTo FROM messages WHERE id = ?", arrayOf<Any>(id)).use {
            it.moveToFirst()
            it.getString(0)
        }

    private fun ledger(id: Long): String =
        db.openHelper.readableDatabase.query("SELECT status, ack_status FROM message_deliveries WHERE id = ?", arrayOf<Any>(id)).use {
            it.moveToFirst()
            "${it.getString(0)}/${it.getString(1)}"
        }

    private suspend fun pending(): Int = db.messageDeliveryDao().getPending("iridium_0", System.currentTimeMillis() + 86_400_000).size

    /**
     * A chat message and its delivery as a restart leaves them: the earlier session went out (its
     * satellite reference is stored), the app stopped before the worker recorded it, and the
     * recovery put the delivery back to retry.
     */
    private suspend fun recovered(): Pair<Long, Long> {
        val m = db.messageDao().insert(
            Message(transport = "iridium", direction = "tx", sender = "self", text = "x", forwardedTo = GatewayService.IRIDIUM_QUEUED),
        )
        val d = db.messageDeliveryDao().insert(
            MessageDeliveryEntity(msgRef = "msg:$m", channel = "iridium_0", textPreview = "x", status = "sending", maxRetries = 0, qosLevel = 1),
        )
        db.messageDeliveryDao().setSatRef(d, ref)
        assertEquals(1, db.messageDeliveryDao().recoverStale())
        return m to d
    }

    /** The worker with the production hook; [deliver] stands in for the modem. */
    private fun worker(dao: Watched, deliver: Dispatcher.DeliveryCallback) = Dispatcher(
        deliveryDao = dao,
        accessEvaluator = AccessEvaluator(db.accessRuleDao(), db.objectGroupDao(), scope),
        failoverResolver = null,
        registry = ChannelRegistry(),
        deliveryCallback = deliver,
        scope = scope,
    ).also { d -> d.onUnconfirmed = { del, _ -> SatelliteChat.unconfirmed(db.messageDao(), del) } }

    private suspend fun afterBatch(dao: Watched, seen: Int) = withTimeout(20_000) {
        while (dao.batches.get() <= seen) delay(20)
    }

    private val uncertain = "${Dispatcher.UNCONFIRMED} status 13, the gateway may have it"

    @Test
    fun theHubsReceiptDuringALaterAttemptThatEndsUncertainStays() = runBlocking {
        val (m, d) = recovered()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = Watched(db.messageDeliveryDao())
        worker(dao) { _, _, _, _, _, _ ->
            out.complete(Unit)
            release.await()
            uncertain
        }.startWorker("iridium_0")
        withTimeout(20_000) { out.await() }
        val seen = dao.batches.get()
        assertEquals(1, SatelliteChat.hubReceipt(db, ref))
        release.complete(Unit)
        afterBatch(dao, seen)

        assertEquals(GatewayService.IRIDIUM_DELIVERED, chat(m))
        assertEquals("sent/acked", ledger(d))
        assertEquals(0, pending())
        restart()
        assertEquals(GatewayService.IRIDIUM_DELIVERED, chat(m))
        assertEquals("sent/acked", ledger(d))
        assertEquals(0, pending())
    }

    @Test
    fun anUncertainAttemptThenTheHubsReceiptEndsConfirmed() = runBlocking {
        val (m, d) = recovered()
        val dao = Watched(db.messageDeliveryDao())
        val tries = AtomicInteger()
        worker(dao) { _, _, _, _, _, _ ->
            tries.incrementAndGet()
            uncertain
        }.startWorker("iridium_0")
        withTimeout(20_000) { while (tries.get() < 1) delay(20) }
        afterBatch(dao, dao.batches.get())
        assertEquals(GatewayService.IRIDIUM_UNCONFIRMED, chat(m))

        scope.cancel() // the worker stops; the receipt comes later
        assertEquals(1, SatelliteChat.hubReceipt(db, ref))
        assertEquals(GatewayService.IRIDIUM_DELIVERED, chat(m))
        assertEquals("sent/acked", ledger(d))
        assertEquals(0, pending())
        restart()
        assertEquals(GatewayService.IRIDIUM_DELIVERED, chat(m))
        assertEquals("sent/acked", ledger(d))
    }

    @Test
    fun withNoReceiptAnUncertainAttemptMayHaveBeenSentAndIsTriedAgain() = runBlocking {
        val (m, d) = recovered()
        val dao = Watched(db.messageDeliveryDao())
        val tries = AtomicInteger()
        worker(dao) { _, _, _, _, _, _ ->
            tries.incrementAndGet()
            uncertain
        }.startWorker("iridium_0")
        withTimeout(20_000) { while (tries.get() < 1) delay(20) }
        afterBatch(dao, dao.batches.get())
        scope.cancel()

        assertEquals(GatewayService.IRIDIUM_UNCONFIRMED, chat(m))
        assertEquals("retry/null", ledger(d))
        assertTrue("an uncertain send without a receipt is tried again", pending() == 1)
        restart()
        assertEquals(GatewayService.IRIDIUM_UNCONFIRMED, chat(m))
    }
}
