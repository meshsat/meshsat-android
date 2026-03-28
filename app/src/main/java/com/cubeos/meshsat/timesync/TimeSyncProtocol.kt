package com.cubeos.meshsat.timesync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Time synchronization protocol — wire-compatible with the Go bridge.
 *
 * TimeSyncReq  (0x14): [1B type=0x14][16B sender_hash][8B unix_nanos LE][1B stratum]  = 26 bytes
 * TimeSyncResp (0x15): [1B type=0x15][16B responder_hash][8B unix_nanos LE][1B stratum][8B echo_ts LE] = 34 bytes
 *
 * Four-timestamp exchange:
 *   T1 = client sends request (unix_nanos in request)
 *   T2 = server receives request (server's clock)
 *   T3 = server sends response (unix_nanos in response)
 *   T4 = client receives response (client's clock)
 *   Offset = ((T2 - T1) + (T3 - T4)) / 2
 */
object TimeSyncProtocol {
    const val TYPE_REQUEST: Byte = 0x14
    const val TYPE_RESPONSE: Byte = 0x15
    const val REQ_SIZE = 26
    const val RESP_SIZE = 34

    data class TimeSyncRequest(
        val senderHash: ByteArray,  // 16 bytes
        val unixNanos: Long,        // sender's time in nanoseconds
        val stratum: Int,
    ) {
        fun marshal(): ByteArray {
            val buf = ByteBuffer.allocate(REQ_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(TYPE_REQUEST)
            buf.put(senderHash, 0, 16)
            buf.putLong(unixNanos)
            buf.put(stratum.toByte())
            return buf.array()
        }

        companion object {
            fun unmarshal(data: ByteArray): TimeSyncRequest? {
                if (data.size < REQ_SIZE || data[0] != TYPE_REQUEST) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                buf.get() // skip type
                val hash = ByteArray(16); buf.get(hash)
                return TimeSyncRequest(hash, buf.long, buf.get().toInt() and 0xFF)
            }
        }

        override fun equals(other: Any?): Boolean = other is TimeSyncRequest && senderHash.contentEquals(other.senderHash)
        override fun hashCode(): Int = senderHash.contentHashCode()
    }

    data class TimeSyncResponse(
        val responderHash: ByteArray, // 16 bytes
        val unixNanos: Long,          // responder's time in nanoseconds
        val stratum: Int,
        val echoTs: Long,             // T1 from request (echoed back)
    ) {
        fun marshal(): ByteArray {
            val buf = ByteBuffer.allocate(RESP_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(TYPE_RESPONSE)
            buf.put(responderHash, 0, 16)
            buf.putLong(unixNanos)
            buf.put(stratum.toByte())
            buf.putLong(echoTs)
            return buf.array()
        }

        companion object {
            fun unmarshal(data: ByteArray): TimeSyncResponse? {
                if (data.size < RESP_SIZE || data[0] != TYPE_RESPONSE) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                buf.get() // skip type
                val hash = ByteArray(16); buf.get(hash)
                return TimeSyncResponse(hash, buf.long, buf.get().toInt() and 0xFF, buf.long)
            }
        }

        override fun equals(other: Any?): Boolean = other is TimeSyncResponse && responderHash.contentEquals(other.responderHash)
        override fun hashCode(): Int = responderHash.contentHashCode()
    }

    /** Create a request with this node's hash and current time. */
    fun createRequest(localHash: ByteArray): TimeSyncRequest {
        return TimeSyncRequest(localHash, MeshClock.now() * 1_000_000, MeshClock.stratum)
    }

    /** Create a response to a request. */
    fun createResponse(localHash: ByteArray, request: TimeSyncRequest): TimeSyncResponse {
        return TimeSyncResponse(localHash, MeshClock.now() * 1_000_000, MeshClock.stratum, request.unixNanos)
    }

    /** Process a received response and update MeshClock. Returns offset in ms. */
    fun processResponse(response: TimeSyncResponse, peerId: String): Long {
        val t1 = response.echoTs / 1_000_000   // our original request time (ms)
        val t3 = response.unixNanos / 1_000_000 // server's response time (ms)
        val t4 = MeshClock.now()                 // our time now (ms)
        // Approximate: T2 ≈ T3 (server processes instantly)
        val offset = ((t3 - t1) + (t3 - t4)) / 2
        MeshClock.updateFromPeer(peerId, offset, response.stratum)
        return offset
    }
}
