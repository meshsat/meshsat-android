package com.cubeos.meshsat.fec

/**
 * Systematic Reed-Solomon encoder/decoder over GF(256).
 *
 * Encoding: K data shards → M parity shards (data preserved verbatim).
 * Decoding: any K of K+M shards → recover all K original data shards.
 * Uses Vandermonde matrix for encoding and matrix inversion for decoding.
 */
object ReedSolomon {

    /**
     * Encode data into K data shards + M parity shards.
     * Input data is split into K equal-length shards (padded if needed).
     *
     * @param data The original payload bytes
     * @param dataShards K — number of data shards
     * @param parityShards M — number of parity shards
     * @return List of K+M shards, each with FecHeader prepended
     */
    fun encode(data: ByteArray, dataShards: Int, parityShards: Int): List<ByteArray> {
        require(dataShards in 1..255) { "dataShards must be 1..255" }
        require(parityShards in 1..255) { "parityShards must be 1..255" }
        require(dataShards + parityShards <= 256) { "total shards must be <= 256" }

        val shardSize = (data.size + dataShards - 1) / dataShards
        val totalShards = dataShards + parityShards

        // Split data into K shards (pad last if needed)
        val shards = Array(totalShards) { ByteArray(shardSize) }
        for (i in 0 until dataShards) {
            val offset = i * shardSize
            val len = minOf(shardSize, data.size - offset)
            if (len > 0) System.arraycopy(data, offset, shards[i], 0, len)
        }

        // Generate parity shards using Vandermonde matrix rows
        for (p in 0 until parityShards) {
            val parityIdx = dataShards + p
            for (bytePos in 0 until shardSize) {
                var val_ = 0
                for (d in 0 until dataShards) {
                    // Vandermonde coefficient: alpha^(parityRow * dataCol)
                    val coeff = GaloisField256.pow(p + 1, d)
                    val_ = GaloisField256.add(val_, GaloisField256.mul(coeff, shards[d][bytePos].toInt() and 0xFF))
                }
                shards[parityIdx][bytePos] = val_.toByte()
            }
        }

        // Prepend FEC header to each shard
        return shards.mapIndexed { idx, shard ->
            val header = FecHeader(
                dataShards = dataShards,
                parityShards = parityShards,
                shardIndex = idx,
            ).marshal()
            header + shard
        }
    }

    /**
     * Decode from any K of K+M shards back to original data.
     *
     * @param shards List of received shards (each with FecHeader)
     * @param originalSize Original data size (for trimming padding)
     * @return Decoded data, or null if insufficient shards
     */
    fun decode(shards: List<ByteArray>, originalSize: Int): ByteArray? {
        if (shards.isEmpty()) return null
        val headers = shards.map { FecHeader.unmarshal(it) ?: return null }
        val k = headers[0].dataShards

        if (shards.size < k) return null // Need at least K shards

        // Take exactly K shards
        val selected = shards.take(k)
        val selectedHeaders = headers.take(k)
        val shardSize = selected[0].size - FecHeader.HEADER_SIZE

        // Extract shard data (without header)
        val shardData = selected.map { it.copyOfRange(FecHeader.HEADER_SIZE, it.size) }
        val indices = selectedHeaders.map { it.shardIndex }

        // If we have all K data shards (indices 0..K-1), just concatenate
        val hasAllData = (0 until k).all { it in indices }
        if (hasAllData) {
            val sorted = indices.zip(shardData).sortedBy { it.first }
            val result = ByteArray(originalSize)
            var offset = 0
            for ((_, data) in sorted) {
                val len = minOf(data.size, originalSize - offset)
                if (len > 0) System.arraycopy(data, 0, result, offset, len)
                offset += data.size
            }
            return result
        }

        // Need matrix inversion — build the encoding matrix rows for selected shards
        // and solve the system
        val matrix = Array(k) { IntArray(k) }
        for (i in 0 until k) {
            val idx = indices[i]
            if (idx < k) {
                // Data shard: identity row
                matrix[i][idx] = 1
            } else {
                // Parity shard: Vandermonde row
                val parityRow = idx - k
                for (j in 0 until k) {
                    matrix[i][j] = GaloisField256.pow(parityRow + 1, j)
                }
            }
        }

        // Invert the matrix using Gauss-Jordan elimination
        val augmented = Array(k) { i -> IntArray(2 * k).also { row ->
            System.arraycopy(matrix[i], 0, row, 0, k)
            row[k + i] = 1 // identity on right side
        }}

        for (col in 0 until k) {
            // Find pivot
            var pivotRow = -1
            for (row in col until k) {
                if (augmented[row][col] != 0) { pivotRow = row; break }
            }
            if (pivotRow < 0) return null // Singular matrix

            // Swap rows
            val tmp = augmented[col]
            augmented[col] = augmented[pivotRow]
            augmented[pivotRow] = tmp

            // Scale pivot row
            val pivotVal = augmented[col][col]
            val pivotInv = GaloisField256.inv(pivotVal)
            for (j in 0 until 2 * k) {
                augmented[col][j] = GaloisField256.mul(augmented[col][j], pivotInv)
            }

            // Eliminate column in other rows
            for (row in 0 until k) {
                if (row == col) continue
                val factor = augmented[row][col]
                if (factor == 0) continue
                for (j in 0 until 2 * k) {
                    augmented[row][j] = GaloisField256.add(
                        augmented[row][j],
                        GaloisField256.mul(factor, augmented[col][j])
                    )
                }
            }
        }

        // Extract inverse matrix (right half of augmented)
        val inverse = Array(k) { i -> IntArray(k) { j -> augmented[i][k + j] } }

        // Multiply inverse by received shard data to recover original
        val recovered = Array(k) { ByteArray(shardSize) }
        for (i in 0 until k) {
            for (bytePos in 0 until shardSize) {
                var val_ = 0
                for (j in 0 until k) {
                    val_ = GaloisField256.add(val_, GaloisField256.mul(
                        inverse[i][j], shardData[j][bytePos].toInt() and 0xFF
                    ))
                }
                recovered[i][bytePos] = val_.toByte()
            }
        }

        // Concatenate recovered data shards
        val result = ByteArray(originalSize)
        var offset = 0
        for (shard in recovered) {
            val len = minOf(shard.size, originalSize - offset)
            if (len > 0) System.arraycopy(shard, 0, result, offset, len)
            offset += shard.size
        }
        return result
    }
}
