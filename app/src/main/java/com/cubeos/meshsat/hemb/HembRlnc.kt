package com.cubeos.meshsat.hemb

import java.security.SecureRandom

/**
 * HeMB RLNC coded symbol — one linear combination of K source segments.
 * Wire-compatible with Go bridge's internal/hemb/rlnc.go CodedSymbol.
 */
data class HembCodedSymbol(
    val genId: Int,          // generation ID
    val symbolIndex: Int,    // position within the generation
    val k: Int,              // number of source segments
    val coefficients: ByteArray, // K GF(256) coefficients
    val data: ByteArray,     // coded payload (linear combination)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HembCodedSymbol) return false
        return genId == other.genId && symbolIndex == other.symbolIndex &&
            k == other.k && coefficients.contentEquals(other.coefficients) &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int = genId * 31 + symbolIndex
}

/**
 * HeMB RLNC encoder — produces N coded symbols from K source segments
 * using random linear combinations over GF(256) with polynomial 0x11B.
 *
 * Wire-compatible with Go bridge's EncodeGeneration().
 */
object HembRlncEncoder {

    private val rng = SecureRandom()

    /**
     * Encode K source segments into N coded symbols.
     * All segments must be equal length (pad last segment if needed).
     *
     * @param genId generation identifier
     * @param segments K source segments (equal length, zero-padded)
     * @param n total coded symbols to produce (N >= K)
     * @return list of N coded symbols
     */
    fun encode(genId: Int, segments: List<ByteArray>, n: Int): List<HembCodedSymbol> {
        val k = segments.size
        require(k in 1..255) { "K must be 1..255, got $k" }
        require(n >= k) { "N ($n) must be >= K ($k)" }
        val symSize = segments[0].size
        require(segments.all { it.size == symSize }) { "All segments must be same size" }

        return (0 until n).map { idx ->
            // Generate random non-all-zero coefficients.
            val coeffs = ByteArray(k)
            do {
                for (i in 0 until k) {
                    coeffs[i] = rng.nextInt(256).toByte()
                }
            } while (coeffs.all { it == 0.toByte() })

            // Compute coded data: sum of coeff[j] * segment[j] over GF(256).
            val coded = ByteArray(symSize)
            for (j in 0 until k) {
                val c = coeffs[j].toInt() and 0xFF
                if (c == 0) continue
                for (b in 0 until symSize) {
                    coded[b] = HembGf256.add(
                        coded[b].toInt() and 0xFF,
                        HembGf256.mul(c, segments[j][b].toInt() and 0xFF)
                    ).toByte()
                }
            }

            HembCodedSymbol(
                genId = genId,
                symbolIndex = idx,
                k = k,
                coefficients = coeffs,
                data = coded,
            )
        }
    }

    /**
     * Segment a payload into K equal-length chunks (last padded with zeros).
     */
    fun segmentPayload(payload: ByteArray, symSize: Int): List<ByteArray> {
        val k = (payload.size + symSize - 1) / symSize
        return (0 until k).map { i ->
            val start = i * symSize
            val end = (start + symSize).coerceAtMost(payload.size)
            val seg = ByteArray(symSize)
            System.arraycopy(payload, start, seg, 0, end - start)
            seg
        }
    }
}

/**
 * HeMB RLNC decoder — progressive Gaussian elimination over GF(256).
 *
 * Feed coded symbols one at a time. When rank reaches K, solve recovers
 * all K source segments. Uses HembGf256 (poly 0x11B) for wire compatibility.
 */
class HembRlncDecoder(
    val k: Int,          // number of source segments
    val symSize: Int,    // bytes per segment
) {
    // Augmented matrix: [coefficients(K) | payload(symSize)]
    private val matrix = Array(k) { IntArray(k + symSize) }
    private val pivotCols = IntArray(k) { -1 }
    private var _rank = 0

    val rank: Int get() = _rank
    val isSolvable: Boolean get() = _rank >= k

    /**
     * Feed a coded symbol. Returns true if it was linearly independent (increased rank).
     */
    fun feed(sym: HembCodedSymbol): Boolean {
        if (_rank >= k) return false
        require(sym.k == k) { "Symbol K=${sym.k} != decoder K=$k" }
        require(sym.data.size == symSize) { "Symbol size ${sym.data.size} != $symSize" }

        // Build augmented row.
        val row = IntArray(k + symSize)
        for (i in 0 until k) {
            row[i] = sym.coefficients[i].toInt() and 0xFF
        }
        for (i in 0 until symSize) {
            row[k + i] = sym.data[i].toInt() and 0xFF
        }

        // Reduce against existing pivot rows.
        for (r in 0 until _rank) {
            val pc = pivotCols[r]
            if (pc < 0) continue
            val factor = row[pc]
            if (factor == 0) continue
            for (j in row.indices) {
                row[j] = HembGf256.add(row[j], HembGf256.mul(factor, matrix[r][j]))
            }
        }

        // Find pivot column.
        var pivotCol = -1
        for (c in 0 until k) {
            if (row[c] != 0) { pivotCol = c; break }
        }
        if (pivotCol < 0) return false // linearly dependent

        // Normalize pivot to 1.
        val pivotInv = HembGf256.inv(row[pivotCol])
        for (j in row.indices) {
            row[j] = HembGf256.mul(row[j], pivotInv)
        }

        System.arraycopy(row, 0, matrix[_rank], 0, row.size)
        pivotCols[_rank] = pivotCol
        _rank++
        return true
    }

    /**
     * Solve via back-substitution. Returns K source segments or null if rank < K.
     */
    fun solve(): List<ByteArray>? {
        if (!isSolvable) return null

        // Gauss-Jordan: reduce upward from each pivot.
        for (r in _rank - 1 downTo 0) {
            val pc = pivotCols[r]
            for (above in 0 until r) {
                val factor = matrix[above][pc]
                if (factor == 0) continue
                for (j in matrix[above].indices) {
                    matrix[above][j] = HembGf256.add(
                        matrix[above][j],
                        HembGf256.mul(factor, matrix[r][j])
                    )
                }
            }
        }

        // Extract segments: row r with pivot on column c recovers segment c.
        val segments = Array(k) { ByteArray(symSize) }
        for (r in 0 until _rank) {
            val col = pivotCols[r]
            for (b in 0 until symSize) {
                segments[col][b] = matrix[r][k + b].toByte()
            }
        }
        return segments.toList()
    }
}

/**
 * Try to decode N symbols into K source segments.
 * Convenience function matching Go's TryDecode().
 */
fun hembTryDecode(symbols: List<HembCodedSymbol>, k: Int): List<ByteArray>? {
    if (symbols.isEmpty() || symbols.size < k) return null
    val symSize = symbols[0].data.size

    val coeffs = HembGfMatrix(symbols.size, k)
    val payloads = symbols.map { it.data.copyOf() }
    for (i in symbols.indices) {
        for (j in 0 until k) {
            coeffs[i, j] = symbols[i].coefficients[j].toInt() and 0xFF
        }
    }

    return hembGaussianEliminate(coeffs, payloads)
}
