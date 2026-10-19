package net.meshsat.android.hubmsg

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.meshsat.android.hub.SessionReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The Hub app route starts its walk only once a session's subscriptions hold (r13 review, PH-R2),
 * wired as the app wires it: the route's publisher is ready when the link is up AND the session's
 * subscribe was granted in full, and readiness, not the transport connect, calls onConnected. A
 * refused subscribe is tried again on the same session, without a reconnect. [MESHSAT-1584]
 */
class SessionReadinessTest {

    /** A retry loop that never ends fails the test instead of hanging the run (r14 review). */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private class World {
        var t = 1_792_400_000_000L
        val readiness = SessionReadiness(minMs = 40, maxMs = 160)
        val hub = ContractHub()
        val storeA = MemHubMsgStore()
        val storeB = MemHubMsgStore()
        val la = hub.link("bridge:phone-a")
        val lb = hub.link("bridge:kit-b")
        val publisher = object : HubMsgRoute.Publisher {
            override val principal = la.principal
            override fun isConnected() = la.isConnected() && readiness.ready
            override fun publishMsg(kind: String, payload: String): HubMsgRoute.Published =
                if (!readiness.ready) HubMsgRoute.Published.NotSent else la.publishMsg(kind, payload)
        }
        val a = HubMsgRoute(storeA, publisher, { t }).also { hub.routes[la.principal] = it }
        val b = HubMsgRoute(storeB, lb, { t }).also { hub.routes[lb.principal] = it }
        var readyCalls = 0

        /** A session whose subscribe holds at once. */
        fun readyNow() {
            assertTrue(readiness.markReady(readiness.invalidate()))
            a.onConnected()
        }

        /** A text whose completion is lost while the phone is off the Hub. */
        suspend fun sendAndLoseCompletion(): Long {
            val id = (a.send(lb.principal, "while the subscribe is refused") as HubMsgRoute.SendResult.Queued).messageId
            hub.pumpOne()
            la.up = false
            hub.pump()
            la.up = true
            return id
        }

        fun status(id: Long) = storeA.row(id)!!.forwardedTo
    }

    @Test
    fun `a refused subscribe is tried again on the same session, and the walk waits for it`() = runBlocking {
        val w = World()
        w.readyNow()
        w.a.pass()
        val id = w.sendAndLoseCompletion()
        assertEquals(HubMsgStatus.FORWARDED, w.status(id))
        // An automatic reconnect: the transport is up, the subscribe is refused once.
        val session = w.readiness.invalidate()
        var attempts = 0
        val job = launch {
            w.readiness.establish(
                session, alive = { w.la.up },
                subscribe = { if (++attempts == 1) error("SUBACK 0x80") },
                onReady = { w.readyCalls++; w.a.onConnected() },
            )
        }
        kotlinx.coroutines.yield() // the first attempt is refused before the passes below
        // Not ready yet: passes publish nothing and no repeat is spent.
        repeat(2) { w.t += 60_000; w.a.pass(); w.hub.pump() }
        assertFalse(w.readiness.ready)
        assertEquals(0, w.readyCalls)
        assertEquals(0, w.hub.replays)
        assertEquals(HubMsgStatus.FORWARDED, w.status(id))
        job.join()
        assertEquals("tried again without a reconnect", 2, attempts)
        assertEquals(1, w.readyCalls)
        assertTrue(w.readiness.ready)
        w.a.pass()
        w.hub.pump()
        assertEquals("recovered once the subscriptions hold", HubMsgStatus.DELIVERED, w.status(id))
        assertEquals(1, w.hub.replays)
        assertEquals(1, w.storeB.rows().count { it.direction == "rx" })
    }

    @Test
    fun `a refused or missing grant in the SUBACK is a failure`() {
        assertTrue(SessionReadiness.allGranted(3, intArrayOf(1, 1, 1)))
        assertTrue(SessionReadiness.allGranted(2, intArrayOf(0, 2)))
        assertFalse(SessionReadiness.allGranted(3, intArrayOf(1, 0x80, 1)))
        assertFalse(SessionReadiness.allGranted(3, intArrayOf(1, 1)))
        assertFalse(SessionReadiness.allGranted(1, null))
        assertFalse(SessionReadiness.allGranted(1, intArrayOf(-1)))
    }

    @Test
    fun `a drop while subscribing ends that session's attempts, and the next session's subscribe makes it ready`() = runBlocking {
        val w = World()
        val first = w.readiness.invalidate()
        var attempts = 0
        val job = launch {
            val ok = w.readiness.establish(first, alive = { w.la.up }, subscribe = { attempts++; error("refused") }, onReady = { w.readyCalls++ })
            assertFalse(ok)
        }
        kotlinx.coroutines.delay(60) // one refused attempt and its wait
        w.la.up = false
        w.readiness.invalidate() // the drop
        job.join()
        assertTrue(attempts in 1..2)
        assertEquals(0, w.readyCalls)
        assertFalse(w.readiness.ready)
        w.la.up = true
        val second = w.readiness.invalidate() // the reconnect
        assertTrue(w.readiness.establish(second, alive = { w.la.up }, subscribe = {}, onReady = { w.readyCalls++ }))
        assertEquals(1, w.readyCalls)
        assertTrue(w.readiness.ready)
    }

    @Test
    fun `a session replaced while its subscribe is in flight is never marked ready`() = runBlocking {
        val w = World()
        val session = w.readiness.invalidate()
        val ok = w.readiness.establish(session, alive = { true }, subscribe = { w.readiness.invalidate() }, onReady = { w.readyCalls++ })
        assertFalse(ok)
        assertFalse(w.readiness.ready)
        assertEquals(0, w.readyCalls)
    }

    @Test
    fun `on the first connect a refused subscribe leaves the route untouched`() = runBlocking {
        val w = World()
        val session = w.readiness.invalidate() // the connect
        assertFalse("refused", w.readiness.attempt(session, subscribe = { error("SUBACK 0x80") }, onReady = { w.readyCalls++ }))
        val id = (w.a.send(w.lb.principal, "before any subscription holds") as HubMsgRoute.SendResult.Queued).messageId
        w.a.pass()
        assertEquals("nothing went out", HubMsgStatus.QUEUED, w.status(id))
        assertTrue(w.hub.queue.isEmpty())
        assertEquals(0, w.readyCalls)
        // Tried again on the same connection, and this time every filter was granted.
        assertTrue(w.readiness.establish(session, alive = { true }, subscribe = {}, onReady = { w.readyCalls++; w.a.onConnected() }, waitFirst = true))
        assertEquals(1, w.readyCalls)
        w.a.pass()
        w.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, w.status(id))
    }
}
