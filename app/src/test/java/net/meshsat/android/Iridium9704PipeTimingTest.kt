package net.meshsat.android

import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.bt.Iridium9704Spp
import net.meshsat.android.bt.SignalFreshness
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 9704 driver over a link as slow as the node's pipe: a signal read that gets no answer keeps
 * the last reading, which then ages (MESHSAT-1670), and the modem's MO segment request is answered
 * at once, never behind a signal read or an MT being handled (MESHSAT-1674).
 */
class Iridium9704PipeTimingTest {

    private lateinit var modem: FakeJsprModem
    private lateinit var spp: Iridium9704Spp

    @Before
    fun up() {
        modem = FakeJsprModem("300000000000001")
        spp = Iridium9704Spp(object : ContextWrapper(null) {})
        spp.attach(modem)
        runBlocking { withTimeout(30_000) { spp.state.first { it == Iridium9704Spp.State.Ready } } }
    }

    @After
    fun down() = spp.detach()

    @Test
    fun `an unanswered signal read keeps the last reading, which goes stale after the threshold`() = runBlocking {
        val first = spp.reading.value
        assertNotNull("the init's read is a reading", first)
        assertEquals(3, first!!.bars)

        modem.answerConstellation = false
        assertEquals(3, spp.pollSignal())
        assertEquals("the reading keeps its own time", first, spp.reading.value)
        assertEquals(1, spp.pollStats.value.missed)

        assertEquals(SignalFreshness.Kind.Fresh, SignalFreshness.kind(first, first.atMs + SignalFreshness.STALE_AFTER_MS))
        assertEquals(SignalFreshness.Kind.Stale, SignalFreshness.kind(first, first.atMs + SignalFreshness.STALE_AFTER_MS + 1))
        assertEquals("3/5, last reading 66 s ago", SignalFreshness.describe(first, first.atMs + 66_001))
    }

    @Test
    fun `an answered 0 is a fresh 0`() = runBlocking {
        modem.signalBars = 0
        assertEquals(0, spp.pollSignal())
        val r = spp.reading.value!!
        assertEquals(0, r.bars)
        assertEquals(SignalFreshness.Kind.Fresh, SignalFreshness.kind(r, System.currentTimeMillis()))
        assertEquals("0/5", SignalFreshness.describe(r, System.currentTimeMillis()))
        assertTrue(spp.pollStats.value.answered >= 2)
    }

    @Test
    fun `an unsolicited constellationState without bars is no reading`() = runBlocking {
        val before = spp.reading.value
        modem.push("""299 constellationState {"constellation_visible": false}""")
        Thread.sleep(300)
        assertEquals(before, spp.reading.value)
        modem.push("""299 constellationState {"constellation_visible": true, "signal_bars": 5}""")
        withTimeout(5_000) { spp.reading.first { it?.bars == 5 } }
        assertEquals(5, spp.signal.value)
    }

    @Test
    fun `the segment request is answered at once while a signal read waits and an MT is handled`() = runBlocking {
        modem.segmentDelayMs = 500
        modem.mtBeforeSegment = "an MT landing at the same moment".toByteArray()
        modem.answerConstellation = false
        val payload = "yo from the deck".toByteArray()
        val mt = CompletableDeferred<ByteArray>()
        val subscribed = CompletableDeferred<Unit>()
        val mtJob = async(Dispatchers.IO) {
            withTimeout(30_000) { spp.receivedMessages.onSubscription { subscribed.complete(Unit) }.first() }.also { mt.complete(it) }
        }
        subscribed.await()

        val send = async(Dispatchers.IO) { spp.sendMessageBlocking(payload) }
        withTimeout(10_000) { spp.moStatus.first { it == Iridium9704Spp.MoStatus.Segmenting } }
        // A signal read takes the link and waits its 6 s for an answer that never comes; the
        // segment request arrives while it waits.
        val poll = async(Dispatchers.IO) { spp.pollSignal() }

        assertEquals("mo_ack_received", withTimeout(30_000) { send.await() })
        val answeredInMs = (modem.segmentAnsweredAtNs - modem.segmentAskedAtNs) / 1_000_000
        assertTrue("the segment request was answered in $answeredInMs ms, behind the signal read", answeredInMs in 0..1_000)
        assertEquals("the answer echoes the request's message id", 1, modem.lastSegmentMessageId)
        val got = modem.mos.poll()
        assertNotNull(got)
        assertArrayEquals(payload, got!!.copyOfRange(0, payload.size))
        assertNotNull(spp.segmentTiming.value)

        assertArrayEquals("the MT handled in the same moment still arrives whole", modem.mtBeforeSegment, mtJob.await())
        poll.await()
        Unit
    }

    @Test
    fun `a segment request already waiting when a command starts is answered, not drained away`() = runBlocking {
        val payload = "second".toByteArray()
        modem.segmentDelayMs = 1
        val send = async(Dispatchers.IO) { spp.sendMessageBlocking(payload) }
        // Commands right behind the accept: whichever reader finds the request answers it.
        repeat(5) { spp.getSimStatus() }
        assertEquals("mo_ack_received", withTimeout(30_000) { send.await() })
    }
}
