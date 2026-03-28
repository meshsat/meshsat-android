package com.cubeos.meshsat.rlnc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RLNC coded packet wire format.
 *
 *   [0:2]   generation_id (uint16 BE) — groups packets for same resource
 *   [2]     segment_count K (uint8)
 *   [3:3+K] coefficients (K bytes, one per original segment)
 *   [3+K:]  coded_data (segment_size bytes)
 */
data class RlncCodedPacket(
    val generationId: Int,
    val segmentCount: Int,
    val coefficients: ByteArray,
    val codedData: ByteArray,
) {
    companion object {
        const val HEADER_SIZE = 3 // generationId(2) + segmentCount(1)

        fun unmarshal(data: ByteArray): RlncCodedPacket? {
            if (data.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val genId = buf.short.toInt() and 0xFFFF
            val k = buf.get().toInt() and 0xFF
            if (data.size < HEADER_SIZE + k) return null
            val coeffs = ByteArray(k)
            buf.get(coeffs)
            val codedData = ByteArray(data.size - HEADER_SIZE - k)
            buf.get(codedData)
            return RlncCodedPacket(genId, k, coeffs, codedData)
        }
    }

    fun marshal(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + coefficients.size + codedData.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putShort(generationId.toShort())
        buf.put(segmentCount.toByte())
        buf.put(coefficients)
        buf.put(codedData)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RlncCodedPacket) return false
        return generationId == other.generationId && segmentCount == other.segmentCount &&
            coefficients.contentEquals(other.coefficients) && codedData.contentEquals(other.codedData)
    }

    override fun hashCode(): Int = generationId * 31 + coefficients.contentHashCode()
}
