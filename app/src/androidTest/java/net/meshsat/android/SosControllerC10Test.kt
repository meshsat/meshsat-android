package net.meshsat.android

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.channel.ChannelRegistry
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.engine.Dispatcher
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.rules.AccessEvaluator
import net.meshsat.android.sos.SosController
import net.meshsat.android.sos.SosHubLink
import net.meshsat.android.sos.SosRun
import net.meshsat.android.sos.SosV2
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The SOS controller under C10 on the app's real Room database and settings (r16 review C10-R3,
 * R4, R5; MESHSAT-1584). Only the Hub session is a fake that records what it is given; the
 * clock is the test's and the follow loop runs every 50 ms.
 *
 * - R3: a cancel frame whose enqueue failed stays owed and is queued once the database takes it,
 *   and a row from before a crash is not queued twice.
 * - R4: a new SOS keeps the last one's owed cancel, which is still sent and acked; after a
 *   restart too.
 * - R5: a cancel made while the first start publish is under way owes the cancel, and so does
 *   a start's ack that comes after the local cancel.
 *
 * Then the review's acceptance cases (the Bridge's sos_r17_test.go is the same set): a start
 * publish that timed out after the Hub took it, a restart inside the first start publish, and the
 * last SOS's satellite start going out only after the next SOS began.
 */
@RunWith(AndroidJUnit4::class)
class SosControllerC10Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "sos-c10-test.db"
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private val clock = AtomicLong(1792330000123)
    private lateinit var hub: FakeHub
    private val controllers = mutableListOf<SosController>()

    class FakeHub : SosHubLink {
        override val bridgeId = "msa-flaneur"
        @Volatile var up = true
        override val isConnected get() = up
        val events = CopyOnWriteArrayList<JSONObject>()
        @Volatile var startGate: CountDownLatch? = null
        val startEntered = CountDownLatch(1)
        /** A gated start, once released, reached no broker: the publish fails and nothing is recorded. */
        @Volatile var gateDrops = false
        /** The Hub takes every start and the PUBACK never comes: recorded, and the publish fails. */
        @Volatile var startTimesOut = false
        override fun publishSos(deviceId: String, id: String, text: String, sos: Boolean, type: String, lat: Double?, lon: Double?, asMessage: Boolean) = up
        override fun publishSosEvent(event: JSONObject): Boolean {
            if (!up) return false
            val start = event.optString("kind") == "start"
            val gate = startGate
            if (start && gate != null) {
                startEntered.countDown()
                gate.await(20, TimeUnit.SECONDS)
                if (gateDrops) return false
            }
            events.add(event)
            return !(start && startTimesOut)
        }
        fun count(kind: String, sosId: String? = null) = events.count { it.optString("kind") == kind && (sosId == null || it.optString("sos_id") == sosId) }
    }

    @Before
    fun open() = runBlocking {
        context.deleteDatabase(name)
        db = AppDatabase.build(context, name)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsRepository(context)
        settings.setSosRun("")
        settings.setSosPrevRuns("")
        settings.setHubCaps("""{"v":1,"caps":["sos_v2"]}""")
        hub = FakeHub()
    }

    @After
    fun close() = runBlocking {
        controllers.forEach { it.stop() }
        // Every loop and flow of the controllers ends before the database closes: a Room query
        // still running on the closed, deleted file crashed the test process (SQLITE_IOERR_FSTAT).
        withTimeout(10_000) { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
        context.deleteDatabase(name)
        settings.setSosRun("")
        settings.setSosPrevRuns("")
        settings.setHubCaps("")
    }

    private fun controller(): SosController {
        val dispatcher = Dispatcher(
            deliveryDao = db.messageDeliveryDao(),
            accessEvaluator = AccessEvaluator(db.accessRuleDao(), db.objectGroupDao(), scope),
            failoverResolver = null,
            registry = ChannelRegistry(),
            deliveryCallback = { _, _, _, _, _, _ -> null },
            scope = scope,
        )
        val env = object : SosController.Env {
            override val dispatcher: Dispatcher = dispatcher
            override val hubReporter: HubReporter? = null
            override val sosHub: SosHubLink get() = hub
            override suspend fun modemImei() = "300434067943980"
            override suspend fun meshPaired() = false
            override fun location(): Location? = null
            override fun tak(lat: Double, lon: Double, alt: Double, reason: String) {}
            override suspend fun audit(event: String, detail: String) {}
        }
        return SosController(context, scope, db, settings, env, clock = { clock.get() }, retryMs = 50).also { controllers += it }
    }

    /** A controller with the settings kept, run loaded (restore). */
    private suspend fun restored(): SosController {
        val c = controller()
        c.restore().join() // loaded before the test acts, or a late load undoes what it did
        return c
    }


    private suspend fun until(what: String, ms: Long = 10_000, cond: suspend () -> Boolean) {
        try {
            withTimeout(ms) { while (!cond()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("$what: never", e)
        }
    }

    private fun current(): SosRun = requireNotNull(SosController.run.value)

    private suspend fun rows(ref: String) = db.messageDeliveryDao().getByRefPrefix(ref).filter { it.msgRef == ref }

    private suspend fun satWent(r: SosRun) {
        val sat = rows(r.refPrefix + "sat").single()
        db.messageDeliveryDao().setStatus(sat.id, "sent", "")
    }

    private fun ack(c: SosController, r: SosRun, kind: String, state: String) = runBlocking {
        c.onAck("""{"v":2,"sos_id":"${r.sosId}","kind":"$kind","state":"$state","rev":1}""")
    }

    private fun failCancelInserts(on: Boolean) {
        val sql = if (on) "CREATE TRIGGER review_fail_cancel BEFORE INSERT ON message_deliveries WHEN NEW.msg_ref LIKE '%cancel:sat' BEGIN SELECT RAISE(FAIL, 'injected'); END"
        else "DROP TRIGGER review_fail_cancel"
        db.openHelper.writableDatabase.execSQL(sql)
    }

    @Test
    fun aFailedCancelFrameEnqueueIsTriedAgainAndQueuedOnce() = runBlocking {
        hub.up = false
        val c = restored()
        c.start(test = false, trigger = "button")
        val r = current()
        assertTrue("the 0x07 under sos_v2", r.satV2)
        satWent(r)
        failCancelInserts(true)
        c.cancel()
        delay(400) // several rounds, each failing
        val ref = r.refPrefix + "cancel:sat"
        assertEquals(0, rows(ref).size)
        assertFalse("done after a failed enqueue", current().cancelFrameDone)
        failCancelInserts(false)
        until("the frame queued") { rows(ref).size == 1 }
        until("done") { current().cancelFrameDone }
        delay(300)
        assertEquals("one frame for one bearer", 1, rows(ref).size)
    }

    @Test
    fun aRowFromBeforeACrashIsNotQueuedTwice() = runBlocking {
        hub.up = false
        val c = restored()
        c.start(test = false, trigger = "button")
        val r = current()
        satWent(r)
        c.cancel()
        val ref = r.refPrefix + "cancel:sat"
        until("the frame") { rows(ref).size == 1 }
        c.stop()
        // The run's update was lost: a new process finds the row and a run that says not done.
        settings.setSosRun(current().copy(cancelFrameDone = false).toJson())
        restored()
        until("done after the restart") { current().cancelFrameDone }
        delay(300)
        assertEquals(1, rows(ref).size)
    }

    @Test
    fun aNewSosKeepsTheLastOnesOwedCancelAlsoAfterARestart() = runBlocking {
        val c = restored()
        c.start(test = false, trigger = "button")
        val a = current()
        until("A's start published") { hub.count("start", a.sosId) > 0 }
        ack(c, a, "start", "active")
        hub.up = false // A's cancel does not reach the Hub
        c.cancel()
        clock.addAndGet(1_000)
        c.start(test = false, trigger = "button")
        val b = current()
        assertTrue("B is a new run", b.id != a.id && b.sosId != a.sosId)
        // A restart before the connection comes back.
        c.stop()
        val after = restored()
        hub.up = true
        until("A's cancel after B and the restart") { hub.count("cancel", a.sosId) > 0 }
        ack(after, a, "cancel", "cancelled")
        until("A no longer kept") { !settings.sosPrevRuns.first().contains(a.sosId) }
        assertEquals(b.id, current().id)
        assertTrue("B still active", current().active)
        assertEquals("B has no cancel", 0, hub.count("cancel", b.sosId))
    }

    @Test
    fun aCancelDuringTheFirstStartPublishOwesTheCancel() = runBlocking {
        val gate = CountDownLatch(1)
        hub.startGate = gate
        val c = restored()
        scope.launch { c.start(test = false, trigger = "button") }
        assertTrue("the start publish began", hub.startEntered.await(10, TimeUnit.SECONDS))
        val r = current()
        assertTrue("the attempt is kept before the publish", r.hubV2)
        c.cancel()
        assertTrue("a cancel during the publish owes the Hub the cancel", current().cancelV2Owed)
        gate.countDown()
        until("the cancel") { hub.count("cancel", r.sosId) > 0 }
        ack(c, r, "cancel", "cancelled")
        assertNotNull(current().cancelHubAckedAt)
    }

    @Test
    fun aStartsAckAfterTheCancelOwesTheCancel() = runBlocking {
        hub.up = false // no start leaves, so the cancel owes nothing yet
        val c = restored()
        c.start(test = false, trigger = "button")
        val r = current()
        c.cancel()
        assertFalse("the precondition: nothing left, so nothing owed", current().cancelV2Owed)
        hub.up = true
        ack(c, r, "start", "active")
        assertTrue("the late start ack made the cancel owed", current().cancelV2Owed)
        until("the cancel") { hub.count("cancel", r.sosId) > 0 }
        assertTrue(SosV2.cancelPending(current()))
    }

    /**
     * R5: every start publish times out after the Hub took the start, so the phone never sees one
     * reach the broker; a cancel then still owes the Hub the cancel.
     */
    @Test
    fun aStartPublishThatTimedOutAfterTheHubTookItOwesTheCancel() = runBlocking {
        hub.startTimesOut = true
        val c = restored()
        c.start(test = false, trigger = "button")
        val r = current()
        until("the Hub has the start") { hub.count("start", r.sosId) > 0 }
        assertNull("the precondition: the phone saw the start reach the broker", current().hubPublishedAt)
        c.cancel()
        assertTrue("a start the Hub took, though its publish failed, owes the Hub no cancel", current().cancelV2Owed)
        until("the cancel") { hub.count("cancel", r.sosId) > 0 }
    }

    /**
     * R5: the app stops while the first start publish is under way. The next process finds the
     * attempt kept, so a cancel made after the restart owes the Hub the cancel, with no start
     * publish of its own before it.
     */
    @Test
    fun aRestartInsideTheFirstStartPublishOwesTheCancel() = runBlocking {
        val gate = CountDownLatch(1)
        val stuck = hub
        stuck.startGate = gate
        stuck.gateDrops = true // released at the end: the old publish reached no broker
        val c = restored()
        scope.launch { c.start(test = false, trigger = "button") }
        assertTrue("the start publish began", stuck.startEntered.await(10, TimeUnit.SECONDS))
        val r = current()
        c.stop() // the old process ends inside its publish
        hub = FakeHub().apply { up = false } // no session yet: the new process publishes nothing
        val after = restored()
        after.cancel()
        assertTrue("after a restart inside the start publish, the cancel owes the Hub nothing", current().cancelV2Owed)
        assertEquals("the precondition: the new process published a start", 0, hub.count("start"))
        hub.up = true
        until("the cancel after the restart") { hub.count("cancel", r.sosId) > 0 }
        gate.countDown()
        delay(200)
        assertEquals("the precondition: the old publish reached the broker", 0, stuck.count("start"))
    }

    /**
     * R4: A is cancelled while its satellite start is with the modem, A's cancel gets no ack, B
     * starts, and only then does A's start go out. A's 0x08 still goes on the satellite once the
     * grace is over; B stays active and owes no cancel.
     */
    @Test
    fun aSatelliteStartThatGoesAfterTheNextSosStillGetsItsCancelFrame() = runBlocking {
        val c = restored()
        c.start(test = false, trigger = "button")
        val a = current()
        assertTrue("the precondition: A's satellite leg is the 0x07", a.satV2)
        val sat = rows(a.refPrefix + "sat").single()
        db.messageDeliveryDao().setStatus(sat.id, "sending", "") // with the modem
        until("A's start published") { hub.count("start", a.sosId) > 0 }
        ack(c, a, "start", "active")
        c.cancel()
        until("A's cancel published") { hub.count("cancel", a.sosId) > 0 }
        clock.addAndGet(1_000)
        c.start(test = false, trigger = "button")
        val b = current()
        assertTrue("B is a new run", b.id != a.id && b.sosId != a.sosId)
        db.messageDeliveryDao().setStatus(sat.id, "sent", "") // A's start goes out, after B began
        c.onSent(rows(a.refPrefix + "sat").single())
        clock.addAndGet(SosV2.CANCEL_ACK_GRACE_MS + 1_000)
        until("A's 0x08 after the grace") { rows(a.refPrefix + "cancel:sat").size == 1 }
        assertEquals("B has a cancel frame", 0, rows(b.refPrefix + "cancel:sat").size)
        assertEquals(b.id, current().id)
        assertTrue("B is not the active SOS", current().active)
        assertEquals("a cancel was published for B", 0, hub.count("cancel", b.sosId))
        delay(300)
        assertEquals("one frame for A's one bearer", 1, rows(a.refPrefix + "cancel:sat").size)
    }
}
