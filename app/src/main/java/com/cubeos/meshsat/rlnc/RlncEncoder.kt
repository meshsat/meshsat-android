package com.cubeos.meshsat.rlnc

import com.cubeos.meshsat.fec.GaloisField256

/**
 * RLNC encoder — produces coded packets from K source segments.
 *
 * Each coded packet is a random linear combination of all K segments
 * over GF(256). Any K linearly independent coded packets suffice to
 * recover the original K segments via Gaussian elimination.
 */
object RlncEncoder {

    /**
     * Encode K source segments into N coded packets (N >= K).
     *
     * @param segments K original segments (equal length)
     * @param generationId Group identifier for this encoding session
     * @param codedCount N — number of coded packets to produce (default K+1)
     * @return List of N coded packets
     */
    fun encode(
        segments: List<ByteArray>,
        generationId: Int,
        codedCount: Int = segments.size + 1,
    ): List<RlncCodedPacket> {
        require(segments.isNotEmpty()) { "Need at least 1 segment" }
        require(segments.size <= 255) { "Max 255 segments" }
        val k = segments.size
        val segSize = segments[0].size
        require(segments.all { it.size == segSize }) { "All segments must be same size" }

        return (0 until codedCount).map { _ ->
            val coeffs = GF256Vector.randomCoefficients(k)
            val coded = ByteArray(segSize)
            for (j in 0 until k) {
                val c = coeffs[j].toInt() and 0xFF
                for (b in 0 until segSize) {
                    coded[b] = GaloisField256.add(
                        coded[b].toInt() and 0xFF,
                        GaloisField256.mul(c, segments[j][b].toInt() and 0xFF)
                    ).toByte()
                }
            }
            RlncCodedPacket(generationId, k, coeffs, coded)
        }
    }

    /**
     * Split a payload into K equal-length segments (padding last if needed).
     */
    fun segmentPayload(data: ByteArray, k: Int): List<ByteArray> {
        val segSize = (data.size + k - 1) / k
        return (0 until k).map { i ->
            val offset = i * segSize
            val seg = ByteArray(segSize)
            val len = minOf(segSize, data.size - offset)
            if (len > 0) System.arraycopy(data, offset, seg, 0, len)
            seg
        }
    }
}
