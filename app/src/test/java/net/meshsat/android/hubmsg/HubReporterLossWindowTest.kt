package net.meshsat.android.hubmsg

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.hub.HubReporterConfig
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * The window between Paho's own automatic reconnect and its connectComplete callback (r14 review,
 * Android loss-keeps-ready): after a loss the client can report connected again before the
 * callback that resubscribes runs. The app's HubReporter, wired as in production, must read not
 * ready and publish nothing in that window, and walk once more only after its own subscribe holds.
 * Paho is replaced by a client that never touches the network and hands back the reporter's
 * callback, so the test drives the real connectionLost and connectComplete. [MESHSAT-1584]
 */
class HubReporterLossWindowTest {

    /** Connected when told; every subscribe granted in full; publishes go nowhere. */
    private class FakePaho(uri: String, id: String) : MqttClient(uri, id, MemoryPersistence()) {
        @Volatile var up = false
        @Volatile var callback: MqttCallbackExtended? = null
        val subscribes = AtomicInteger()

        override fun connect(options: MqttConnectOptions?) { up = true }
        override fun isConnected() = up
        override fun setCallback(callback: MqttCallback?) { this.callback = callback as MqttCallbackExtended }
        override fun publish(topic: String?, message: MqttMessage?) {}
        override fun disconnect(quiesceTimeout: Long) { up = false }
        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) { up = false }

        override fun subscribeWithResponse(topicFilters: Array<String>, qos: IntArray): IMqttToken {
            subscribes.incrementAndGet()
            val grants = IntArray(topicFilters.size) { 1 }
            return Proxy.newProxyInstance(IMqttToken::class.java.classLoader, arrayOf(IMqttToken::class.java)) { _, m, _ ->
                when {
                    m.name == "getGrantedQos" -> grants
                    m.returnType == java.lang.Boolean.TYPE -> true
                    m.returnType == Integer.TYPE -> 0
                    else -> null
                }
            } as IMqttToken
        }
    }

    private suspend fun until(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (ok()) return true
            delay(20)
        }
        return ok()
    }

    @Test
    fun `connected again before connectComplete is not ready, and the walk waits for its own subscribe`() = runBlocking {
        jvmBuildModel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var fake: FakePaho? = null
        val r = HubReporter(
            object : ContextWrapper(null) {}, scope,
            HubReporterConfig(hubUrl = "tcp://hub.invalid:1883", bridgeId = "loss-window", healthIntervalSec = 300),
        ) { uri, id -> FakePaho(uri, id).also { fake = it } }
        val walks = AtomicInteger()
        r.onMsgIn = {}
        r.onMsgStatus = {}
        r.onConnected = { walks.incrementAndGet() }
        try {
            withTimeout(20_000) {
                r.start()
                assertTrue("ready on the first connect", until(5_000) { r.isMsgReady && walks.get() == 1 })
                val f = fake!!
                val cb = f.callback!!

                // The real loss callback; then Paho reconnects by itself and reports connected
                // before it calls connectComplete.
                f.up = false
                cb.connectionLost(java.io.IOException("socket closed"))
                f.up = true
                assertTrue(r.isConnected)
                assertFalse("ready on a session whose subscriptions are gone", r.isMsgReady)
                assertEquals(HubMsgRoute.Published.NotSent, r.publishMsg("out", "{}"))
                assertEquals(1, walks.get())

                // connectComplete resubscribes on the same client: ready again, one new walk.
                cb.connectComplete(true, "tcp://hub.invalid:1883")
                assertTrue("ready after its own subscribe", until(5_000) { r.isMsgReady && walks.get() == 2 })
                delay(300)
                assertEquals("one new walk", 2, walks.get())
                assertEquals(2, f.subscribes.get())
            }
        } finally {
            r.stop()
            delay(200)
            scope.cancel()
        }
    }
}
