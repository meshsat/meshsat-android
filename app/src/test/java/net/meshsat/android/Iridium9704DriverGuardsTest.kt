package net.meshsat.android

import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.bt.Iridium9704Spp
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The 9704 driver against a scripted modem (MESHSAT-1630): the firmware version from the field
 * the modem names for it, and malformed segment fields logged and ignored instead of throwing in
 * the handler, with the driver still taking the next good message.
 */
class Iridium9704DriverGuardsTest {

    private lateinit var modem: FakeJsprModem
    private lateinit var spp: Iridium9704Spp

    @Before
    fun up() {
        modem = FakeJsprModem("300000000000001", segment = 100)
        spp = Iridium9704Spp(object : ContextWrapper(null) {})
        spp.attach(modem)
        runBlocking { withTimeout(30_000) { spp.state.first { it == Iridium9704Spp.State.Ready } } }
    }

    @After
    fun down() = spp.detach()

    @Test
    fun `the firmware version comes from GET firmware, the hardware version from hwInfo`() {
        assertEquals("v5.2.1", spp.modemInfo.value.firmwareVersion)
        assertEquals("test", spp.modemInfo.value.hardwareVersion)
    }

    @Test
    fun `a firmware answer without a version gives no version, never the hardware one`() {
        assertEquals("", Iridium9704Spp.firmwareVersion(null))
        assertEquals("", Iridium9704Spp.firmwareVersion(JSONObject()))
        assertEquals("", Iridium9704Spp.firmwareVersion(JSONObject("""{"version": {"major": 1, "minor": 2}}""")))
        assertEquals("v1.2.3", Iridium9704Spp.firmwareVersion(JSONObject("""{"version": {"major": 1, "minor": 2, "patch": 3}}""")))
    }

    private fun takesTheNextGoodMt() {
        val payload = "after the bad lines".toByteArray()
        val got = runBlocking {
            val subscribed = CompletableDeferred<Unit>()
            val first = async { withTimeout(30_000) { spp.receivedMessages.onSubscription { subscribed.complete(Unit) }.first() } }
            subscribed.await() // the flow keeps nothing for late collectors
            modem.deliverMt(payload, id = 42)
            first.await()
        }
        assertArrayEquals(payload, got)
    }

    private fun b64(n: Int) = Iridium9704Spp.jsprSegmentData(ByteArray(n) { 0x41 })

    @Test
    fun `a negative MT length is refused, not allocated`() {
        modem.push("""299 messageTerminate {"topic_id": 244, "message_id": 5, "message_length_max": -5}""")
        modem.push("""299 messageTerminateSegment {"topic_id": 244, "message_id": 5, "segment_length": 4, "segment_start": 0, "data": "${b64(4)}"}""")
        modem.push("""299 messageTerminateStatus {"topic_id": 244, "message_id": 5, "final_mt_status": "complete"}""")
        takesTheNextGoodMt()
    }

    @Test
    fun `a huge MT length is not allocated up front`() {
        modem.push("""299 messageTerminate {"topic_id": 244, "message_id": 6, "message_length_max": ${Int.MAX_VALUE}}""")
        modem.push("""299 messageTerminateStatus {"topic_id": 244, "message_id": 6, "final_mt_status": "complete"}""")
        takesTheNextGoodMt()
    }

    @Test
    fun `a negative MT segment start is ignored`() {
        modem.push("""299 messageTerminate {"topic_id": 244, "message_id": 7, "message_length_max": 10}""")
        modem.push("""299 messageTerminateSegment {"topic_id": 244, "message_id": 7, "segment_length": 4, "segment_start": -3, "data": "${b64(4)}"}""")
        modem.push("""299 messageTerminateStatus {"topic_id": 244, "message_id": 7, "final_mt_status": "complete"}""")
        takesTheNextGoodMt()
    }

    @Test
    fun `a negative MO segment request is ignored and the real segments still send the message`() {
        modem.badSegmentFirst = true
        val wire = ByteArray(250) { it.toByte() }
        assertEquals("mo_ack_received", runBlocking { spp.sendMessageBlocking(wire) })
        val mo = modem.mos.take()
        assertArrayEquals(wire, mo.copyOfRange(0, mo.size - 2))
    }
}
