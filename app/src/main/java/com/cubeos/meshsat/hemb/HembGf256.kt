package com.cubeos.meshsat.hemb

/**
 * GF(256) arithmetic using irreducible polynomial x^8+x^4+x^3+x+1 (0x11B)
 * and primitive generator element 0x03 (x+1, order 255).
 *
 * Wire-compatible with Go bridge's internal/hemb/gf256.go.
 *
 * NOTE: This is intentionally separate from fec/GaloisField256 which uses
 * polynomial 0x11D and generator 0x02. Using the wrong tables would produce
 * symbols that cannot be decoded across bridge/Android boundary.
 */
object HembGf256 {

    /** Anti-log table (doubled to 512 entries for wrap-around without modular reduction). */
    val EXP = IntArray(512)

    /** Log table. */
    val LOG = IntArray(256)

    init {
        // Build exp/log tables using generator 0x03 (x+1).
        // Generator 0x02 only has order 51 under polynomial 0x11B — NOT primitive.
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            val hi = if (x shl 1 >= 256) (x shl 1) xor 0x11B else x shl 1
            x = hi xor x // multiply by generator 0x03 = (x+1)
        }
        // Duplicate exp[0..254] into exp[255..509] so gfMul can index
        // log[a]+log[b] (range 0..508) without modular reduction.
        for (i in 0 until 255) {
            EXP[i + 255] = EXP[i]
        }
    }

    /** Addition in GF(256) = XOR. */
    fun add(a: Int, b: Int): Int = a xor b

    /** Multiplication in GF(256) via exp/log table lookup. */
    fun mul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return EXP[LOG[a] + LOG[b]]
    }

    /** Multiplicative inverse of a in GF(256). Throws if a == 0. */
    fun inv(a: Int): Int {
        require(a != 0) { "HembGf256: zero has no inverse in GF(256)" }
        return EXP[255 - LOG[a]]
    }
}

/**
 * Row-major matrix over GF(256) for Gaussian elimination.
 */
class HembGfMatrix(val rows: Int, val cols: Int) {
    val data = IntArray(rows * cols)

    operator fun get(row: Int, col: Int): Int = data[row * cols + col]
    operator fun set(row: Int, col: Int, value: Int) { data[row * cols + col] = value }

    /** Deep copy. */
    fun copy(): HembGfMatrix {
        val m = HembGfMatrix(rows, cols)
        System.arraycopy(data, 0, m.data, 0, data.size)
        return m
    }
}

/**
 * Gaussian elimination over GF(256) with partial pivoting.
 *
 * Solves coeffs * X = payloads where coeffs is N×K (N >= K).
 * Returns K decoded payload byte arrays, or null if rank < K.
 */
fun hembGaussianEliminate(
    coeffs: HembGfMatrix,
    payloads: List<ByteArray>,
): List<ByteArray>? {
    val n = coeffs.rows
    val k = coeffs.cols
    if (n < k) return null
    if (payloads.size != n) return null
    if (k == 0) return emptyList()

    val payloadLen = payloads[0].size
    for (i in 1 until n) {
        if (payloads[i].size != payloadLen) return null
    }

    // Deep copy to avoid mutating originals.
    val mat = coeffs.copy()
    val pld = Array(n) { payloads[it].copyOf() }

    // Forward elimination with partial pivoting.
    for (col in 0 until k) {
        var pivotRow = -1
        for (row in col until n) {
            if (mat[row, col] != 0) {
                pivotRow = row
                break
            }
        }
        if (pivotRow < 0) return null // rank deficient

        // Swap pivot row into position.
        if (pivotRow != col) {
            for (c in 0 until k) {
                val tmp = mat[col, c]
                mat[col, c] = mat[pivotRow, c]
                mat[pivotRow, c] = tmp
            }
            val tmp = pld[col]
            pld[col] = pld[pivotRow]
            pld[pivotRow] = tmp
        }

        // Scale pivot row so diagonal = 1.
        val inv = HembGf256.inv(mat[col, col])
        for (c in 0 until k) {
            mat[col, c] = HembGf256.mul(mat[col, c], inv)
        }
        for (j in 0 until payloadLen) {
            pld[col][j] = HembGf256.mul(pld[col][j].toInt() and 0xFF, inv).toByte()
        }

        // Eliminate all other rows in this column.
        for (row in 0 until n) {
            if (row == col) continue
            val factor = mat[row, col]
            if (factor == 0) continue
            for (c in 0 until k) {
                mat[row, c] = HembGf256.add(mat[row, c], HembGf256.mul(factor, mat[col, c]))
            }
            for (j in 0 until payloadLen) {
                pld[row][j] = HembGf256.add(
                    pld[row][j].toInt() and 0xFF,
                    HembGf256.mul(factor, pld[col][j].toInt() and 0xFF)
                ).toByte()
            }
        }
    }

    return (0 until k).map { pld[it] }
}

/**
 * Compute the rank of an N×K coefficient matrix over GF(256).
 */
fun hembComputeRank(rows: List<ByteArray>, k: Int): Int {
    val n = rows.size
    if (n == 0 || k == 0) return 0

    val mat = HembGfMatrix(n, k)
    for (i in 0 until n) {
        for (j in 0 until k.coerceAtMost(rows[i].size)) {
            mat[i, j] = rows[i][j].toInt() and 0xFF
        }
    }

    var rank = 0
    for (col in 0 until k) {
        var pivotRow = -1
        for (row in rank until n) {
            if (mat[row, col] != 0) {
                pivotRow = row
                break
            }
        }
        if (pivotRow < 0) continue

        if (pivotRow != rank) {
            for (c in 0 until k) {
                val tmp = mat[rank, c]
                mat[rank, c] = mat[pivotRow, c]
                mat[pivotRow, c] = tmp
            }
        }

        val inv = HembGf256.inv(mat[rank, col])
        for (c in 0 until k) {
            mat[rank, c] = HembGf256.mul(mat[rank, c], inv)
        }

        for (row in rank + 1 until n) {
            val factor = mat[row, col]
            if (factor == 0) continue
            for (c in 0 until k) {
                mat[row, c] = HembGf256.add(mat[row, c], HembGf256.mul(factor, mat[rank, c]))
            }
        }
        rank++
    }
    return rank
}
