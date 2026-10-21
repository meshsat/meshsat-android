package net.meshsat.android.hubmsg

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.meshsat.android.FakeJsprModem
import net.meshsat.android.bt.Iridium9704Spp
import net.meshsat.android.crypto.ProvisionImporter
import net.meshsat.android.engine.SatelliteChain
import net.meshsat.android.hub.BirthStampStore
import net.meshsat.android.hub.BirthStamper
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.hub.HubReporterConfig
import net.meshsat.android.service.GatewayService
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's Hub birth declaration against the real Hub (R32-R1, MESHSAT-1623): the app's own
 * HubReporter over mTLS, its 9704 driver on a scripted JSPR modem (a test IMEI), a stamp store
 * that fails on cue and survives a "restart", and a clock an hour behind. Step by step:
 *
 *  1. no modem yet; 2. the modem's IMEI arrives late, nothing declared; 3. sealed;
 *  4. the chain setting unreadable, so nothing declared (the Hub must keep sealed); 5. sealed;
 *  6. a restart with the stamp floor unreadable and the clock behind: nothing goes out;
 *  7. the floor readable: the held birth goes out by itself, later than every earlier one;
 *  8. stamps cannot be stored while the chain turns plain: nothing goes out;
 *  9. stores work: plain goes out; 10. sealed; 11. a reconnect with the clock still behind.
 *
 * After each step it writes `<n>-<name>.done` (the births published so far) into
 * MESHSAT_R32_STEPS and waits for `<n>.go`, so the Hub's database can be read in between. The
 * births, as published, go to `births.jsonl` there for the Hub's fixture test. Skipped unless
 * MESHSAT_R32_LIVE=1; MESHSAT_R32_BUNDLE is a provisioning bundle (mode 0600, never printed).
 */
class HubBirthDeclarationLiveTest {

    private fun env(name: String): String =
        System.getenv(name)?.trim().orEmpty().also { assumeTrue("$name not set", it.isNotBlank()) }

    /** The stamp floor as the app's settings keep it, across "restarts", failing on cue. */
    private class Store : BirthStampStore {
        @Volatile var failRead = false
        @Volatile var failSave = false
        @Volatile var stored: String? = null
        override fun lastBirthStamp(): Instant? {
            if (failRead) throw java.io.IOException("read refused (test)")
            return stored?.let { BirthStamper.parse(it) }
        }
        override fun saveBirthStamp(stamp: Instant) {
            if (failSave) throw java.io.IOException("save refused (test)")
            stored = BirthStamper.format(stamp)
        }
    }

    /** One published birth as the Hub reads it. */
    private data class Birth(val raw: String) {
        private val j = JSONObject(raw)
        val stamp: Instant = Instant.parse(j.getString("timestamp"))
        private val imt = j.getJSONArray("interfaces").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .firstOrNull { it.optString("type") == "iridium_imt" }
        val imei: String = imt?.optString("imei").orEmpty()
        val format: String = imt?.optString("egress_format").orEmpty()
        fun line() = "stamp=${BirthStamper.format(stamp)} imei=${if (imei.isEmpty()) "-" else "..." + imei.takeLast(4)} egress_format=${format.ifEmpty { "-" }}"
    }

    /** The service's 9704 the reporter reads; its setter is private to the service, as it should be. */
    private fun setModem(spp: Iridium9704Spp?) {
        GatewayService::class.java.getDeclaredField("iridium9704Spp").apply { isAccessible = true }.set(null, spp)
    }

    @Test
    fun `the Hub keeps what the phone declares, in stamp order, through every fault`() = runBlocking {
        assumeTrue("MESHSAT_R32_LIVE not set", System.getenv("MESHSAT_R32_LIVE") == "1")
        val bundle = ProvisionImporter.parseBundle(File(env("MESHSAT_R32_BUNDLE")).readText())
        val steps = File(env("MESHSAT_R32_STEPS")).apply { mkdirs() }
        val imei = "300000000000001"
        jvmBuildModel()

        val births = CopyOnWriteArrayList<Birth>()
        val store = Store()
        val mode = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val clockOffsetMs = java.util.concurrent.atomic.AtomicLong(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val spp = Iridium9704Spp(object : ContextWrapper(null) {})
        setModem(spp)

        fun newReporter() = HubReporter(
            object : ContextWrapper(null) {}, scope,
            HubReporterConfig(
                hubUrl = bundle.mqttUrl, bridgeId = bundle.bridgeId, callsign = "r32-live", username = bundle.username,
                password = bundle.password, healthIntervalSec = 2, clientCertPem = bundle.clientCertPem,
                clientKeyPem = bundle.clientKeyPem, caCertPem = bundle.caCertPem, topicPrefix = bundle.topicPrefix,
            ),
            birthStamps = store,
            clock = { Instant.now().plusMillis(clockOffsetMs.get()) },
        ) { uri, id ->
            object : MqttClient(uri, id, MemoryPersistence()) {
                override fun publish(topic: String, message: MqttMessage) {
                    super.publish(topic, message)
                    if (topic.endsWith("/birth")) births += Birth(String(message.payload, Charsets.UTF_8))
                }
            }
        }.also { it.imtEgressFormat = { SatelliteChain.egressFormat(mode.get()) } }

        suspend fun until(ms: Long, ok: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                if (ok()) return true
                delay(100)
            }
            return ok()
        }

        var step = 0
        suspend fun checkpoint(name: String) {
            step++
            File(steps, "$step-$name.done").writeText(births.joinToString("\n") { it.line() } + "\n")
            println("step $step $name: ${births.size} births; last ${births.lastOrNull()?.line()}")
            assertTrue("no go for step $step", until(15 * 60_000L) { File(steps, "$step.go").exists() })
        }
        suspend fun nextBirth(after: Int, what: String): Birth {
            assertTrue("no birth for $what", until(60_000) { births.size > after })
            return births[after]
        }
        suspend fun noBirthFor(ms: Long, after: Int, what: String) {
            delay(ms)
            assertEquals("a birth went out while $what", after, births.size)
        }

        var r = newReporter()
        try {
            r.start()
            var b = nextBirth(0, "the connect")
            assertEquals("", b.imei)
            checkpoint("no-modem")

            spp.attach(FakeJsprModem(imei))
            assertTrue("the 9704 did not come up", until(30_000) { spp.state.value == Iridium9704Spp.State.Ready })
            var n = births.size
            b = nextBirth(n, "the late IMEI")
            assertEquals(imei, b.imei); assertEquals("", b.format)
            checkpoint("late-imei-undeclared")

            n = births.size; mode.set(SatelliteChain.MODE_SEALED)
            b = nextBirth(n, "sealed"); assertEquals("sealed", b.format)
            checkpoint("sealed")

            n = births.size; mode.set(null)
            b = nextBirth(n, "the setting unreadable"); assertEquals(imei, b.imei); assertEquals("", b.format)
            checkpoint("setting-unreadable")

            n = births.size; mode.set(SatelliteChain.MODE_SEALED)
            b = nextBirth(n, "sealed again"); assertEquals("sealed", b.format)
            checkpoint("sealed-again")

            r.stop()
            store.failRead = true
            clockOffsetMs.set(-3_600_000L)
            n = births.size
            r = newReporter(); r.start()
            noBirthFor(25_000, n, "the floor cannot be read")
            checkpoint("restart-floor-unreadable")

            store.failRead = false
            b = nextBirth(n, "the floor read again")
            assertTrue(b.stamp.isAfter(births[n - 1].stamp)); assertEquals("sealed", b.format)
            checkpoint("floor-readable")

            store.failSave = true
            n = births.size; mode.set(SatelliteChain.MODE_PLAIN)
            noBirthFor(25_000, n, "stamps cannot be stored")
            checkpoint("save-refused-plain-held")

            store.failSave = false
            b = nextBirth(n, "stores working"); assertEquals("plain", b.format)
            checkpoint("plain")

            n = births.size; mode.set(SatelliteChain.MODE_SEALED)
            b = nextBirth(n, "sealed once more"); assertEquals("sealed", b.format)
            checkpoint("sealed-final")

            r.stop()
            n = births.size
            r = newReporter(); r.start()
            b = nextBirth(n, "the reconnect"); assertEquals("sealed", b.format)
            checkpoint("reconnect")

            // Every birth that went out is later than the one before; the floor is the last.
            births.zipWithNext().forEach { (a, c) -> assertTrue("${c.line()} after ${a.line()}", c.stamp.isAfter(a.stamp)) }
            assertEquals(BirthStamper.format(births.last().stamp), store.stored)
            File(steps, "births.jsonl").writeText(births.joinToString("\n") { it.raw } + "\n")
        } finally {
            r.stop()
            spp.detach()
            setModem(null)
            scope.cancel()
        }
    }
}
