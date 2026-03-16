package com.cubeos.meshsat.engine

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Single message queued for burst transmission.
 * Port of meshsat/internal/engine.BurstMessage.
 */
data class BurstMessage(
    val payload: ByteArray,
    val priority: Int = 0,
    val queuedAt: Long = System.currentTimeMillis(),
    val interfaceId: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BurstMessage) return false
        return payload.contentEquals(other.payload) && priority == other.priority &&
                queuedAt == other.queuedAt && interfaceId == other.interfaceId
    }

    override fun hashCode(): Int = payload.contentHashCode()
}

/**
 * TLV-framed burst queue for satellite pass transmission.
 * Queues messages and packs them into MTU-sized payloads for efficient satellite use.
 * Port of meshsat/internal/engine/burst.go.
 *
 * Wire format:
 *   [1B type=0x42] [2B count uint16 LE]
 *   For each message: [2B payload_len uint16 LE] [payload bytes]
 */
class BurstQueue(
    val maxSize: Int,
    val maxAge: Duration,
) {
    private val lock = ReentrantLock()
    private val pending = mutableListOf<BurstMessage>()

    /**
     * Add a message to the burst queue.
     */
    fun enqueue(msg: BurstMessage) {
        require(msg.payload.isNotEmpty()) { "empty payload" }
        require(msg.payload.size <= IRIDIUM_MTU - BURST_HEADER_LEN - BURST_MSG_HEADER_LEN) {
            "payload too large: ${msg.payload.size} bytes (max ${IRIDIUM_MTU - BURST_HEADER_LEN - BURST_MSG_HEADER_LEN})"
        }

        lock.withLock {
            pending.add(msg)
            Log.d(TAG, "burst: message enqueued (pending=${pending.size}, priority=${msg.priority})")
        }
    }

    /**
     * TLV-pack all pending messages into one SBD-sized payload.
     * Returns (payload, count) or (null, 0) if queue is empty.
     * Highest-priority messages are packed first; overflow stays queued.
     */
    fun flush(): Pair<ByteArray?, Int> = lock.withLock {
        if (pending.isEmpty()) return null to 0

        // Sort by priority descending (highest first)
        pending.sortByDescending { it.priority }

        val (payload, count) = packBurst(pending, IRIDIUM_MTU)

        // Remove packed messages from pending
        if (count >= pending.size) {
            pending.clear()
        } else {
            repeat(count) { pending.removeAt(0) }
        }

        Log.i(TAG, "burst: flushed (packed=$count, remaining=${pending.size}, bytes=${payload.size})")
        payload to count
    }

    /** Number of queued messages. */
    fun pending(): Int = lock.withLock { pending.size }

    /** True if maxAge exceeded or maxSize reached. */
    fun shouldFlush(): Boolean = lock.withLock {
        if (pending.isEmpty()) return false
        if (pending.size >= maxSize) return true
        val oldest = pending.minOf { it.queuedAt }
        (System.currentTimeMillis() - oldest) >= maxAge.inWholeMilliseconds
    }

    companion object {
        private const val TAG = "BurstQueue"

        /** TLV type marker for burst frames. */
        const val BURST_TYPE_BYTE: Byte = 0x42

        /** Maximum SBD payload size for Iridium. */
        const val IRIDIUM_MTU = 340

        /** Burst header: 1 byte type + 2 bytes count. */
        const val BURST_HEADER_LEN = 3

        /** Per-message header: 2 bytes payload length. */
        const val BURST_MSG_HEADER_LEN = 2

        /**
         * Pack messages into a TLV-framed burst payload fitting within MTU.
         * Returns (payload, count of messages packed).
         */
        fun packBurst(msgs: List<BurstMessage>, mtu: Int): Pair<ByteArray, Int> {
            val buf = ByteBuffer.allocate(mtu).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(BURST_TYPE_BYTE)
            buf.putShort(0) // placeholder for count

            var count = 0
            for (msg in msgs) {
                val needed = BURST_MSG_HEADER_LEN + msg.payload.size
                if (buf.position() + needed > mtu) break

                buf.putShort(msg.payload.size.toShort())
                buf.put(msg.payload)
                count++
            }

            // Write actual count
            buf.putShort(1, count.toShort())

            val result = ByteArray(buf.position())
            buf.flip()
            buf.get(result)
            return result to count
        }

        /**
         * Decode a TLV-framed burst payload into individual message payloads.
         */
        fun unpackBurst(data: ByteArray): List<ByteArray> {
            require(data.size >= BURST_HEADER_LEN) { "burst data too short: ${data.size} bytes" }
            require(data[0] == BURST_TYPE_BYTE) {
                "invalid burst type byte: 0x${(data[0].toInt() and 0xFF).toString(16)} (expected 0x42)"
            }

            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(1)
            val count = bb.getShort().toInt() and 0xFFFF

            val payloads = mutableListOf<ByteArray>()
            for (i in 0 until count) {
                require(bb.remaining() >= BURST_MSG_HEADER_LEN) {
                    "truncated burst at message $i: need header at offset ${bb.position()}"
                }
                val payloadLen = bb.getShort().toInt() and 0xFFFF
                require(bb.remaining() >= payloadLen) {
                    "truncated burst at message $i: need $payloadLen bytes at offset ${bb.position()}"
                }
                val payload = ByteArray(payloadLen)
                bb.get(payload)
                payloads.add(payload)
            }

            return payloads
        }
    }
}
