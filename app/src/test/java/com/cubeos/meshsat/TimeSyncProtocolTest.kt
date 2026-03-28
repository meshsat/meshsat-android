package com.cubeos.meshsat

import com.cubeos.meshsat.timesync.TimeSyncProtocol
import org.junit.Assert.*
import org.junit.Test

class TimeSyncProtocolTest {
    private val testHash = ByteArray(16) { it.toByte() }

    @Test
    fun `request wire format size matches bridge`() {
        val req = TimeSyncProtocol.createRequest(testHash)
        val bytes = req.marshal()
        assertEquals(TimeSyncProtocol.REQ_SIZE, bytes.size)
        assertEquals(0x14.toByte(), bytes[0]) // TYPE_REQUEST
    }

    @Test
    fun `request round-trip`() {
        val req = TimeSyncProtocol.createRequest(testHash)
        val bytes = req.marshal()
        val parsed = TimeSyncProtocol.TimeSyncRequest.unmarshal(bytes)!!
        assertArrayEquals(testHash, parsed.senderHash)
        assertEquals(req.stratum, parsed.stratum)
        assertEquals(req.unixNanos, parsed.unixNanos)
    }

    @Test
    fun `response wire format size matches bridge`() {
        val req = TimeSyncProtocol.createRequest(testHash)
        val respHash = ByteArray(16) { (it + 10).toByte() }
        val resp = TimeSyncProtocol.createResponse(respHash, req)
        val bytes = resp.marshal()
        assertEquals(TimeSyncProtocol.RESP_SIZE, bytes.size)
        assertEquals(0x15.toByte(), bytes[0]) // TYPE_RESPONSE
    }

    @Test
    fun `response echoes request timestamp`() {
        val req = TimeSyncProtocol.createRequest(testHash)
        val respHash = ByteArray(16) { (it + 20).toByte() }
        val resp = TimeSyncProtocol.createResponse(respHash, req)
        assertEquals(req.unixNanos, resp.echoTs)
    }

    @Test
    fun `response round-trip`() {
        val req = TimeSyncProtocol.createRequest(testHash)
        val respHash = ByteArray(16) { (it + 30).toByte() }
        val resp = TimeSyncProtocol.createResponse(respHash, req)
        val bytes = resp.marshal()
        val parsed = TimeSyncProtocol.TimeSyncResponse.unmarshal(bytes)!!
        assertArrayEquals(respHash, parsed.responderHash)
        assertEquals(resp.unixNanos, parsed.unixNanos)
        assertEquals(resp.echoTs, parsed.echoTs)
    }

    @Test
    fun `unmarshal rejects short data`() {
        assertNull(TimeSyncProtocol.TimeSyncRequest.unmarshal(ByteArray(5)))
        assertNull(TimeSyncProtocol.TimeSyncResponse.unmarshal(ByteArray(10)))
    }

    @Test
    fun `unmarshal rejects wrong type`() {
        val bad = ByteArray(26)
        bad[0] = 0x99.toByte()
        assertNull(TimeSyncProtocol.TimeSyncRequest.unmarshal(bad))
    }
}
