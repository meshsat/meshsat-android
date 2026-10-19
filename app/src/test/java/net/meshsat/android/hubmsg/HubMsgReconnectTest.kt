package net.meshsat.android.hubmsg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reconnect in the middle of the route's own work (r13 review, PH-R1): during a repeat, during a
 * delayed listing, during a listing that comes back empty, just before an old walk marks itself
 * done, and with an owed operation still in its wait; two passes at once; and a pass that began
 * before a reconnect, which must neither send nor read anything for the new connection. Each
 * recovery case loses the Hub's answer while the phone is off the Hub, then reconnects once; the
 * new connection's walk must recover it with no further reconnect, under the same operation and
 * event ids, with one chat row at the far end. [MESHSAT-1584]
 */
class HubMsgReconnectTest {

    /** The route's store, with a hook inside the walk's listing (a slow database). */
    private class SlowStore(val inner: MemHubMsgStore) : HubMsgStore by inner {
        var onListing: (suspend () -> Unit)? = null
        var listings = 0
        override suspend fun toReconcile(nowMs: Long, withinMs: Long, after: HubOpCursor, limit: Int): List<HubOp> {
            listings++
            onListing?.let { onListing = null; it() }
            return inner.toReconcile(nowMs, withinMs, after, limit)
        }
    }

    private class World {
        var t = 1_792_400_000_000L
        val hub = ContractHub()
        val memA = MemHubMsgStore()
        val storeA = SlowStore(memA)
        val storeB = MemHubMsgStore()
        val la = hub.link("bridge:phone-a")
        val lb = hub.link("bridge:kit-b")
        lateinit var a: HubMsgRoute
        /** Runs once, inside the publish of the nth "out" (1-based), after the frame left. */
        var onOut: MutableMap<Int, suspend () -> Unit> = HashMap()
        var outs = 0
        val publisher = object : HubMsgRoute.Publisher {
            override val principal = la.principal
            override fun isConnected() = la.isConnected()
            override fun publishMsg(kind: String, payload: String): HubMsgRoute.Published {
                val result = la.publishMsg(kind, payload)
                if (kind == "out") onOut.remove(++outs)?.let { runBlocking { it() } }
                return result
            }
        }

        init {
            a = HubMsgRoute(storeA, publisher, { t })
            hub.routes[la.principal] = a
            hub.routes[lb.principal] = HubMsgRoute(storeB, lb, { t })
        }

        /** The phone off the Hub while everything queued is carried (its answers are lost), then back. */
        suspend fun dropAndReconnect() {
            la.up = false
            hub.pump()
            la.up = true
            a.onConnected()
        }

        /** A text whose completion is lost while the phone is off the Hub; the phone is back after. */
        suspend fun sendAndLoseCompletion(text: String): Long {
            val id = (a.send(lb.principal, text) as HubMsgRoute.SendResult.Queued).messageId
            hub.pumpOne() // delivered to kit-b; hub_forwarded reaches the phone
            la.up = false
            hub.pump() // kit-b's answer: recipient_app_stored, published while the phone is away
            la.up = true
            return id
        }

        /** n texts the Hub holds for kit-b while it is away: operations without a completion. */
        suspend fun held(n: Int) {
            lb.up = false
            repeat(n) { a.send(lb.principal, "held $it") }
            hub.pump()
        }

        fun status(id: Long) = memA.row(id)!!.forwardedTo
        fun opIds() = memA.ops().map { it.opId }.toSet()
    }

    /** What every case must show: delivered, the same operations and events, one row each at kit-b. */
    private fun assertRecovered(w: World, ids: List<Long>, opsBefore: Set<String>) {
        for (id in ids) assertEquals("delivered without another reconnect", HubMsgStatus.DELIVERED, w.status(id))
        assertEquals("the same operations", opsBefore, w.opIds())
        assertEquals("the Hub kept one operation each", opsBefore, w.hub.ops.keys)
        for (op in opsBefore) {
            val events = w.memA.keptEvents(op).map { it.event.eventId }
            assertEquals("each event kept once", events.toSet().size, events.size)
        }
        assertEquals("one chat row each at kit-b", ids.size, w.storeB.rows().count { it.direction == "rx" })
    }

    @Test
    fun `a reconnect during a repeat does not count the repeat for the new connection`() = runBlocking {
        val w = World()
        w.a.onConnected()
        w.a.pass() // the walk over nothing
        val id = w.sendAndLoseCompletion("lost during a repeat")
        val ops = w.opIds()
        w.a.onConnected() // generation 2: its walk repeats the submission...
        w.onOut[2] = { w.dropAndReconnect() } // ...and the answer to that repeat is lost too: generation 3
        w.a.pass()
        repeat(3) { w.t += 60_000; w.a.pass(); w.hub.pump() }
        assertRecovered(w, listOf(id), ops)
    }

    @Test
    fun `a reconnect during a delayed listing leaves the new walk whole`() = runBlocking {
        val w = World()
        w.a.onConnected()
        w.a.pass()
        val ids = listOf(w.sendAndLoseCompletion("one"), w.sendAndLoseCompletion("two"))
        val ops = w.opIds()
        w.a.onConnected()
        // The listing of generation 2's walk is slow; the phone drops and comes back meanwhile, and
        // the old pass's repeats then lose their answers in one more drop.
        w.storeA.onListing = { w.la.up = false; w.la.up = true; w.a.onConnected() }
        w.onOut[3] = { w.dropAndReconnect() }
        w.a.pass()
        repeat(3) { w.t += 60_000; w.a.pass(); w.hub.pump() }
        assertRecovered(w, ids, ops)
    }

    @Test
    fun `a reconnect just before an old walk marks itself done does not end the new walk`() = runBlocking {
        val w = World()
        w.a.onConnected()
        w.a.pass()
        val ids = listOf(w.sendAndLoseCompletion("first"), w.sendAndLoseCompletion("lost at the end"))
        val ops = w.opIds()
        w.a.onConnected()
        // The walk's last repeat loses its answer and the phone reconnects before the walk ends.
        w.onOut[4] = { w.dropAndReconnect() }
        w.a.pass()
        w.hub.pump()
        repeat(3) { w.t += 60_000; w.a.pass(); w.hub.pump() }
        assertRecovered(w, ids, ops)
    }

    /**
     * The connect's pass and the periodic one at the same time: one waits for the other, so the walk
     * repeats each operation once for its connection (PH-R1).
     */
    @Test
    fun `two passes at once repeat an operation once for the connection`() = runBlocking {
        val w = World()
        w.a.onConnected()
        w.a.pass()
        val id = w.sendAndLoseCompletion("two passes")
        val ops = w.opIds()
        w.a.onConnected()
        var second: Job? = null
        // A second pass starts on another thread while the first one's repeat is on the way.
        w.onOut[2] = {
            second = CoroutineScope(Dispatchers.Default).launch { w.a.pass() }
            Thread.sleep(300)
        }
        w.a.pass()
        second!!.join()
        w.hub.pump()
        assertEquals("one repeat for the connection", 1, w.hub.replays)
        assertRecovered(w, listOf(id), ops)
    }

    @Test
    fun `an owed operation still in its wait is asked by the new connection's walk at once`() = runBlocking {
        val w = World()
        w.a.onConnected()
        w.a.pass()
        // hub_forwarded cannot be kept: the operation is owed in the database.
        w.memA.failEvent = { it.kind == "hub_forwarded" }
        val id = w.sendAndLoseCompletion("owed and waiting")
        w.memA.failEvent = { false }
        val ops = w.opIds()
        w.t += 31_000
        // The owed repeat goes out, and its answers are lost in a drop: a new connection, before
        // this pass reaches the walk, which it then leaves unread.
        w.onOut[2] = { w.dropAndReconnect() }
        val listings = w.storeA.listings
        w.a.pass()
        assertEquals("the old pass read the new connection's walk", listings, w.storeA.listings)
        // 10 s later the operation is still in its 30 s wait; the new connection's walk asks anyway.
        w.t += 10_000
        w.a.pass()
        w.hub.pump()
        assertRecovered(w, listOf(id), ops)
    }

    @Test
    fun `a reconnect during a listing that comes back empty does not end the new walk`() = runBlocking {
        val w = World()
        w.a.onConnected()
        w.a.pass()
        w.held(HubMsgRoute.WALK_BATCH)
        w.a.onConnected()
        val before = w.hub.replays
        w.a.pass() // a full batch: every operation asked, the walk not done yet
        w.hub.pump()
        assertEquals(HubMsgRoute.WALK_BATCH, w.hub.replays - before)
        // The next listing, past the last operation, comes back empty; the phone reconnects meanwhile.
        w.storeA.onListing = { w.a.onConnected() }
        w.a.pass()
        w.hub.pump()
        repeat(2) { w.t += 60_000; w.a.pass(); w.hub.pump() }
        assertEquals("the new connection's walk asked every operation", 2 * HubMsgRoute.WALK_BATCH, w.hub.replays - before)
    }

    @Test
    fun `a pass that began before a reconnect sends nothing after it`() = runBlocking {
        val w = World()
        w.a.onConnected()
        w.a.pass()
        w.held(3)
        w.a.onConnected()
        w.storeA.onListing = { w.a.onConnected() } // the walk's listing is slow; the phone reconnects meanwhile
        val outs = w.outs
        w.a.pass()
        assertEquals("the old pass repeated for the new connection", outs, w.outs)
        w.hub.pump()
        w.a.pass()
        w.hub.pump()
        assertEquals("the new connection's walk, once each", 3, w.outs - outs)
    }
}
