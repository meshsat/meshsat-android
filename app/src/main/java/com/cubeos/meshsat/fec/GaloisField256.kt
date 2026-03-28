package com.cubeos.meshsat.fec

/**
 * GF(2^8) arithmetic using primitive polynomial x^8+x^4+x^3+x^2+1 (0x11D).
 * Precomputed log/exp tables for fast multiply/divide/inverse.
 *
 * Used by both Reed-Solomon FEC and RLNC network coding.
 */
object GaloisField256 {
    private const val FIELD_SIZE = 256
    private const val PRIMITIVE_POLY = 0x11D // x^8+x^4+x^3+x^2+1

    val EXP = IntArray(FIELD_SIZE * 2) // exp[i] = alpha^i
    val LOG = IntArray(FIELD_SIZE)      // log[alpha^i] = i

    init {
        var x = 1
        for (i in 0 until FIELD_SIZE - 1) {
            EXP[i] = x
            EXP[i + FIELD_SIZE - 1] = x
            LOG[x] = i
            x = x shl 1
            if (x >= FIELD_SIZE) x = x xor PRIMITIVE_POLY
        }
        LOG[0] = FIELD_SIZE - 1 // convention: log(0) = 255
    }

    /** Addition in GF(256) = XOR. */
    fun add(a: Int, b: Int): Int = a xor b

    /** Subtraction in GF(256) = XOR (same as addition in characteristic 2). */
    fun sub(a: Int, b: Int): Int = a xor b

    /** Multiplication in GF(256) via log/exp tables. */
    fun mul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return EXP[LOG[a] + LOG[b]]
    }

    /** Division in GF(256): a / b. */
    fun div(a: Int, b: Int): Int {
        require(b != 0) { "Division by zero in GF(256)" }
        if (a == 0) return 0
        return EXP[(LOG[a] - LOG[b] + (FIELD_SIZE - 1)) % (FIELD_SIZE - 1)]
    }

    /** Multiplicative inverse: 1/a. */
    fun inv(a: Int): Int {
        require(a != 0) { "Inverse of zero in GF(256)" }
        return EXP[(FIELD_SIZE - 1) - LOG[a]]
    }

    /** Raise to power: a^n in GF(256). */
    fun pow(a: Int, n: Int): Int {
        if (a == 0) return 0
        return EXP[(LOG[a] * n) % (FIELD_SIZE - 1)]
    }
}
