package com.cubeos.meshsat.dtn

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * DTN wire formats for custody transfer and bundle fragmentation.
 */
object DtnProtocol {
    const val CUSTODY_OFFER: Byte = 0x10
    const val CUSTODY_ACCEPT: Byte = 0x11
    const val CUSTODY_SIGNAL: Byte = 0x12

    const val SIGNAL_SUCCEEDED: Byte = 0x01
    const val SIGNAL_FAILED: Byte = 0x02
    const val SIGNAL_FORWARDED: Byte = 0x03

    /** Generate a random 16-byte bundle ID. */
    fun generateBundleId(): ByteArray {
        val id = ByteArray(16)
        SecureRandom().nextBytes(id)
        return id
    }

    // ── Custody Offer (25 bytes) ──

    data class CustodyOffer(
        val bundleHash: ByteArray,  // 16 bytes — truncated SHA-256 of bundle payload
        val totalSize: Int,         // uint32
        val fragmentCount: Int,     // uint16
        val ttlSeconds: Int,        // uint16
        val priority: Int,          // uint8
    ) {
        companion object {
            const val SIZE = 25

            fun unmarshal(data: ByteArray): CustodyOffer? {
                if (data.size < SIZE) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                val hash = ByteArray(16); buf.get(hash)
                return CustodyOffer(hash, buf.int, buf.short.toInt() and 0xFFFF, buf.short.toInt() and 0xFFFF, buf.get().toInt() and 0xFF)
            }
        }

        fun marshal(): ByteArray {
            val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
            buf.put(bundleHash, 0, 16)
            buf.putInt(totalSize)
            buf.putShort(fragmentCount.toShort())
            buf.putShort(ttlSeconds.toShort())
            buf.put(priority.toByte())
            return buf.array()
        }

        override fun equals(other: Any?): Boolean = other is CustodyOffer && bundleHash.contentEquals(other.bundleHash)
        override fun hashCode(): Int = bundleHash.contentHashCode()
    }

    // ── Custody Accept (32 bytes) ──

    data class CustodyAccept(
        val bundleHash: ByteArray,   // 16 bytes
        val custodianHash: ByteArray, // 16 bytes — accepting node's dest hash
    ) {
        companion object {
            const val SIZE = 32
            fun unmarshal(data: ByteArray): CustodyAccept? {
                if (data.size < SIZE) return null
                val hash = data.copyOfRange(0, 16)
                val custodian = data.copyOfRange(16, 32)
                return CustodyAccept(hash, custodian)
            }
        }

        fun marshal(): ByteArray = bundleHash + custodianHash

        override fun equals(other: Any?): Boolean = other is CustodyAccept && bundleHash.contentEquals(other.bundleHash)
        override fun hashCode(): Int = bundleHash.contentHashCode()
    }

    // ── Custody Signal (33 bytes) ──

    data class CustodySignal(
        val bundleHash: ByteArray,    // 16 bytes
        val status: Byte,             // succeeded/failed/forwarded
        val custodianHash: ByteArray, // 16 bytes
    ) {
        companion object {
            const val SIZE = 33
            fun unmarshal(data: ByteArray): CustodySignal? {
                if (data.size < SIZE) return null
                return CustodySignal(data.copyOfRange(0, 16), data[16], data.copyOfRange(17, 33))
            }
        }

        fun marshal(): ByteArray = bundleHash + byteArrayOf(status) + custodianHash

        override fun equals(other: Any?): Boolean = other is CustodySignal && bundleHash.contentEquals(other.bundleHash)
        override fun hashCode(): Int = bundleHash.contentHashCode()
    }

    // ── Fragment Header (24 bytes) ──

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
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                val id = ByteArray(16); buf.get(id)
                return FragmentHeader(id, buf.short.toInt() and 0xFFFF, buf.short.toInt() and 0xFFFF, buf.int)
            }
        }

        fun marshal(): ByteArray {
            val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
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
