package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.routing.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Transfer state for a resource.
 */
enum class RnsTransferState {
    ADVERTISING,     // sender advertised, waiting for request
    TRANSFERRING,    // chunks being sent/received
    COMPLETE,        // all chunks received, hash verified
    FAILED,          // transfer failed (timeout, hash mismatch)
}

/**
 * A Reticulum Resource — reliable chunked transfer over an established link.
 *
 * Splits large payloads into MTU-sized chunks with per-chunk acknowledgment.
 * Provides reliable delivery over unreliable transports (e.g., LoRa, Iridium).
 *
 * Protocol:
 *   1. Sender → RESOURCE_ADV (advertisement: hash, total_size, chunk_count)
 *   2. Receiver → RESOURCE_REQ (request: hash, accepted)
 *   3. Sender → RESOURCE (chunk data, one per packet)
 *   4. Receiver → RESOURCE_PRF (proof/ACK: chunk_index, chunk_hash)
 *   5. Repeat 3-4 until all chunks delivered
 *   6. Receiver → RESOURCE_ICL (complete: full hash verification)
 *
 * [MESHSAT-223]
 */
class RnsResource(
    val id: ByteArray,                   // 16 bytes (truncated hash of payload)
    val totalSize: Int,                  // total payload size in bytes
    val chunkSize: Int,                  // bytes per chunk (based on link MTU)
    val chunkCount: Int,                 // total number of chunks
    val payloadHash: ByteArray,          // 32 bytes (SHA-256 of complete payload)
    @Volatile var state: RnsTransferState,
    val isOutbound: Boolean,
    val linkId: ByteArray,               // 16 bytes (which link carries this transfer)
    val createdAt: Long = System.currentTimeMillis(),
) {
    val idHex: String get() = id.toHex()

    /** Chunks received (for inbound) or ACKed (for outbound). */
    private val receivedChunks = ConcurrentHashMap<Int, ByteArray>()

    /** Track which chunks have been acknowledged. */
    private val ackedChunks = ConcurrentHashMap.newKeySet<Int>()

    /** Progress: 0.0 to 1.0. */
    val progress: Float
        get() {
            val count = if (isOutbound) ackedChunks.size else receivedChunks.size
            return if (chunkCount == 0) 1f else count.toFloat() / chunkCount
        }

    /** Whether all chunks have been received/ACKed. */
    val isComplete: Boolean
        get() {
            val count = if (isOutbound) ackedChunks.size else receivedChunks.size
            return count >= chunkCount
        }

    /** Record a received chunk (inbound). */
    fun addChunk(index: Int, data: ByteArray) {
        if (index in 0 until chunkCount) {
            receivedChunks[index] = data
        }
    }

    /** Mark a chunk as acknowledged (outbound). */
    fun ackChunk(index: Int) {
        if (index in 0 until chunkCount) {
            ackedChunks.add(index)
        }
    }

    /** Get indices of chunks not yet ACKed (for retransmission). */
    fun pendingChunks(): List<Int> {
        return (0 until chunkCount).filter { it !in ackedChunks }
    }

    /**
     * Reassemble the complete payload from received chunks.
     * Returns null if any chunk is missing.
     */
    fun reassemble(): ByteArray? {
        if (receivedChunks.size < chunkCount) return null

        val result = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until chunkCount) {
            val chunk = receivedChunks[i] ?: return null
            val copyLen = minOf(chunk.size, totalSize - offset)
            System.arraycopy(chunk, 0, result, offset, copyLen)
            offset += copyLen
        }
        return result
    }

    /**
     * Verify the reassembled payload hash matches the expected hash.
     */
    fun verifyHash(payload: ByteArray): Boolean {
        val computed = MessageDigest.getInstance("SHA-256").digest(payload)
        return computed.contentEquals(payloadHash)
    }

    companion object {
        private const val TAG = "RnsResource"

        /**
         * Create a resource for outbound transfer.
         *
         * @param payload Complete data to send
         * @param chunkSize Max bytes per chunk (link MTU - overhead)
         * @param linkId 16-byte link identifier
         */
        fun forSending(
            payload: ByteArray,
            chunkSize: Int,
            linkId: ByteArray,
        ): Pair<RnsResource, List<ByteArray>> {
            val hash = MessageDigest.getInstance("SHA-256").digest(payload)
            val id = hash.copyOfRange(0, RnsConstants.DEST_HASH_LEN)
            val chunkCount = (payload.size + chunkSize - 1) / chunkSize

            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            for (i in 0 until chunkCount) {
                val end = minOf(offset + chunkSize, payload.size)
                chunks.add(payload.copyOfRange(offset, end))
                offset = end
            }

            val resource = RnsResource(
                id = id,
                totalSize = payload.size,
                chunkSize = chunkSize,
                chunkCount = chunkCount,
                payloadHash = hash,
                state = RnsTransferState.ADVERTISING,
                isOutbound = true,
                linkId = linkId,
            )

            return resource to chunks
        }

        /**
         * Create a resource for inbound transfer (from advertisement).
         */
        fun forReceiving(
            id: ByteArray,
            totalSize: Int,
            chunkSize: Int,
            chunkCount: Int,
            payloadHash: ByteArray,
            linkId: ByteArray,
        ): RnsResource {
            return RnsResource(
                id = id,
                totalSize = totalSize,
                chunkSize = chunkSize,
                chunkCount = chunkCount,
                payloadHash = payloadHash,
                state = RnsTransferState.TRANSFERRING,
                isOutbound = false,
                linkId = linkId,
            )
        }
    }
}

// --- Resource wire format packets ---

/**
 * Resource advertisement: tells receiver what's coming.
 *
 * Wire format (in RnsPacket.data with context=CTX_RESOURCE_ADV):
 *   [0:16]  resource_id (truncated hash of payload)
 *   [16:48] payload_hash (SHA-256 of complete payload)
 *   [48:52] total_size (uint32 big-endian)
 *   [52:54] chunk_size (uint16 big-endian)
 *   [54:56] chunk_count (uint16 big-endian)
 */
object RnsResourceAdv {
    const val SIZE = RnsConstants.DEST_HASH_LEN + RnsConstants.FULL_HASH_LEN + 4 + 2 + 2  // 56

    fun marshal(resource: RnsResource): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(resource.id)
        buf.put(resource.payloadHash)
        buf.putInt(resource.totalSize)
        buf.putShort(resource.chunkSize.toShort())
        buf.putShort(resource.chunkCount.toShort())
        return buf.array()
    }

    data class Parsed(
        val resourceId: ByteArray,
        val payloadHash: ByteArray,
        val totalSize: Int,
        val chunkSize: Int,
        val chunkCount: Int,
    )

    fun unmarshal(data: ByteArray): Parsed? {
        if (data.size < SIZE) return null
        val buf = ByteBuffer.wrap(data)
        buf.order(ByteOrder.BIG_ENDIAN)
        val id = ByteArray(RnsConstants.DEST_HASH_LEN)
        buf.get(id)
        val hash = ByteArray(RnsConstants.FULL_HASH_LEN)
        buf.get(hash)
        val totalSize = buf.int
        val chunkSize = buf.short.toInt() and 0xFFFF
        val chunkCount = buf.short.toInt() and 0xFFFF
        return Parsed(id, hash, totalSize, chunkSize, chunkCount)
    }
}

/**
 * Resource chunk: one segment of the payload.
 *
 * Wire format (in RnsPacket.data with context=CTX_RESOURCE):
 *   [0:16]  resource_id
 *   [16:18] chunk_index (uint16 big-endian)
 *   [18..]  chunk_data (variable, up to chunk_size)
 */
object RnsResourceChunk {
    const val HEADER_SIZE = RnsConstants.DEST_HASH_LEN + 2  // 18

    fun marshal(resourceId: ByteArray, chunkIndex: Int, chunkData: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + chunkData.size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(resourceId)
        buf.putShort(chunkIndex.toShort())
        buf.put(chunkData)
        return buf.array()
    }

    data class Parsed(
        val resourceId: ByteArray,
        val chunkIndex: Int,
        val chunkData: ByteArray,
    )

    fun unmarshal(data: ByteArray): Parsed? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data)
        buf.order(ByteOrder.BIG_ENDIAN)
        val id = ByteArray(RnsConstants.DEST_HASH_LEN)
        buf.get(id)
        val index = buf.short.toInt() and 0xFFFF
        val chunkData = ByteArray(buf.remaining())
        buf.get(chunkData)
        return Parsed(id, index, chunkData)
    }
}

/**
 * Resource proof (chunk ACK).
 *
 * Wire format (in RnsPacket.data with context=CTX_RESOURCE_PRF):
 *   [0:16]  resource_id
 *   [16:18] chunk_index (uint16 big-endian)
 */
object RnsResourceProof {
    const val SIZE = RnsConstants.DEST_HASH_LEN + 2  // 18

    fun marshal(resourceId: ByteArray, chunkIndex: Int): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(resourceId)
        buf.putShort(chunkIndex.toShort())
        return buf.array()
    }

    data class Parsed(
        val resourceId: ByteArray,
        val chunkIndex: Int,
    )

    fun unmarshal(data: ByteArray): Parsed? {
        if (data.size < SIZE) return null
        val buf = ByteBuffer.wrap(data)
        buf.order(ByteOrder.BIG_ENDIAN)
        val id = ByteArray(RnsConstants.DEST_HASH_LEN)
        buf.get(id)
        val index = buf.short.toInt() and 0xFFFF
        return Parsed(id, index)
    }
}

/**
 * Resource complete notification (transfer verified).
 *
 * Wire format (in RnsPacket.data with context=CTX_RESOURCE_ICL):
 *   [0:16]  resource_id
 *   [16]    status (0x01=success, 0x00=failed)
 */
object RnsResourceComplete {
    const val SIZE = RnsConstants.DEST_HASH_LEN + 1  // 17

    fun marshal(resourceId: ByteArray, success: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.put(resourceId)
        buf.put(if (success) 0x01 else 0x00)
        return buf.array()
    }

    data class Parsed(
        val resourceId: ByteArray,
        val success: Boolean,
    )

    fun unmarshal(data: ByteArray): Parsed? {
        if (data.size < SIZE) return null
        val id = data.copyOfRange(0, RnsConstants.DEST_HASH_LEN)
        val success = data[RnsConstants.DEST_HASH_LEN] == 0x01.toByte()
        return Parsed(id, success)
    }
}
