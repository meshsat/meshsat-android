package net.meshsat.android.hubmsg

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.meshsat.android.crypto.ProvisionImporter
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.hub.HubReporterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's Hub app route against the real Hub (MESHSAT-1584): two phone identities of one
 * tenant, each the app's own HubReporter (mTLS over wss, the msg subscriptions, the PUBACK wait)
 * and HubMsgRoute, over a store that outlives a restart as the app's database does.
 *
 *  1. a text and its reply, both read delivered;
 *  2. an unknown recipient reads refused;
 *  3. the recipient off the Hub, then the sender off the Hub while the completion is published:
 *     the Hub's redelivery reaches the recipient once, and the sender's next connect asks again
 *     and reads delivered (the session is clean, so the walk is what recovers it);
 *  4. optionally a kit with its route on (MESHSAT_HUBMSG_KIT): delivered to the kit's Bridge.
 *
 * Skipped unless MESHSAT_HUBMSG_LIVE=1. MESHSAT_HUBMSG_A and MESHSAT_HUBMSG_B are paths to two
 * provisioning bundles (the claim's JSON, mode 0600); nothing of them is printed. Run on the SDK VM:
 *   MESHSAT_HUBMSG_LIVE=1 MESHSAT_HUBMSG_A=a.json MESHSAT_HUBMSG_B=b.json \
 *   ./gradlew testFdroidDebugUnitTest --tests 'net.meshsat.android.hubmsg.HubMsgLiveTest'
 */
class HubMsgLiveTest {

    private fun env(name: String): String =
        System.getenv(name)?.trim().orEmpty().also { assumeTrue("$name not set", it.isNotBlank()) }

    /** One phone: a provisioned identity, its reporter and route; [store] survives [stop]. */
    private class End(path: String, private val scope: CoroutineScope) {
        private val bundle = ProvisionImporter.parseBundle(File(path).readText())
        val principal = HubMsgWire.principal(bundle.bridgeId)
        val store = MemHubMsgStore()
        val stored = CopyOnWriteArrayList<String>()
        @Volatile var reporter: HubReporter? = null
        @Volatile var route: HubMsgRoute? = null

        fun start() {
            val r = HubReporter(object : ContextWrapper(null) {}, scope, HubReporterConfig(
                hubUrl = bundle.mqttUrl, bridgeId = bundle.bridgeId, callsign = "hubmsg-live", username = bundle.username,
                password = bundle.password, healthIntervalSec = 300, clientCertPem = bundle.clientCertPem,
                clientKeyPem = bundle.clientKeyPem, caCertPem = bundle.caCertPem, topicPrefix = bundle.topicPrefix,
            ))
            val rt = HubMsgRoute(
                store = store,
                pub = object : HubMsgRoute.Publisher {
                    override val principal: String get() = r.principal
                    override fun isConnected() = r.isConnected
                    override fun publishMsg(kind: String, payload: String) = r.publishMsg(kind, payload)
                },
                onStored = { from, text, _ -> stored += "$from: $text" },
            )
            r.onMsgIn = { p -> scope.launch { rt.handleIn(p) } }
            r.onMsgStatus = { p -> scope.launch { rt.handleStatus(p) } }
            r.onConnected = {
                rt.onConnected()
                scope.launch { runCatching { rt.pass() } }
            }
            reporter = r
            route = rt
            r.start()
        }

        fun stop() {
            reporter?.stop()
            reporter = null
            route = null
        }

        suspend fun send(to: String, text: String): Long =
            (route!!.send(to, text) as HubMsgRoute.SendResult.Queued).messageId

        fun status(id: Long) = store.row(id)?.forwardedTo.orEmpty()
    }

    private suspend fun until(what: String, ms: Long, ok: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!ok()) {
            assertTrue("timed out after ${ms / 1000} s: $what", System.currentTimeMillis() - t0 < ms)
            delay(250)
        }
        println("hubmsg live: $what after ${System.currentTimeMillis() - t0} ms")
    }

    @Test
    fun `phone to phone through the real Hub, offline both ways`() = runBlocking {
        assumeTrue("MESHSAT_HUBMSG_LIVE is not 1", System.getenv("MESHSAT_HUBMSG_LIVE") == "1")
        jvmBuildModel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = End(env("MESHSAT_HUBMSG_A"), scope)
        val b = End(env("MESHSAT_HUBMSG_B"), scope)
        // The service's pass, every 2 s here instead of 30 s.
        scope.launch { while (isActive) { delay(2_000); runCatching { a.route?.pass() }; runCatching { b.route?.pass() } } }
        try {
            a.start(); b.start()
            until("both on the Hub", 60_000) { a.reporter?.isConnected == true && b.reporter?.isConnected == true }

            // 1. A text and its reply.
            val run = UUID.randomUUID().toString().take(8)
            val t1 = "hub_msg live $run one"
            val id1 = a.send(b.principal, t1)
            until("one delivered", 60_000) { a.status(id1) == HubMsgStatus.DELIVERED }
            until("one stored at b", 10_000) { b.stored.contains("${a.principal}: $t1") }
            val t2 = "hub_msg live $run reply"
            val id2 = b.send(a.principal, t2)
            until("reply delivered", 60_000) { b.status(id2) == HubMsgStatus.DELIVERED }
            assertTrue(a.stored.contains("${b.principal}: $t2"))

            // 2. An unknown recipient.
            val id3 = a.send("bridge:hubmsg-live-nobody-$run", "hub_msg live $run nobody")
            until("nobody refused", 60_000) { a.status(id3).let { it.startsWith(HubMsgStatus.REFUSED_PREFIX) || it.startsWith(HubMsgStatus.FAILED_PREFIX) } }
            assertTrue(a.status(id3), a.status(id3).endsWith("refused_unknown_recipient"))

            // 3. The recipient off the Hub; then the sender off it while the completion goes out.
            b.stop()
            delay(3_000)
            val t4 = "hub_msg live $run offline"
            val id4 = a.send(b.principal, t4)
            until("offline one forwarded", 60_000) { a.status(id4) == HubMsgStatus.FORWARDED }
            a.stop()
            b.start()
            until("offline one stored at b", 240_000) { b.stored.contains("${a.principal}: $t4") }
            delay(10_000) // the completion reaches nobody: a is off the Hub, its session clean
            assertEquals("the completion was not missed", HubMsgStatus.FORWARDED, a.status(id4))
            a.start() // a fresh route over the same store, as after a restart
            until("offline one delivered after a's connect", 90_000) { a.status(id4) == HubMsgStatus.DELIVERED }
            assertEquals("stored once", 1, b.stored.count { it == "${a.principal}: $t4" })
            assertEquals(1, b.store.rows().count { it.direction == "rx" && it.text == t4 })

            // 4. A kit with its route on, when named: a text to it, and (MESHSAT_HUBMSG_KIT_TEXT) the
            // text it sent to a before this run, which the Hub delivers again until a has it.
            val kit = System.getenv("MESHSAT_HUBMSG_KIT")?.trim().orEmpty()
            if (kit.isNotBlank()) {
                val id5 = a.send(HubMsgWire.principal(kit), "hub_msg live $run to $kit")
                until("kit delivered", 120_000) { a.status(id5) == HubMsgStatus.DELIVERED }
                val fromKit = System.getenv("MESHSAT_HUBMSG_KIT_TEXT")?.trim().orEmpty()
                if (fromKit.isNotBlank()) {
                    until("the kit's text stored at a", 360_000) { a.stored.contains("${HubMsgWire.principal(kit)}: $fromKit") }
                    delay(5_000) // a copy the Hub sends before it has the answer is answered, not stored
                    assertEquals(1, a.store.rows().count { it.direction == "rx" && it.text == fromKit })
                }
            }
            println("hubmsg live: run $run passed, a=${a.principal} b=${b.principal}")
        } finally {
            a.stop(); b.stop()
            delay(1_000)
            scope.cancel()
        }
    }

    /**
     * A far end for a phone running the app (MESHSAT_HUBMSG_ECHO = seconds): end B answers every
     * text it stores with "echo: <text>" through the Hub, until the time is up.
     */
    @Test
    fun `an echo end for a phone running the app`() = runBlocking {
        val seconds = System.getenv("MESHSAT_HUBMSG_ECHO")?.trim()?.toLongOrNull() ?: 0L
        assumeTrue("MESHSAT_HUBMSG_ECHO not set", System.getenv("MESHSAT_HUBMSG_LIVE") == "1" && seconds > 0)
        jvmBuildModel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val b = End(env("MESHSAT_HUBMSG_B"), scope)
        scope.launch { while (isActive) { delay(2_000); runCatching { b.route?.pass() } } }
        try {
            b.start()
            until("b on the Hub", 60_000) { b.reporter?.isConnected == true }
            var answered = 0
            val end = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < end) {
                while (answered < b.stored.size) {
                    val (from, text) = b.stored[answered++].split(": ", limit = 2)
                    val id = b.send(from, "echo: $text")
                    println("hubmsg live: echo from $from, row $id")
                }
                delay(500)
            }
            b.store.rows().filter { it.direction == "tx" }.forEach { println("hubmsg live: echo row to ${it.recipient}: ${it.forwardedTo}") }
        } finally {
            b.stop()
            delay(1_000)
            scope.cancel()
        }
    }

    /** The JVM's android.jar has no device model; the birth needs one. */
    private fun jvmBuildModel() {
        val f = android.os.Build::class.java.getDeclaredField("MODEL")
        if (runCatching { f.get(null) }.getOrNull() != null) return
        val u = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = u.javaClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(u, f)
        val off = u.javaClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(u, f) as Long
        u.javaClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java).invoke(u, base, off, "hubmsg-live")
    }
}
