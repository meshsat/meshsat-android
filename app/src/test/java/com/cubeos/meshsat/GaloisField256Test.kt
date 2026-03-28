package com.cubeos.meshsat

import com.cubeos.meshsat.fec.GaloisField256
import org.junit.Assert.*
import org.junit.Test

class GaloisField256Test {
    @Test
    fun `add is XOR`() {
        assertEquals(0, GaloisField256.add(42, 42)) // a + a = 0
        assertEquals(42 xor 17, GaloisField256.add(42, 17))
    }

    @Test
    fun `multiply by 1 is identity`() {
        for (a in 0..255) {
            assertEquals(a, GaloisField256.mul(a, 1))
        }
    }

    @Test
    fun `multiply by 0 is 0`() {
        for (a in 0..255) {
            assertEquals(0, GaloisField256.mul(a, 0))
        }
    }

    @Test
    fun `multiply inverse equals 1`() {
        for (a in 1..255) {
            assertEquals("$a * inv($a) should be 1", 1, GaloisField256.mul(a, GaloisField256.inv(a)))
        }
    }

    @Test
    fun `division is inverse of multiplication`() {
        for (a in 1..255) {
            for (b in 1..10) { // spot check
                val product = GaloisField256.mul(a, b)
                assertEquals(a, GaloisField256.div(product, b))
            }
        }
    }

    @Test
    fun `associativity of multiplication`() {
        val a = 42; val b = 17; val c = 99
        assertEquals(
            GaloisField256.mul(GaloisField256.mul(a, b), c),
            GaloisField256.mul(a, GaloisField256.mul(b, c))
        )
    }

    @Test
    fun `pow 0 is 1`() {
        assertEquals(1, GaloisField256.pow(42, 0))
    }

    @Test
    fun `pow 1 is identity`() {
        assertEquals(42, GaloisField256.pow(42, 1))
    }
}
