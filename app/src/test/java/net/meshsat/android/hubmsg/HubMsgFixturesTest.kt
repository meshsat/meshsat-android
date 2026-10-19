package net.meshsat.android.hubmsg

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared vectors of HeMB-NG stage 2 (fixture release fixtures-v1.1, test resources
 * hemb-ng/v1, whose integrity HembNgFixturesTest pins), consumed as this app's production code:
 * the ids (C1e), the content digest (C1d), the receipt kinds (C6a), the reducer (C6) in every
 * order with every event twice, and the inbox (C3), all 17 cases. [MESHSAT-1584]
 */
class HubMsgFixturesTest {

    private val dir = File(File(requireNotNull(javaClass.classLoader?.getResource("hemb-ng/fixtures.lock")).toURI()).parentFile, "v1")

    private fun fixture(name: String) = JSONObject(File(dir, name).readText())

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun `the ids are valid as the release says`() {
        val f = fixture("ids.json")
        assertEquals(HubMsgIds.NAMESPACE, f.getString("namespace"))
        val cases = f.getJSONArray("cases").objects()
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val x = c.optJSONObject("context") ?: JSONObject()
            val ctx = HubMsgIds.Context(
                sender = x.optString("sender"), name = x.optString("name"), bridgeId = x.optString("bridge_id"),
                runStartMs = if (x.has("run_start_ms")) x.getLong("run_start_ms") else -1,
            )
            assertEquals(c.getString("case"), c.getBoolean("valid"), HubMsgIds.idValid(c.getString("id"), c.getString("field"), ctx))
        }
    }

    @Test
    fun `a new id is a v7 with its time`() {
        val now = 1792330000123L
        val id = HubMsgIds.newV7(now)
        assertTrue(HubMsgIds.validV7(id))
        val b = HubMsgIds.parseCanonical(id)!!
        var ms = 0L
        for (i in 0 until 6) ms = (ms shl 8) or (b[i].toLong() and 0xFF)
        assertEquals(now, ms)
    }

    @Test
    fun `the content digest is the release's`() {
        val f = fixture("content_digest.json")
        assertEquals(HubMsgDigest.TAG, f.getString("tag"))
        val vectors = f.getJSONArray("vectors").objects()
        assertTrue(vectors.isNotEmpty())
        for (v in vectors) {
            val body = hex(v.getString("body_hex"))
            val pre = HubMsgDigest.preimage(v.getString("mid"), v.getString("recipient"), v.getString("content_type"), v.getLong("mid_expires_at_ms"), body)
            assertEquals(v.getString("case"), v.getString("preimage_hex"), pre.joinToString("") { "%02x".format(it) })
            assertEquals(v.getString("case"), v.getString("content_sha256"),
                HubMsgDigest.sha256Hex(v.getString("mid"), v.getString("recipient"), v.getString("content_type"), v.getLong("mid_expires_at_ms"), body))
        }
    }

    @Test
    fun `the receipt kinds are the release's`() {
        val kinds = fixture("c6a_receipt_kinds.json").getJSONArray("kinds").objects()
            .map { Triple(it.getString("kind"), it.getString("class"), it.getString("authority")) }.toSet()
        assertEquals(kinds, HubMsgReducer.ALLOWED_KINDS)
    }

    @Test
    fun `the reducer is the release's in every order with every event twice`() {
        val f = fixture("c6_reducer.json")
        val op = f.getJSONObject("operation").let { HubMsgReducer.Operation(it.getString("op_id"), it.getString("mid"), it.getString("recipient")) }
        val order = f.getJSONArray("projection_order").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(HubMsgReducer.PROJECTION_ORDER, order)
        val sources = f.getJSONObject("source_by_authority").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        assertEquals(HubMsgReducer.SOURCE_OF, sources)
        val sets = f.getJSONArray("sets").objects()
        assertTrue(sets.size >= 5)
        for (set in sets) {
            val events = set.getJSONArray("events").objects().map { HubEvent.fromJsonObject(it) }
            val e = set.getJSONObject("expect")
            fun strings(name: String) = e.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) } }
            val want = HubMsgReducer.Projection(e.getString("state"), e.getBoolean("conflict"), strings("failed_reasons"), strings("evidence"), strings("rejected"))
            var n = 0
            permute(events) { orderOf ->
                n++
                for (evs in listOf(orderOf, orderOf + orderOf)) {
                    assertEquals("${set.getString("case")}, order $n", want, HubMsgReducer.reduce(op, evs))
                }
            }
        }
    }

    private fun permute(evs: List<HubEvent>, visit: (List<HubEvent>) -> Unit) {
        val a = evs.toMutableList()
        fun rec(k: Int) {
            if (k == a.size) { visit(a.toList()); return }
            for (i in k until a.size) {
                java.util.Collections.swap(a, k, i); rec(k + 1); java.util.Collections.swap(a, k, i)
            }
        }
        rec(0)
    }

    @Test
    fun `the inbox answers every case as the release says`() {
        val f = fixture("inbox_cases.json")
        assertEquals(HubMsgInbox.SKEW_MS, f.getLong("skew_ms"))
        assertEquals(HubMsgInbox.RETENTION_AFTER_EXPIRY_MS, f.getLong("retention_after_expiry_ms"))
        assertEquals(HubMsgInbox.CAPACITY, f.getInt("capacity_target"))
        val cases = f.getJSONArray("cases").objects()
        assertEquals(17, cases.size)
        runInboxCases(cases) { MemInbox() }
    }

    companion object {
        /** Runs the release's inbox cases against a fresh inbox from [open] each (the Room inbox runs them too). */
        fun runInboxCases(cases: List<JSONObject>, open: () -> HubMsgInbox.Tx) {
            for (c in cases) {
                val tx = open()
                val steps = c.getJSONArray("steps")
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    val sub = s.getJSONObject("submission")
                    val bodyHex = sub.getString("body_hex")
                    val body = String(ByteArray(bodyHex.length / 2) { j -> bodyHex.substring(2 * j, 2 * j + 2).toInt(16).toByte() }, Charsets.UTF_8)
                    val d = HubDelivery(1, sub.getString("op_id"), sub.getString("mid"), sub.getString("origin"), sub.getString("recipient"),
                        sub.getString("content_type"), sub.getLong("mid_expires_at"), sub.getLong("expires_at"), sub.getString("content_sha256"), body)
                    val got = HubMsgInbox.commit(tx, d, s.getLong("now"), s.getBoolean("clock_established"), c.getInt("capacity")).copy(messageId = 0)
                    val e = s.getJSONObject("expect")
                    val want = HubMsgInbox.Answer(e.getString("result"), e.optString("reason"), e.optBoolean("stored_now"),
                        e.optBoolean("accepted_within_skew"), e.optBoolean("expiry_unjudged"))
                    assertEquals("${c.getString("case")} step ${i + 1}", want, got)
                }
            }
        }
    }
}

/** An inbox in memory, for the JVM tests. */
class MemInbox : HubMsgInbox.Tx {
    val entries = mutableListOf<HubMsgInbox.Entry>()
    val messages = mutableListOf<String>()

    override fun byOp(origin: String, opId: String) = entries.firstOrNull { it.origin == origin && it.opId == opId }
    override fun byMid(origin: String, mid: String) = entries.filter { it.origin == origin && it.mid == mid }.minByOrNull { it.committedAt }
    override fun count() = entries.size
    override fun pruneBefore(nowMs: Long): Int {
        val gone = entries.filter { HubMsgInbox.retainedUntil(it.expiresAt) < nowMs }
        entries.removeAll(gone)
        return gone.size
    }

    override fun insert(e: HubMsgInbox.Entry, store: Boolean, body: String): HubMsgInbox.Entry {
        var kept = e
        if (store) {
            messages += body
            kept = e.copy(messageId = messages.size.toLong())
        }
        entries += kept
        return kept
    }
}
