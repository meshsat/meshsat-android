package net.meshsat.android.hubmsg

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.FakeJsprModem
import net.meshsat.android.bt.Iridium9704Spp
import net.meshsat.android.engine.SatelliteChain
import net.meshsat.android.hub.BirthStampStore
import net.meshsat.android.hub.BirthStamper
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.hub.HubReporterConfig
import net.meshsat.android.service.GatewayService
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The r33 review's schedules A1 and A2 (R33-R1) on the app's real HubReporter: births go out one
 * at a time, an older birth that the broker took cannot clear a newer one that failed, a stamp
 * always labels the snapshot built with it, and the current declaration goes out by itself. The
 * broker is a client that never touches the network and holds or fails each birth on cue; the
 * 9704 is the real driver on a scripted modem. With MESHSAT_BIRTH_ORDER_OUT set to a directory,
 * the births taken are written there, one file per schedule, for the Hub's fixture test.
 * [MESHSAT-1623]
 */
class HubBirthOrderTest {

    /** What the broker does with the next birth. */
    private sealed interface Next {
        data object Take : Next
        data object Fail : Next
        data class Hold(val release: CountDownLatch, val thenTake: Boolean) : Next
    }

    /** Connected; subscribes granted; births held or failed on cue and counted while in flight. */
    private class Broker(uri: String, id: String, val script: java.util.concurrent.LinkedBlockingQueue<Next>) :
        MqttClient(uri, id, MemoryPersistence()) {
        @Volatile var up = false
        @Volatile var callback: org.eclipse.paho.client.mqttv3.MqttCallbackExtended? = null
        val taken = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        override fun connect(options: MqttConnectOptions?) { up = true }
        override fun isConnected() = up
        override fun setCallback(callback: MqttCallback?) { this.callback = callback as? org.eclipse.paho.client.mqttv3.MqttCallbackExtended }
        override fun disconnect(quiesceTimeout: Long) { up = false }
        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) { up = false }
        override fun subscribeWithResponse(topicFilters: Array<String>, qos: IntArray): IMqttToken {
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

        override fun publish(topic: String?, message: MqttMessage?) {
            if (topic?.endsWith("/birth") != true) return
            attempts.incrementAndGet()
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                val take = when (val next = script.poll() ?: Next.Take) {
                    Next.Take -> true
                    Next.Fail -> false
                    is Next.Hold -> {
                        next.release.await(60, TimeUnit.SECONDS)
                        next.thenTake
                    }
                }
                if (!take) throw MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt())
                taken += String(message!!.payload, Charsets.UTF_8)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * The stamp floor. [armPause] marks the next stamp taken, so the snapshot built right after it
     * (the declaration provider's next call) can be held: a birth paused after its stamp is
     * reserved and before its snapshot, as the review's A2 has it.
     */
    private class Store : BirthStampStore {
        @Volatile var stored: String? = null
        val saves = AtomicInteger()
        private val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val justStamped = java.util.concurrent.atomic.AtomicBoolean(false)
        fun armPause() = armed.set(true)
        override fun lastBirthStamp(): Instant? = stored?.let { BirthStamper.parse(it) }
        override fun saveBirthStamp(stamp: Instant) {
            stored = BirthStamper.format(stamp)
            saves.incrementAndGet()
            if (armed.compareAndSet(true, false)) justStamped.set(true)
        }
    }

    private val store = Store()

    private val imei = "300000000000001"
    private lateinit var scope: CoroutineScope
    private lateinit var spp: Iridium9704Spp
    private val mode = AtomicReference<String?>(SatelliteChain.MODE_PLAIN)

    private fun setModem(s: Iridium9704Spp?) {
        GatewayService::class.java.getDeclaredField("iridium9704Spp").apply { isAccessible = true }.set(null, s)
    }

    @Before
    fun up() = runBlocking {
        jvmBuildModel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        spp = Iridium9704Spp(object : ContextWrapper(null) {})
        spp.attach(FakeJsprModem(imei))
        withTimeout(30_000) { spp.state.first { it == Iridium9704Spp.State.Ready } }
        setModem(spp)
    }

    @After
    fun down() {
        setModem(null)
        spp.detach()
        scope.cancel()
    }

    /** What the birth declares for the modem, "" for nothing. */
    private fun format(birth: String): String {
        val a = JSONObject(birth).getJSONArray("interfaces")
        return (0 until a.length()).map { a.getJSONObject(it) }.first { it.optString("imei") == imei }.optString("egress_format")
    }

    private fun stamp(birth: String): Instant = Instant.parse(JSONObject(birth).getString("timestamp"))

    private val script = java.util.concurrent.LinkedBlockingQueue<Next>()

    private fun reporter(broker: AtomicReference<Broker?>, healthSec: Int = 1, provider: () -> String?) = HubReporter(
        object : ContextWrapper(null) {}, scope,
        HubReporterConfig(hubUrl = "tcp://hub.invalid:1883", bridgeId = "msa-order-test", healthIntervalSec = healthSec),
        birthStamps = store,
    ) { uri, id -> Broker(uri, id, script).also { broker.set(it) } }.also { it.imtEgressFormat = provider }

    private suspend fun until(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (ok()) return true
            delay(50)
        }
        return ok()
    }

    private fun writeOut(name: String, births: List<String>) {
        val dir = System.getenv("MESHSAT_BIRTH_ORDER_OUT")?.takeIf { it.isNotBlank() } ?: return
        File(dir).mkdirs()
        File(dir, "$name.jsonl").writeText(births.joinToString("\n") + "\n")
    }

    private fun assertOrdered(births: List<String>) {
        births.zipWithNext().forEach { (a, b) -> assertTrue("${stamp(b)} after ${stamp(a)}", stamp(b).isAfter(stamp(a))) }
    }

    /**
     * A1: the first birth ([from]) is held at the broker; the chain turns [to]; the next birth
     * fails; then the held one is taken. Nothing changes after that, and the [to] birth must still
     * go out by itself, with no two births in flight at any time.
     */
    private fun a1(from: String, to: String, name: String) = runBlocking {
        mode.set(from)
        val brokerRef = AtomicReference<Broker?>()
        val r = reporter(brokerRef) { SatelliteChain.egressFormat(mode.get()) }
        val release = CountDownLatch(1)
        script.put(Next.Hold(release, thenTake = true))
        script.put(Next.Fail)
        val want = SatelliteChain.egressFormat(to)
        try {
            r.start()
            val b = withTimeout(10_000) { while (brokerRef.get() == null) delay(10); brokerRef.get()!! }
            assertTrue("the first birth reached the broker", until(10_000) { b.attempts.get() >= 1 })
            mode.set(to)
            delay(3_000) // health ticks ask for the new birth while the first one is held
            assertEquals("nothing else goes out while a birth is in flight", 1, b.attempts.get())
            release.countDown()
            assertTrue(
                "the $want birth went out by itself",
                until(30_000) { b.taken.size >= 2 && format(b.taken.last()) == want },
            )
            assertEquals("one birth at a time", 1, b.maxInFlight.get())
            assertTrue("the failed $want birth was tried again", b.attempts.get() >= 3)
            assertEquals(SatelliteChain.egressFormat(from), format(b.taken.first()))
            assertOrdered(b.taken)
            writeOut(name, b.taken)
        } finally {
            release.countDown()
            r.stop()
        }
    }

    @Test
    fun `an older birth taken late does not clear a newer one that failed`() =
        a1(SatelliteChain.MODE_PLAIN, SatelliteChain.MODE_SEALED, "a1-older-taken-late")

    /** A1 the other way round: sealed held, the chain turns plain (r33: the inverse format change). */
    @Test
    fun `an older sealed birth taken late does not clear a newer plain one that failed`() =
        a1(SatelliteChain.MODE_SEALED, SatelliteChain.MODE_PLAIN, "a1-inverse-older-taken-late")

    /**
     * A1 with the completions the other way round (r33: the passing control): the first birth is
     * taken at once, the chain turns sealed, and the sealed birth then fails. It is tried again
     * until it is taken.
     */
    @Test
    fun `an older birth taken first and a newer one that fails after it is tried again`() = runBlocking {
        mode.set(SatelliteChain.MODE_PLAIN)
        val brokerRef = AtomicReference<Broker?>()
        val r = reporter(brokerRef) { SatelliteChain.egressFormat(mode.get()) }
        script.put(Next.Take)
        script.put(Next.Fail)
        try {
            r.start()
            val b = withTimeout(10_000) { while (brokerRef.get() == null) delay(10); brokerRef.get()!! }
            assertTrue("the plain birth was taken", until(10_000) { b.taken.size == 1 })
            assertEquals("plain", format(b.taken.first()))
            mode.set(SatelliteChain.MODE_SEALED)
            assertTrue("the sealed birth was taken after it failed", until(30_000) { format(b.taken.last()) == "sealed" })
            assertTrue("the failed sealed birth was tried again", b.attempts.get() >= 3)
            assertEquals(1, b.maxInFlight.get())
            assertOrdered(b.taken)
            writeOut("a1-control-reverse-order", b.taken)
        } finally {
            r.stop()
        }
    }

    /**
     * The control for A1: the birth fails twice, then is taken. The health loop is slow here (five
     * minutes), so only the worker's own retry can bring it.
     */
    @Test
    fun `a failed birth is tried again until the current declaration is taken`() = runBlocking {
        val brokerRef = AtomicReference<Broker?>()
        mode.set(SatelliteChain.MODE_SEALED)
        val r = reporter(brokerRef, healthSec = 300) { SatelliteChain.egressFormat(mode.get()) }
        script.put(Next.Fail)
        script.put(Next.Fail)
        try {
            r.start()
            val b = withTimeout(10_000) { while (brokerRef.get() == null) delay(10); brokerRef.get()!! }
            assertTrue("sealed taken after two failures", until(40_000) { b.taken.isNotEmpty() && format(b.taken.last()) == "sealed" })
            assertTrue(b.attempts.get() >= 3)
            assertEquals(1, b.maxInFlight.get())
            assertOrdered(b.taken)
        } finally {
            r.stop()
        }
    }

    /**
     * A2: a birth (the one a reconnect asks for, while the health loop runs) pauses after its stamp
     * is reserved and before its snapshot. The chain turns from [from] to [to] meanwhile. No other
     * birth may be built or sent in between; when it resumes, the births taken go up in stamp order
     * in the order they were sent, and the last one declares what is current.
     */
    private fun a2(from: String, to: String, name: String) = runBlocking {
        val brokerRef = AtomicReference<Broker?>()
        mode.set(from)
        val inSnapshot = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val r = reporter(brokerRef) {
            if (store.justStamped.compareAndSet(true, false)) {
                inSnapshot.countDown()
                resume.await(60, TimeUnit.SECONDS)
            }
            SatelliteChain.egressFormat(mode.get())
        }
        val first = SatelliteChain.egressFormat(from)
        val want = SatelliteChain.egressFormat(to)
        try {
            r.start()
            val b = withTimeout(10_000) { while (brokerRef.get() == null) delay(10); brokerRef.get()!! }
            assertTrue("the first birth ($first) was taken", until(10_000) { b.taken.size == 1 })
            assertEquals(first, format(b.taken.first()))
            delay(1_500)

            // A reconnect asks for a birth; it pauses between its stamp and its snapshot.
            store.armPause()
            val savesBefore = store.saves.get()
            b.callback!!.connectComplete(true, "tcp://hub.invalid:1883")
            assertTrue("the reconnect's birth paused after its stamp", inSnapshot.await(10, TimeUnit.SECONDS))
            mode.set(to)
            delay(3_000) // health ticks see the new chain while that birth is paused
            assertEquals("no birth was sent while one was being built", 1, b.attempts.get())
            assertEquals("no other stamp was taken meanwhile", savesBefore + 1, store.saves.get())
            resume.countDown()
            assertTrue("the current declaration went out", until(30_000) { b.taken.size >= 2 && format(b.taken.last()) == want })
            delay(2_500)
            assertEquals(1, b.maxInFlight.get())
            assertOrdered(b.taken)
            assertEquals(want, format(b.taken.last()))
            writeOut(name, b.taken)
        } finally {
            resume.countDown()
            r.stop()
        }
    }

    @Test
    fun `a stamp always labels the snapshot built with it`() =
        a2(SatelliteChain.MODE_PLAIN, SatelliteChain.MODE_SEALED, "a2-snapshot-paused")

    /** A2 the other way round: sealed first, the chain turns plain while the birth is paused. */
    @Test
    fun `a stamp labels its snapshot when the chain turns plain`() =
        a2(SatelliteChain.MODE_SEALED, SatelliteChain.MODE_PLAIN, "a2-inverse-snapshot-paused")
}
