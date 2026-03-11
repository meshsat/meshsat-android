package com.cubeos.meshsat.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption/decryption — compatible with MeshSat Pi's transform pipeline.
 *
 * Wire format: [12-byte nonce][ciphertext+tag]
 * Transport format: base64(wire format) for text channels (SMS).
 *
 * The hex key is shared between MeshSat Pi and MeshSat Android.
 */
object AesGcmCrypto {

    private const val KEY_SIZE_BITS = 256
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE_BITS = 128
    private const val ALGORITHM = "AES/GCM/NoPadding"

    /** Generate a random 256-bit key, returned as hex string. */
    fun generateKey(): String {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(KEY_SIZE_BITS, SecureRandom())
        return keyGen.generateKey().encoded.toHexString()
    }

    /** Encrypt plaintext bytes. Returns [nonce][ciphertext+tag]. */
    fun encrypt(plaintext: ByteArray, hexKey: String): ByteArray {
        val key = hexKey.hexToSecretKey()
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        return nonce + ciphertext
    }

    /** Decrypt [nonce][ciphertext+tag] back to plaintext bytes. */
    fun decrypt(data: ByteArray, hexKey: String): ByteArray {
        require(data.size > NONCE_SIZE) { "Data too short for AES-GCM" }

        val key = hexKey.hexToSecretKey()
        val nonce = data.copyOfRange(0, NONCE_SIZE)
        val ciphertext = data.copyOfRange(NONCE_SIZE, data.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    /** Encrypt a string and return base64 (for SMS transport). */
    fun encryptToBase64(plaintext: String, hexKey: String): String {
        val encrypted = encrypt(plaintext.toByteArray(Charsets.UTF_8), hexKey)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /** Decrypt a base64 string (from SMS transport) back to plaintext. */
    fun decryptFromBase64(base64Text: String, hexKey: String): String {
        val data = Base64.decode(base64Text, Base64.DEFAULT)
        val plaintext = decrypt(data, hexKey)
        return String(plaintext, Charsets.UTF_8)
    }

    /** Check if a string looks like a MeshSat encrypted message (base64, min length for nonce+tag). */
    fun looksEncrypted(text: String): Boolean {
        if (text.length < 24) return false // nonce + tag = ~37 base64 chars minimum
        return try {
            val decoded = Base64.decode(text.trim(), Base64.DEFAULT)
            decoded.size > NONCE_SIZE + TAG_SIZE_BITS / 8 // nonce + at least tag
        } catch (_: Exception) {
            false
        }
    }

    private fun String.hexToSecretKey(): SecretKey {
        val bytes = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(bytes.size == 32) { "Key must be 32 bytes (64 hex chars), got ${bytes.size}" }
        return SecretKeySpec(bytes, "AES")
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
