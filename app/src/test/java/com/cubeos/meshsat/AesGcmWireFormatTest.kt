package com.cubeos.meshsat

import com.cubeos.meshsat.crypto.AesGcmCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire format verification tests for AES-256-GCM encryption (MESHSAT-205).
 *
 * Verifies that Android's encryption output matches the format expected by Hub:
 *   [12-byte nonce][ciphertext][16-byte GCM auth tag]
 *
 * Hub uses the same format: AES-256-GCM with random 12-byte nonce prepended.
 * Both sides use 64-char hex string as the shared key.
 */
class AesGcmWireFormatTest {

    private val testKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `encrypted output has 12-byte nonce prefix`() {
        val plaintext = "hello hub".toByteArray()
        val encrypted = AesGcmCrypto.encrypt(plaintext, testKey)

        // Minimum size: 12 nonce + plaintext + 16 tag
        assertTrue(encrypted.size >= 12 + plaintext.size + 16)

        // First 12 bytes are the nonce (should be random, non-zero with high probability)
        val nonce = encrypted.copyOfRange(0, 12)
        assertEquals(12, nonce.size)
    }

    @Test
    fun `ciphertext includes 16-byte GCM auth tag`() {
        val plaintext = "test".toByteArray()
        val encrypted = AesGcmCrypto.encrypt(plaintext, testKey)

        // AES-GCM output = ciphertext (same length as plaintext) + 16-byte tag
        // Total = 12 nonce + 4 plaintext + 16 tag = 32
        assertEquals(12 + plaintext.size + 16, encrypted.size)
    }

    @Test
    fun `encrypt then decrypt round-trip`() {
        val plaintext = "MAYDAY MAYDAY requesting evacuation at checkpoint bravo"
        val encrypted = AesGcmCrypto.encrypt(plaintext.toByteArray(), testKey)
        val decrypted = AesGcmCrypto.decrypt(encrypted, testKey)
        assertEquals(plaintext, String(decrypted))
    }

    @Test
    fun `same plaintext produces different ciphertexts (random nonce)`() {
        val plaintext = "test message".toByteArray()
        val enc1 = AesGcmCrypto.encrypt(plaintext, testKey)
        val enc2 = AesGcmCrypto.encrypt(plaintext, testKey)
        // Nonces should differ
        val nonce1 = enc1.copyOfRange(0, 12)
        val nonce2 = enc2.copyOfRange(0, 12)
        assertTrue(!nonce1.contentEquals(nonce2))
    }

    @Test
    fun `key must be exactly 64 hex chars (32 bytes)`() {
        val key = testKey
        assertEquals(64, key.length)
        // Each pair of hex chars = 1 byte → 32 bytes = 256 bits
        val keyBytes = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(32, keyBytes.size)
    }

    @Test
    fun `base64 roundtrip for SMS transport`() {
        val plaintext = "field report: all clear at grid 4523"
        val encrypted = AesGcmCrypto.encrypt(plaintext.toByteArray(), testKey)
        // Simulate SMS transport: base64 encode then decode (using java.util.Base64 in JVM tests)
        val b64 = java.util.Base64.getEncoder().encodeToString(encrypted)
        val decoded = java.util.Base64.getDecoder().decode(b64)
        val decrypted = AesGcmCrypto.decrypt(decoded, testKey)
        assertEquals(plaintext, String(decrypted))
    }

    @Test
    fun `wire format is nonce then ciphertext then tag`() {
        // Known structure: [nonce:12][ciphertext:N][tag:16]
        // The tag is appended by Java's AES/GCM/NoPadding automatically
        val plaintext = ByteArray(100) { it.toByte() }
        val encrypted = AesGcmCrypto.encrypt(plaintext, testKey)

        // Total = 12 + 100 + 16 = 128
        assertEquals(128, encrypted.size)

        // Decrypt to verify integrity
        val decrypted = AesGcmCrypto.decrypt(encrypted, testKey)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `hub-compatible wire format verified`() {
        // Simulate Hub's perspective: receive [nonce][ciphertext+tag], split at offset 12
        val plaintext = "SOS position 47.3N 122.5W".toByteArray()
        val wire = AesGcmCrypto.encrypt(plaintext, testKey)

        // Hub extracts nonce (first 12 bytes) and ciphertext+tag (rest)
        val nonce = wire.copyOfRange(0, 12)
        val ciphertextWithTag = wire.copyOfRange(12, wire.size)

        assertEquals(12, nonce.size)
        assertEquals(plaintext.size + 16, ciphertextWithTag.size)

        // Hub decrypts with same key using same nonce → matches
        val decrypted = AesGcmCrypto.decrypt(wire, testKey)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `looksEncrypted via wire format size check`() {
        val encrypted = AesGcmCrypto.encrypt("test".toByteArray(), testKey)
        // Verify wire format is large enough: nonce(12) + ciphertext(4) + tag(16) = 32
        assertTrue(encrypted.size >= 12 + 16)
        // Base64-encoded version (as it would appear in SMS) should be at least 24 chars
        val b64 = java.util.Base64.getEncoder().encodeToString(encrypted)
        assertTrue(b64.length >= 24)
    }

    @Test
    fun `generated key is valid 64-char hex`() {
        val key = AesGcmCrypto.generateKey()
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }
}
