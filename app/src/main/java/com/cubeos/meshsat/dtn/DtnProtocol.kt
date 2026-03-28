package com.cubeos.meshsat.dtn

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * DTN wire formats — byte-compatible with the Go bridge.
 *
 * CustodyOffer (0x16): [1B type=0x16][16B custody_id UUID][16B source_hash][4B delivery_id LE][payload]
 * CustodyACK   (0x17): [1B type=0x17][16B custody_id UUID][16B acceptor_hash][64B Ed25519 signature]
 *
 * Fragment header (prepended to payload): [16B bundle_id][2B index LE][2B count LE][4B total_size LE]
 */
object DtnProtocol {
    const val TYPE_CUSTODY_OFFER: Byte = 0x16
    const val TYPE_CUSTODY_ACK: Byte = 0x17

    /** Generate a random 16-byte UUID for custody/bundle IDs. */
    fun generateId(): ByteArray {
        val id = ByteArray(16)
        SecureRandom().nextBytes(id)
        return id
    }

    // ── CustodyOffer: [0x16][16B custody_id][16B source_hash][4B delivery_id LE][payload] ──

    data class CustodyOffer(
        val custodyId: ByteArray,    // 16 bytes — unique per custody handshake
        val sourceHash: ByteArray,   // 16 bytes — offering node's dest hash
        val deliveryId: Int,         // uint32 LE — delivery ledger ID
        val payload: ByteArray,      // the actual message
    ) {
        companion object {
            const val HEADER_SIZE = 37 // 1 + 16 + 16 + 4

            fun unmarshal(data: ByteArray): CustodyOffer? {
                if (data.size < HEADER_SIZE || data[0] != TYPE_CUSTODY_OFFER) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                buf.get() // skip type
                val custodyId = ByteArray(16); buf.get(custodyId)
                val sourceHash = ByteArray(16); buf.get(sourceHash)
                val deliveryId = buf.int
                val payload = ByteArray(data.size - HEADER_SIZE)
                buf.get(payload)
                return CustodyOffer(custodyId, sourceHash, deliveryId, payload)
            }
        }

        fun marshal(): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(TYPE_CUSTODY_OFFER)
            buf.put(custodyId)
            buf.put(sourceHash)
            buf.putInt(deliveryId)
            buf.put(payload)
            return buf.array()
        }

        override fun equals(other: Any?): Boolean = other is CustodyOffer && custodyId.contentEquals(other.custodyId)
        override fun hashCode(): Int = custodyId.contentHashCode()
    }

    // ── CustodyACK: [0x17][16B custody_id][16B acceptor_hash][64B Ed25519 signature] ──

    data class CustodyAck(
        val custodyId: ByteArray,     // 16 bytes — matches the offer
        val acceptorHash: ByteArray,  // 16 bytes — accepting node's dest hash
        val signature: ByteArray,     // 64 bytes — Ed25519 sig over (custodyId || acceptorHash)
    ) {
        companion object {
            const val SIZE = 97 // 1 + 16 + 16 + 64

            fun unmarshal(data: ByteArray): CustodyAck? {
                if (data.size < SIZE || data[0] != TYPE_CUSTODY_ACK) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                buf.get() // skip type
                val custodyId = ByteArray(16); buf.get(custodyId)
                val acceptorHash = ByteArray(16); buf.get(acceptorHash)
                val signature = ByteArray(64); buf.get(signature)
                return CustodyAck(custodyId, acceptorHash, signature)
            }
        }

        fun marshal(): ByteArray {
            val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(TYPE_CUSTODY_ACK)
            buf.put(custodyId)
            buf.put(acceptorHash)
            buf.put(signature)
            return buf.array()
        }

        override fun equals(other: Any?): Boolean = other is CustodyAck && custodyId.contentEquals(other.custodyId)
        override fun hashCode(): Int = custodyId.contentHashCode()
    }

    // ── Fragment Header: [16B bundle_id][2B index LE][2B count LE][4B total_size LE] ──

    data class FragmentHeader(
        val bundleId: ByteArray,   // 16 bytes — unique per original message
        val fragmentIndex: Int,    // uint16
        val fragmentCount: Int,    // uint16
        val totalSize: Int,        // uint32
    ) {
        companion object {
            const val SIZE = 24
            fun unmarshal(data: ByteArray): FragmentHeader? {
                if (data.size < SIZE) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val id = ByteArray(16); buf.get(id)
                return FragmentHeader(id, buf.short.toInt() and 0xFFFF, buf.short.toInt() and 0xFFFF, buf.int)
            }
        }

        fun marshal(): ByteArray {
            val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(bundleId, 0, 16)
            buf.putShort(fragmentIndex.toShort())
            buf.putShort(fragmentCount.toShort())
            buf.putInt(totalSize)
            return buf.array()
        }

        override fun equals(other: Any?): Boolean = other is FragmentHeader && bundleId.contentEquals(other.bundleId) && fragmentIndex == other.fragmentIndex
        override fun hashCode(): Int = bundleId.contentHashCode() * 31 + fragmentIndex
    }
}
