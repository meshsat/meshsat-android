package com.cubeos.meshsat

import com.cubeos.meshsat.timesync.TimeSyncProtocol
import org.junit.Assert.*
import org.junit.Test

class TimeSyncProtocolTest {
    @Test
    fun `request wire format round-trip`() {
        val request = TimeSyncProtocol.createRequest()
        assertEquals(TimeSyncProtocol.WIRE_SIZE, request.size)

        val parsed = TimeSyncProtocol.unmarshal(request)
        assertNotNull(parsed)
        assertEquals(TimeSyncProtocol.MSG_REQUEST, parsed!!.type)
        assertTrue(parsed.timestampMs > 0)
        assertEquals(parsed.timestampMs, parsed.originTimestampMs) // request echoes itself
    }

    @Test
    fun `response echoes origin timestamp`() {
        val reqBytes = TimeSyncProtocol.createRequest()
        val reqMsg = TimeSyncProtocol.unmarshal(reqBytes)!!

        val respBytes = TimeSyncProtocol.createResponse(reqMsg)
        val respMsg = TimeSyncProtocol.unmarshal(respBytes)!!

        assertEquals(TimeSyncProtocol.MSG_RESPONSE, respMsg.type)
        assertEquals(reqMsg.timestampMs, respMsg.originTimestampMs) // T1 echoed
        assertTrue(respMsg.timestampMs >= reqMsg.timestampMs) // T3 >= T1
    }

    @Test
    fun `unmarshal rejects short data`() {
        assertNull(TimeSyncProtocol.unmarshal(ByteArray(5)))
    }

    @Test
    fun `unmarshal rejects invalid type`() {
        val bad = ByteArray(18)
        bad[0] = 0x99.toByte() // invalid type
        assertNull(TimeSyncProtocol.unmarshal(bad))
    }

    @Test
    fun `marshal produces correct size`() {
        val msg = TimeSyncProtocol.TimeSyncMessage(
            TimeSyncProtocol.MSG_REQUEST, 3, 1000L, 1000L
        )
        val bytes = TimeSyncProtocol.marshal(msg)
        assertEquals(18, bytes.size)

        val parsed = TimeSyncProtocol.unmarshal(bytes)!!
        assertEquals(3, parsed.stratum)
        assertEquals(1000L, parsed.timestampMs)
    }
}
