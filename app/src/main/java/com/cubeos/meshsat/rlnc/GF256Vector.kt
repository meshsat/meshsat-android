package com.cubeos.meshsat.rlnc

import com.cubeos.meshsat.fec.GaloisField256

/**
 * Vector operations over GF(256) for RLNC encoding/decoding.
 */
object GF256Vector {

    /** Dot product of two byte arrays over GF(256). */
    fun dot(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size) { "Vectors must have same length" }
        var result = 0
        for (i in a.indices) {
            result = GaloisField256.add(
                result,
                GaloisField256.mul(a[i].toInt() and 0xFF, b[i].toInt() and 0xFF)
            )
        }
        return result
    }

    /** Scalar multiply: c * v over GF(256). */
    fun scalarMul(c: Int, v: ByteArray): ByteArray {
        return ByteArray(v.size) { i ->
            GaloisField256.mul(c, v[i].toInt() and 0xFF).toByte()
        }
    }

    /** Vector addition: a + b over GF(256) (XOR). */
    fun add(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "Vectors must have same length" }
        return ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    /** Generate random coefficients over GF(256) (non-zero). */
    fun randomCoefficients(k: Int): ByteArray {
        val rng = java.security.SecureRandom()
        return ByteArray(k) {
            var v: Int
            do { v = rng.nextInt(256) } while (v == 0)
            v.toByte()
        }
    }
}
