package net.meshsat.android

import net.meshsat.android.hubmsg.HubMsgIds
import net.meshsat.android.sos.SosMessages
import net.meshsat.android.sos.SosProgress
import net.meshsat.android.sos.SosRouteStatus
import net.meshsat.android.sos.SosRun
import net.meshsat.android.sos.SosV2
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * An SOS under its own identity (HeMB-NG stage 2, C10, MESHSAT-1584) against the shared cases,
 * the fixture release fixtures-v1.1 (sos.json), which the Bridge's, the Hub's and iOS's tests read too.
 */
class SosV2Test {

    private val fixture: JSONObject by lazy {
        val f = File(requireNotNull(javaClass.classLoader?.getResource("hemb-ng/v1/sos.json")).toURI())
        JSONObject(f.readText())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The fixture's frames carry 52.16, 4.49 and an empty device id; the times are in their bytes. */
    private val fix = SosMessages.Fix(52.16, 4.49, null, 0L)

    @Test
    fun `the C10 frames are the fixture's bytes`() {
        val frames = fixture.getJSONArray("frames")
        var n = 0
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            val d = f.optJSONObject("decoded") ?: continue
            val want = bytes(f.getString("hex"))
            val got = when (f.getString("type")) {
                "0x07" -> {
                    val sec = ByteBuffer.wrap(want, want.size - 28, 4).int.toLong()
                    SosMessages.satFrameStart(d.getString("bridge_id"), d.getString("device_id"), fix, d.getString("text"), sec,
                        d.getString("sos_id"), d.getLong("run_start_ms"))
                }
                "0x08" -> {
                    val sec = ByteBuffer.wrap(want, want.size - 5, 4).int.toLong() and 0xFFFFFFFFL
                    SosMessages.cancelFrame(d.getString("bridge_id"), d.getString("sos_id"), d.getLong("run_start_ms"), sec, d.getInt("reason").toByte())
                }
                else -> error("type ${f.getString("type")}")
            }
            assertEquals(f.getString("case"), hex(want), hex(got))
            n++
        }
        assertTrue("only $n C10 frames", n >= 6)
    }

    @Test
    fun `the 0x07 is the 0x02 body and the run's identity, and the 0x02 is unchanged`() {
        val old = bytes(fixture.getJSONArray("frames").getJSONObject(0).getString("hex"))
        val sec = ByteBuffer.wrap(old, old.size - 4, 4).int.toLong()
        assertEquals(hex(old), hex(SosMessages.satFrame("kit-a", "", fix, "Injured, need help", sec)))
        val legacy = fixture.getJSONObject("legacy_sos_id")
        val start = SosMessages.satFrameStart("kit-a", "", fix, "Injured, need help", sec, legacy.getString("sos_id"), legacy.getLong("run_start_ms"))
        assertEquals(SosMessages.TYPE_SOS_START, start[3])
        assertEquals(hex(old.copyOfRange(4, old.size)), hex(start.copyOfRange(4, old.size)))
        assertEquals(old.size + 24, start.size)
    }

    @Test
    fun `a long bridge id goes whole in the C10 frames, as the Hub matches it`() {
        val id = HubMsgIds.newSosId(1792330000123)
        val bridge = "a-very-long-bridge-id-here"
        val start = SosMessages.satFrameStart(bridge, "300434067943980", null, "SOS", 1792330000, id, 1792330000123)
        assertEquals(bridge.length, start[4].toInt())
        val cancel = SosMessages.cancelFrame(bridge, id, 1792330000123, 1792330600)
        assertEquals(bridge, String(cancel, 5, cancel[4].toInt(), Charsets.UTF_8))
    }

    @Test
    fun `the sos_id rule is the fixture's`() {
        val frames = fixture.getJSONArray("frames")
        var n = 0
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            val d = f.optJSONObject("decoded") ?: continue
            if (!f.has("valid")) continue
            assertEquals(f.getString("case"), f.getBoolean("valid"),
                HubMsgIds.sosIdValid(d.getString("sos_id"), d.getString("bridge_id"), d.getLong("run_start_ms")))
            n++
        }
        assertTrue(n >= 7)
        val l = fixture.getJSONObject("legacy_sos_id")
        assertEquals(l.getString("sos_id"), HubMsgIds.legacySosId(l.getString("bridge_id"), l.getLong("run_start_ms")))
        val id = HubMsgIds.newSosId(1792330000123)
        assertTrue(HubMsgIds.validV7(id) && HubMsgIds.sosIdValid(id, "kit-a", 1792330000123))
        assertFalse(HubMsgIds.sosIdValid(id, "kit-a", 1792330000124))
        assertNotEquals(id, HubMsgIds.newSosId(1792330000123))
        // Any other v7 field is not tied to a start.
        assertTrue(HubMsgIds.idValid(id, "mid"))
    }

    private fun run(sosId: String, start: Long = 1792330000123, cancelledAt: Long? = null) = SosRun(
        id = start, test = false, trigger = "button", name = "Kyriakos", fix = fix, routes = emptyList(), skipped = emptyList(),
        hubWanted = true, deviceId = "", hubAlertId = "x", sosId = sosId, cancelledAt = cancelledAt,
    )

    @Test
    fun `the events read as the fixture's, run_start_ms always there`() {
        val mqtt = fixture.getJSONArray("mqtt")
        var n = 0
        for (i in 0 until mqtt.length()) {
            val c = mqtt.getJSONObject(i)
            val want = c.getJSONObject("json")
            if (!c.getBoolean("valid")) {
                assertFalse("the invalid case changed", want.has("run_start_ms"))
                continue
            }
            val r = run(want.getString("sos_id"), want.getLong("run_start_ms"), cancelledAt = 1792330600000)
            val got = if (want.getString("kind") == "start") SosV2.startEvent(r, want.getString("text")) else SosV2.cancelEvent(r)
            for (k in want.keys()) assertEquals("${c.getString("case")}: $k", want.get(k).toString(), got.get(k).toString())
            for (k in got.keys()) assertTrue("${c.getString("case")}: an extra $k", want.has(k) || k == "timestamp" || k == "device_id")
            n++
        }
        assertEquals(2, n)
        assertEquals(1792330600L, SosV2.cancelEvent(run("x", cancelledAt = 1792330600000)).getLong("timestamp"))
    }

    @Test
    fun `hub caps and acks`() {
        assertTrue(SosV2.capsSayV2("""{"v":1,"caps":["sos_v2"]}"""))
        assertTrue(SosV2.capsSayV2("""{"v":1,"caps":["other","sos_v2"]}"""))
        for (no in listOf("", """{"v":1,"caps":[]}""", """{"v":2,"caps":["sos_v2"]}""", "not json")) assertFalse(no, SosV2.capsSayV2(no))
        val a = SosV2.parseAck("""{"v":2,"sos_id":"01a14f31-66fb-7a7b-bc7d-7e7f80818283","kind":"cancel","state":"cancelled","rev":2}""")
        assertEquals(SosV2.Ack("01a14f31-66fb-7a7b-bc7d-7e7f80818283", "cancel", "cancelled", 2), a)
        for (bad in listOf("""{"v":1,"sos_id":"x","kind":"start"}""", """{"v":2,"kind":"start"}""", """{"v":2,"sos_id":"x","kind":"stop"}""", "[]")) {
            assertNull(bad, SosV2.parseAck(bad))
        }
    }

    @Test
    fun `the repeats back off from 15 s to a minute`() {
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 60_000L), (1..4).map { SosV2.repeatAfter(it) })
    }

    @Test
    fun `the hub leg goes as the v2 event only for a real SOS with its id while the Hub says so`() {
        val id = HubMsgIds.newSosId(1792330000123)
        assertTrue(SosV2.hubLegV2(run(id), capsV2 = true))
        assertFalse(SosV2.hubLegV2(run(id), capsV2 = false))
        assertFalse(SosV2.hubLegV2(run(""), capsV2 = true))
        assertFalse(SosV2.hubLegV2(run(id).copy(test = true), capsV2 = true))
    }

    @Test
    fun `the cancel's 0x08 waits for the Hub's ack while the session is up, and the cancel ends after a day`() {
        val at = 1792330600000L
        val r = run(HubMsgIds.newSosId(1792330000123), cancelledAt = at).copy(satV2 = true, cancelV2Owed = true)
        assertTrue(SosV2.cancelPending(r))
        assertFalse("inside the grace with the session up", SosV2.cancelFrameDue(r, at + 1_000, hubUp = true, satStartWent = true))
        assertTrue("after the grace", SosV2.cancelFrameDue(r, at + SosV2.CANCEL_ACK_GRACE_MS, hubUp = true, satStartWent = true))
        assertTrue("with no session at once", SosV2.cancelFrameDue(r, at, hubUp = false, satStartWent = true))
        assertFalse("its start never went", SosV2.cancelFrameDue(r, at, hubUp = false, satStartWent = false))
        assertFalse("the start was the 0x02", SosV2.cancelFrameDue(r.copy(satV2 = false), at, hubUp = false, satStartWent = true))
        assertFalse("queued already", SosV2.cancelFrameDue(r.copy(cancelFrameDone = true), at, hubUp = false, satStartWent = true))
        assertFalse("the Hub has it", SosV2.cancelPending(r.copy(cancelHubAckedAt = at + 1)))
        assertFalse("given up", SosV2.cancelPending(r.copy(cancelGaveUp = true)))
        assertFalse(SosV2.cancelOver(r, at + SosV2.CANCEL_FOR_MS - 1))
        assertTrue(SosV2.cancelOver(r, at + SosV2.CANCEL_FOR_MS))
    }

    @Test
    fun `a run keeps its C10 state across a restart, and an old run reads with none`() {
        val r = run(HubMsgIds.newSosId(1792330000123), cancelledAt = 1792330600000).copy(
            bridgeId = "kit-a", satV2 = true, hubV2 = true, hubPublishedAt = 1, hubSentAt = 2, hubState = "active",
            cancelV2Owed = true, cancelHubPublishedAt = 3, cancelHubAckedAt = 4, cancelFrameDone = true, cancelGaveUp = true,
        )
        assertEquals(r, SosRun.fromJson(r.toJson()))
        val old = SosRun.fromJson("""{"id":1792330000123,"name":"K","routes":[],"skipped":[],"hub_wanted":true,"device_id":"","hub_alert_id":"x"}""")!!
        assertEquals("", old.sosId)
        assertFalse(old.satV2 || old.hubV2 || old.cancelV2Owed || old.cancelFrameDone)
        assertNull(old.hubPublishedAt)
    }

    @Test
    fun `the Hub route says what is known, with the broker and then the Hub has it`() {
        val id = HubMsgIds.newSosId(1792330000123)
        val published = run(id).copy(hubV2 = true, hubPublishedAt = 1)
        val hub = SosProgress.routes(published, emptyList()).last()
        assertEquals(SosRouteStatus.State.Sending, hub.state)
        val acked = published.copy(hubSentAt = 2)
        assertEquals(SosRouteStatus.State.Sent, SosProgress.routes(acked, emptyList()).last().state)
        assertEquals("The Hub has it", SosProgress.routes(acked, emptyList()).last().detail)
        val cancelling = acked.copy(cancelledAt = 3, cancelV2Owed = true, cancelHubPublishedAt = 4)
        assertEquals(SosRouteStatus.State.Sending, SosProgress.routes(cancelling, emptyList()).last().cancel)
        assertEquals(SosRouteStatus.State.Sent, SosProgress.routes(cancelling.copy(cancelHubAckedAt = 5), emptyList()).last().cancel)
    }
}
