package com.cubeos.meshsat.rlnc

import com.cubeos.meshsat.fec.GaloisField256

/**
 * Progressive RLNC decoder using Gaussian elimination over GF(256).
 *
 * Feed coded packets one at a time. When rank reaches K (the number of
 * original segments), back-substitute to recover all segments.
 *
 * Multi-path: coded packets from different interfaces/paths can all
 * contribute as long as they share the same generationId.
 */
class RlncDecoder(
    val segmentCount: Int,   // K — number of original segments
    val segmentSize: Int,    // bytes per segment
) {
    // Augmented matrix: [coefficients(K) | coded_data(segSize)]
    // Each row is a received (or reduced) coded packet
    private val matrix = Array(segmentCount) { IntArray(segmentCount + segmentSize) }
    private val pivotCols = IntArray(segmentCount) { -1 } // which column each row pivots on
    private var _rank = 0

    /** Current rank (number of linearly independent packets received). */
    val rank: Int get() = _rank

    /** True when K independent packets received — solution is available. */
    val isSolvable: Boolean get() = _rank >= segmentCount

    /**
     * Feed a coded packet. Returns true if it increased rank (was linearly independent).
     */
    fun feed(packet: RlncCodedPacket): Boolean {
        if (_rank >= segmentCount) return false // already solvable
        require(packet.segmentCount == segmentCount) { "Segment count mismatch" }
        require(packet.codedData.size == segmentSize) { "Segment size mismatch" }

        // Build augmented row: [coefficients | coded_data]
        val row = IntArray(segmentCount + segmentSize)
        for (i in 0 until segmentCount) {
            row[i] = packet.coefficients[i].toInt() and 0xFF
        }
        for (i in 0 until segmentSize) {
            row[segmentCount + i] = packet.codedData[i].toInt() and 0xFF
        }

        // Reduce against existing rows (partial elimination)
        for (r in 0 until _rank) {
            val pivotCol = pivotCols[r]
            if (pivotCol < 0) continue
            val factor = row[pivotCol]
            if (factor == 0) continue
            for (j in row.indices) {
                row[j] = GaloisField256.add(row[j], GaloisField256.mul(factor, matrix[r][j]))
            }
        }

        // Find first non-zero coefficient to pivot on
        var pivotCol = -1
        for (c in 0 until segmentCount) {
            if (row[c] != 0) { pivotCol = c; break }
        }

        if (pivotCol < 0) return false // linearly dependent — no new info

        // Normalize pivot to 1
        val pivotInv = GaloisField256.inv(row[pivotCol])
        for (j in row.indices) {
            row[j] = GaloisField256.mul(row[j], pivotInv)
        }

        // Store row at current rank position
        System.arraycopy(row, 0, matrix[_rank], 0, row.size)
        pivotCols[_rank] = pivotCol
        _rank++
        return true
    }

    /**
     * Solve the system via back-substitution and return the K original segments.
     * Returns null if rank < K.
     */
    fun solve(): List<ByteArray>? {
        if (!isSolvable) return null

        // Full Gauss-Jordan elimination (reduce upward from each pivot)
        for (r in _rank - 1 downTo 0) {
            val pivotCol = pivotCols[r]
            for (above in 0 until r) {
                val factor = matrix[above][pivotCol]
                if (factor == 0) continue
                for (j in matrix[above].indices) {
                    matrix[above][j] = GaloisField256.add(
                        matrix[above][j],
                        GaloisField256.mul(factor, matrix[r][j])
                    )
                }
            }
        }

        // Extract segments — each row's data portion is a recovered segment
        // Row r with pivot on column c recovers segment c
        val segments = Array(segmentCount) { ByteArray(segmentSize) }
        for (r in 0 until _rank) {
            val col = pivotCols[r]
            for (b in 0 until segmentSize) {
                segments[col][b] = matrix[r][segmentCount + b].toByte()
            }
        }
        return segments.toList()
    }
}
