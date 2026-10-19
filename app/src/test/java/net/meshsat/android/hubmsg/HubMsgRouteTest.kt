package net.meshsat.android.hubmsg

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Hub app route against a Hub that follows the contract (ContractHub), with a second
 * route as the kit at the far end: the delivery status up to "delivered to the MeshSat app", the
 * queue and the repeats, the Hub's refusal, copies and reordering, what the Hub said while the
 * phone was off the Hub, the recovery of an event the phone could not keep (A11-A15, also across a
 * restart and after a weaker outcome), the bound, the walk, and the receiving side (C3).
 * [MESHSAT-1584]
 */
class HubMsgRouteTest {

    private class Pair(lifetimeOffset: Long = 0) {
        var t = 1_792_400_000_000L + lifetimeOffset
        val hub = ContractHub()
        val storeA = MemHubMsgStore()
        val storeB = MemHubMsgStore()
        val linkA = hub.link("bridge:phone-a")
        val linkB = hub.link("bridge:kit-b")
        val storedAtB = mutableListOf<String>()
        var a = route(storeA, linkA)
        val b = HubMsgRoute(storeB, linkB, { t }) { from, text, _ -> storedAtB += "$from: $text" }.also { hub.routes["bridge:kit-b"] = it }

        fun route(store: MemHubMsgStore, link: ContractHub.Link) = HubMsgRoute(store, link, { t }).also { hub.routes[link.principal] = it }

        /** kit-a's process stopping and starting over the same store: the route's memory is gone. */
        fun restartA() { a = route(storeA, linkA) }

        suspend fun send(text: String = "Water at the north gate"): Long =
            (a.send("bridge:kit-b", text) as HubMsgRoute.SendResult.Queued).messageId

        fun statusA(id: Long) = storeA.row(id)!!.forwardedTo
        fun rxB() = storeB.rows().filter { it.direction == "rx" }
        fun op() = storeA.ops().single()
    }

    @Test
    fun `a text reaches the far app and reads delivered`() = runBlocking {
        val p = Pair()
        val id = p.send()
        assertEquals(HubMsgStatus.ACCEPTED, p.statusA(id))
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
        assertEquals(listOf("bridge:phone-a: Water at the north gate"), p.storedAtB)
        val rx = p.rxB().single()
        assertEquals("bridge:phone-a" to "Water at the north gate", rx.sender to rx.text)
        assertEquals(setOf("forwarded", "completed"), p.storeA.keptEvents(p.op().opId).map { it.event.cls }.toSet())
    }

    @Test
    fun `an address that is not a bridge, this phone, an empty or an oversize text never reach the queue`() = runBlocking {
        val p = Pair()
        for ((to, text) in listOf("kit-b" to "x", "bridge:" to "x", "bridge:phone-a" to "x", "bridge:kit-b" to "",
            "bridge:kit-b" to "x".repeat(HubMsgWire.MAX_BODY + 1))) {
            assertTrue("$to/${text.length}", p.a.send(to, text) is HubMsgRoute.SendResult.Refused)
        }
        assertTrue(p.storeA.rows().isEmpty() && p.hub.queue.isEmpty())
        assertTrue(p.a.send("bridge:kit-b", "x".repeat(HubMsgWire.MAX_BODY)) is HubMsgRoute.SendResult.Queued)
    }

    @Test
    fun `off the Hub a text waits in the queue and goes at the next connect`() = runBlocking {
        val p = Pair()
        p.linkA.up = false
        val id = p.send()
        assertEquals(HubMsgStatus.QUEUED, p.statusA(id))
        assertTrue(p.hub.queue.isEmpty())
        p.linkA.up = true
        p.a.onConnected()
        p.a.pass()
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
        assertEquals(1, p.rxB().size)
    }

    @Test
    fun `a publish whose answer did not come goes again, unchanged, and the message is stored once`() = runBlocking {
        val p = Pair()
        p.linkA.answer = HubMsgRoute.Published.Unknown
        val id = p.send()
        assertEquals(HubMsgStatus.QUEUED, p.statusA(id))
        p.linkA.answer = HubMsgRoute.Published.Ok
        p.a.pass()
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
        assertEquals(1, p.rxB().size)
        assertEquals(1, p.hub.replays) // the second publish was a repeat of the first, answered from what the Hub kept
    }

    @Test
    fun `the Hub's refusal reads not sent, and is kept once`() = runBlocking {
        val p = Pair()
        p.hub.refuse = { "refused_unknown_recipient" }
        val id = p.send()
        p.hub.pump()
        assertEquals(HubMsgStatus.REFUSED_PREFIX + "refused_unknown_recipient", p.statusA(id))
        p.hub.statuses.toList().forEach { p.hub.replay(it) }
        assertEquals(1, p.storeA.keptEvents(p.op().opId).size)
        assertEquals(0, p.rxB().size)
    }

    @Test
    fun `copies and reordering change nothing`() = runBlocking {
        val p = Pair()
        val id = p.send()
        p.hub.pump()
        p.hub.statuses.toList().reversed().forEach { p.hub.replay(it); p.hub.replay(it) }
        p.hub.deliveries.toList().forEach { p.hub.replay(it) }
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
        assertEquals(1, p.rxB().size)
        assertEquals(listOf("bridge:phone-a: Water at the north gate"), p.storedAtB)
    }

    @Test
    fun `what the Hub said while the phone was off the Hub is asked for at the next connect`() = runBlocking {
        val p = Pair()
        val id = p.send()
        // The submission goes, kit-b stores it and answers, but the phone is off the Hub by then.
        p.hub.pumpOne() // out: delivered to kit-b (its answer queued) and hub_forwarded to the phone
        p.linkA.up = false
        p.hub.pump() // the answer: recipient_app_stored, published while the phone is away
        assertEquals(HubMsgStatus.FORWARDED, p.statusA(id))
        p.linkA.up = true
        p.a.onConnected()
        p.a.pass()
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
        assertEquals(1, p.hub.replays)
        p.a.pass() // the walk is done for this connect, and nothing is owed
        p.hub.pump()
        assertEquals(1, p.hub.replays)
    }

    @Test
    fun `an event the phone could not keep is asked for again, and kept once`() = runBlocking {
        val p = Pair()
        p.a.pass() // the connect's walk, over nothing yet
        p.storeA.failEvent = { it.kind == "recipient_app_stored" }
        val id = p.send()
        p.hub.pump()
        assertEquals(HubMsgStatus.FORWARDED, p.statusA(id))
        val op = p.op()
        assertTrue("owed", op.replayAt != 0L)
        assertEquals(1, p.storeA.missing(op.opId).size)
        p.storeA.failEvent = { false }
        p.a.pass()
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
        assertEquals(0L, p.op().replayAt)
        assertTrue(p.storeA.missing(op.opId).isEmpty())
        assertEquals(1, p.rxB().size)
    }

    @Test
    fun `both writes failing, then a restart, still recovers it`() = runBlocking {
        val p = Pair()
        p.storeA.failEvent = { it.kind == "recipient_app_stored" }
        p.storeA.fail = { it == "missing" }
        val id = p.send()
        p.hub.pump()
        assertEquals(0L, p.op().replayAt)
        p.storeA.failEvent = { false }
        p.storeA.fail = { false }
        p.restartA()
        p.a.onConnected()
        p.a.pass()
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
    }

    @Test
    fun `both writes failing in a running process that already walked is recovered from memory`() = runBlocking {
        val p = Pair()
        p.a.pass()
        p.storeA.failEvent = { it.kind == "recipient_app_stored" }
        p.storeA.fail = { it == "missing" }
        val id = p.send()
        p.hub.pump()
        p.a.pass() // the send's own walk: nothing reconciled yet that was lost
        p.storeA.failEvent = { false }
        p.storeA.fail = { false }
        p.hub.pump()
        p.a.pass()
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
    }

    @Test
    fun `a completion lost after an expiry is still asked for`() = runBlocking {
        val p = Pair()
        p.a.pass()
        p.linkB.up = false // kit-b is away: the delivery is lost there
        val id = p.send()
        p.hub.pump()
        p.hub.emit("hub_expired", "expired", "hub", "expired")
        assertEquals(HubMsgStatus.EXPIRED, p.statusA(id))
        p.storeA.failEvent = { it.kind == "recipient_app_stored" }
        p.hub.emit("recipient_app_stored", "completed", "recipient") // a late stored answer the Hub records
        assertEquals(HubMsgStatus.EXPIRED, p.statusA(id))
        p.storeA.failEvent = { false }
        p.hub.replay(p.hub.statuses.first { JSONObject(it.payload).optString("kind") == "hub_expired" }) // an old copy ends nothing
        assertTrue(p.op().replayAt != 0L)
        p.a.pass()
        p.hub.pump()
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
    }

    @Test
    fun `at the bound the phone stops asking for what is really missing, and only that`() = runBlocking {
        val p = Pair()
        p.a.pass()
        p.linkB.up = false
        p.storeA.failEvent = { it.kind == "recipient_app_stored" }
        val missingOne = p.send("a completion lost")
        p.hub.pump()
        p.hub.emit("recipient_app_stored", "completed", "recipient")
        p.storeA.failEvent = { false }
        val second = (p.a.send("bridge:kit-b", "an expiry kept") as HubMsgRoute.SendResult.Queued).messageId
        p.hub.pump()
        val opSecond = p.storeA.ops().single { it.messageId == second }
        p.hub.publish("bridge:phone-a", "status", HubEvent(opId = opSecond.opId, mid = opSecond.mid, recipient = opSecond.recipient,
            eventId = HubMsgIds.newV7(p.t), rev = 2, cls = "expired", kind = "hub_expired", reason = "expired", authority = "hub").toJson())
        assertEquals(HubMsgStatus.EXPIRED, p.statusA(second))
        p.linkA.up = false
        p.t = p.op2(missingOne).expiresAt + HubMsgRoute.REPLAY_WITHIN_MS + 1
        p.a.pass()
        p.a.pass()
        assertEquals(HubMsgStatus.UNKNOWN, p.statusA(missingOne))
        assertEquals(HubMsgStatus.EXPIRED, p.statusA(second))
        assertTrue(p.op2(missingOne).replayGivenUpAt != 0L && p.op2(missingOne).replayAt != 0L) // given up, not recovered
        assertEquals(0L, p.op2(second).replayGivenUpAt)
    }

    private fun Pair.op2(messageId: Long) = storeA.ops().single { it.messageId == messageId }

    @Test
    fun `a connect walks every operation without a completion, a batch a pass, once`() = runBlocking {
        val p = Pair()
        p.a.pass()
        p.linkB.up = false
        repeat(250) { p.a.send("bridge:kit-b", "text $it") }
        p.hub.pump()
        val before = p.hub.replays
        p.a.onConnected()
        repeat(3) { p.a.pass(); p.hub.pump() }
        assertEquals(250, p.hub.replays - before)
        repeat(3) { p.a.pass(); p.hub.pump() }
        assertEquals(250, p.hub.replays - before) // quiet: not asked again until the next connect
        p.a.onConnected()
        repeat(3) { p.a.pass(); p.hub.pump() }
        assertEquals(500, p.hub.replays - before)
    }

    @Test
    fun `a delivery is stored with its chat row once and answered every time`() = runBlocking {
        val p = Pair()
        p.send()
        p.hub.pump()
        val d = p.hub.deliveries.single()
        p.hub.replay(d)
        p.hub.replay(d)
        assertEquals(1, p.rxB().size)
        assertEquals(2, p.hub.queue.count { it.kind == "ack" && it.principal == "bridge:kit-b" }) // the first was pumped already
        p.hub.pump()
        assertEquals(listOf("bridge:phone-a: Water at the north gate"), p.storedAtB)
    }

    @Test
    fun `a delivery to another principal, or that does not parse, is ignored`() = runBlocking {
        val p = Pair()
        p.send()
        p.hub.pump()
        val d = HubDelivery.fromJson(p.hub.deliveries.single().payload)!!
        // A whole, well-formed delivery of its own operation, only addressed to someone else.
        val other = HubSubmission.create(HubMsgIds.newV7(p.t), HubMsgIds.newV7(p.t), "bridge:someone-else", "not for kit-b",
            d.midExpiresAt, d.expiresAt)
        p.b.handleIn(HubDelivery(1, other.opId, other.mid, "bridge:phone-a", other.to, other.contentType, other.midExpiresAt,
            other.expiresAt, other.contentSha256, other.body).toJson())
        p.b.handleIn(d.copy(from = "kit-a").toJson())
        p.b.handleIn("not json")
        assertEquals(1, p.rxB().size)
        assertEquals(listOf("bridge:phone-a: Water at the north gate"), p.storedAtB)
        assertTrue("an answer to what was not ours", p.hub.queue.none { it.kind == "ack" })
    }

    @Test
    fun `a completion ends an obligation held only in memory, with no more repeats`() = runBlocking {
        val p = Pair()
        p.a.pass() // the start's walk is done
        p.storeA.failEvent = { it.kind == "hub_forwarded" }
        val id = p.send()
        p.hub.pump() // hub_forwarded not kept, then the completion kept
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
        // A late copy of hub_forwarded whose event and obligation both cannot be written: an
        // obligation in memory, behind a completion that is already kept.
        p.storeA.fail = { it == "missing" }
        p.hub.replay(p.hub.statuses.first { JSONObject(it.payload).optString("kind") == "hub_forwarded" })
        p.storeA.failEvent = { false }
        p.storeA.fail = { false }
        val before = p.hub.replays
        repeat(3) { p.t += 31 * 60_000L; p.a.pass(); p.hub.pump() }
        assertEquals("a repeat for an event a completion made moot", before, p.hub.replays)
        assertEquals(HubMsgStatus.DELIVERED, p.statusA(id))
    }

    @Test
    fun `a completion that comes after the bound still reads delivered`() = runBlocking {
        val p = Pair()
        p.a.pass()
        p.linkB.up = false
        p.storeA.failEvent = { it.kind == "recipient_app_stored" }
        val id = p.send()
        p.hub.pump()
        p.hub.emit("recipient_app_stored", "completed", "recipient") // lost on the phone
        p.storeA.failEvent = { false }
        p.linkA.up = false
        p.t = p.op().expiresAt + HubMsgRoute.REPLAY_WITHIN_MS + 1
        p.a.pass()
        assertEquals(HubMsgStatus.UNKNOWN, p.statusA(id))
        p.linkA.up = true
        p.hub.replay(p.hub.statuses.first { JSONObject(it.payload).optString("kind") == "recipient_app_stored" })
        assertEquals("the recipient's own word beats the phone giving up", HubMsgStatus.DELIVERED, p.statusA(id))
    }
}
