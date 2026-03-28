package com.cubeos.meshsat.timesync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NTP-style time synchronization protocol over Reticulum links.
 *
 * Wire format (18 bytes):
 *   [0]      message_type: REQUEST=0x01, RESPONSE=0x02
 *   [1]      stratum: sender's stratum level
 *   [2:10]   timestamp_ms: sender's corrected time (int64 BE)
 *   [10:18]  origin_timestamp_ms: T1 from request (echoed in response)
 *
 * Four-timestamp exchange:
 *   T1 = client sends request (origin_timestamp)
 *   T2 = server receives request (server's MeshClock.now())
 *   T3 = server sends response (server's MeshClock.now())
 *   T4 = client receives response (client's MeshClock.now())
 *
 *   Offset = ((T2 - T1) + (T3 - T4)) / 2
 *   RTT    = (T4 - T1) - (T3 - T2)
 */
object TimeSyncProtocol {
    const val MSG_REQUEST: Byte = 0x01
    const val MSG_RESPONSE: Byte = 0x02
    const val WIRE_SIZE = 18

    data class TimeSyncMessage(
        val type: Byte,
        val stratum: Int,
        val timestampMs: Long,
        val originTimestampMs: Long,
    )

    /** Create a time sync request. */
    fun createRequest(): ByteArray {
        val t1 = MeshClock.now()
        return marshal(TimeSyncMessage(MSG_REQUEST, MeshClock.stratum, t1, t1))
    }

    /** Create a time sync response to a request. */
    fun createResponse(request: TimeSyncMessage): ByteArray {
        val t3 = MeshClock.now()
        return marshal(TimeSyncMessage(MSG_RESPONSE, MeshClock.stratum, t3, request.timestampMs))
    }

    /** Process a received response and update MeshClock. Returns calculated offset in ms. */
    fun processResponse(response: TimeSyncMessage, peerId: String): Long {
        val t1 = response.originTimestampMs    // our original request time
        val t2 = response.timestampMs          // server's time when it got our request (approximation: uses t3)
        val t3 = response.timestampMs          // server's time when it sent response
        val t4 = MeshClock.now()               // our time now

        val offset = ((t2 - t1) + (t3 - t4)) / 2
        MeshClock.updateFromPeer(peerId, offset, response.stratum)
        return offset
    }

    /** Marshal to wire format. */
    fun marshal(msg: TimeSyncMessage): ByteArray {
        val buf = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(msg.type)
        buf.put(msg.stratum.toByte())
        buf.putLong(msg.timestampMs)
        buf.putLong(msg.originTimestampMs)
        return buf.array()
    }

    /** Unmarshal from wire format. Returns null if data is too short or invalid. */
    fun unmarshal(data: ByteArray): TimeSyncMessage? {
        if (data.size < WIRE_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val type = buf.get()
        if (type != MSG_REQUEST && type != MSG_RESPONSE) return null
        val stratum = buf.get().toInt() and 0xFF
        val timestamp = buf.getLong()
        val origin = buf.getLong()
        return TimeSyncMessage(type, stratum, timestamp, origin)
    }
}
