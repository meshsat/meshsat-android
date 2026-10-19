package net.meshsat.android.hubmsg

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.hub.HubReporterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's HubReporter (Paho) against scripts/mqtt-stub-broker.py, which refuses chosen SUBACK
 * codes and closes the socket on cue (r13 review, PH-R2): a filter refused on the first connect and
 * on an automatic reconnect is tried again on the same connection, the route's walk and publishes
 * wait for it, and a drop during setup ends that connection's attempts. The scenario is a word in
 * the bridge id (see the script). Skipped unless MESHSAT_TEST_STUB_MQTT names the stub, e.g.
 * tcp://127.0.0.1:18883 (scripts/mqtt-stub-broker.py 127.0.0.1 18883 600). [MESHSAT-1584]
 */
class HubReporterStubBrokerTest {

    private class Probe(scope: CoroutineScope, url: String, bridgeId: String) {
        val r = HubReporter(
            object : ContextWrapper(null) {}, scope,
            HubReporterConfig(hubUrl = url, bridgeId = bridgeId, healthIntervalSec = 300),
        )
        /** What isMsgReady read each time the route's connected hook ran. */
        val readyAtHook = CopyOnWriteArrayList<Boolean>()
        /** The reporter's states in order. */
        val states = CopyOnWriteArrayList<HubReporter.State>()
        /** When each walk began, and when each connect was seen. */
        val hookAt = CopyOnWriteArrayList<Long>()
        val connectedAt = CopyOnWriteArrayList<Long>()

        init {
            r.onMsgIn = {}
            r.onMsgStatus = {}
            r.onConnected = {
                hookAt += System.currentTimeMillis()
                readyAtHook += r.isMsgReady
            }
            scope.launch {
                r.state.collect {
                    if (it == HubReporter.State.Connected) connectedAt += System.currentTimeMillis()
                    states += it
                }
            }
        }

        fun connects() = states.count { it == HubReporter.State.Connected }
    }

    private suspend fun until(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (ok()) return true
            delay(50)
        }
        return ok()
    }

    private fun withProbe(word: String, body: suspend (Probe) -> Unit) = runBlocking {
        val url = System.getenv("MESHSAT_TEST_STUB_MQTT")?.trim().orEmpty()
        assumeTrue("MESHSAT_TEST_STUB_MQTT not set", url.isNotBlank())
        jvmBuildModel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = Probe(scope, url, "a-$word-${UUID.randomUUID().toString().take(4)}")
        try {
            p.r.start()
            body(p)
        } finally {
            p.r.stop()
            delay(300)
            scope.cancel()
        }
    }

    @Test
    fun `a filter refused on the first connect is tried again on the same connection, and the walk waits`() = withProbe("refuse1") { p ->
        assertTrue(until(10_000) { p.r.state.value == HubReporter.State.Connected })
        val t0 = System.currentTimeMillis()
        delay(500)
        assertFalse("not ready on a refused SUBACK", p.r.isMsgReady)
        assertEquals(emptyList<Boolean>(), p.readyAtHook.toList())
        assertEquals(HubMsgRoute.Published.NotSent, p.r.publishMsg("out", "{}"))
        assertTrue("tried again without a reconnect", until(15_000) { p.readyAtHook.size == 1 })
        assertTrue("after the retry's wait", System.currentTimeMillis() - t0 >= 1_000)
        assertEquals(listOf(true), p.readyAtHook.toList())
        assertEquals("the connection stayed up: ${p.states}", 1, p.connects())
        assertEquals(HubMsgRoute.Published.Ok, p.r.publishMsg("out", "{}"))
    }

    @Test
    fun `an automatic reconnect is unready at once, and its refused resubscribe is tried again`() = withProbe("reconn1") { p ->
        assertTrue(until(10_000) { p.readyAtHook.size == 1 && p.r.isMsgReady })
        // The stub closes the socket 3 s after the SUBACK.
        assertTrue("unready on the loss", until(10_000) { !p.r.isMsgReady })
        assertEquals(HubMsgRoute.Published.NotSent, p.r.publishMsg("out", "{}"))
        // Paho reconnects by itself; the first resubscribe is refused, the next one holds.
        assertTrue("reconnected: ${p.states}", until(20_000) { p.connects() >= 2 })
        assertEquals("no walk on the refused resubscribe", 1, p.readyAtHook.size)
        assertTrue("tried again on the same connection", until(15_000) { p.readyAtHook.size == 2 })
        assertEquals(listOf(true, true), p.readyAtHook.toList())
        val waited = p.hookAt[1] - p.connectedAt[1]
        assertTrue("the second walk waited for the retry, not for the refused SUBACK: $waited ms", waited >= 1_000)
    }

    @Test
    fun `a drop while subscribing ends that connection's attempts, and the next connection makes it ready`() = withProbe("dropset") { p ->
        assertTrue(until(10_000) { p.r.state.value == HubReporter.State.Connected })
        // Connection 1 refuses every filter and is closed after its second SUBACK, about 2 s in.
        assertTrue(until(30_000) { p.readyAtHook.size == 1 })
        assertEquals(listOf(true), p.readyAtHook.toList())
        assertTrue("through a reconnect: ${p.states}", p.connects() >= 2)
        delay(5_000)
        assertEquals("one walk, on the second connection", 1, p.readyAtHook.size)
    }
}
