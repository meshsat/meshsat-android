package com.cubeos.meshsat.routing

import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.KeyAgreement
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Simple key-value store interface for persisting routing keys.
 * Implementations can back this with SharedPreferences, DataStore, or Room.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/**
 * Routing identity: Ed25519 signing key + X25519 encryption key.
 * Destination hash = first 16 bytes of SHA-256(signingPub || encryptionPub).
 *
 * Port of Go's routing/identity.go.
 * Requires Android API 33+ (Ed25519/X25519 via standard JCA).
 */
class Identity private constructor(
    private val signingPrivate: PrivateKey,
    private val signingPublic: PublicKey,
    private val encryptionPrivate: PrivateKey,
    private val encryptionPublic: PublicKey,
) {
    private val lock = ReentrantReadWriteLock()

    /** Raw 32-byte Ed25519 public key (for wire format). */
    val signingPubRaw: ByteArray = extractRaw(signingPublic)

    /** Raw 32-byte X25519 public key (for wire format). */
    val encryptionPubRaw: ByteArray = extractRaw(encryptionPublic)

    /** 16-byte destination hash. */
    val destHash: ByteArray = computeDestHash(signingPubRaw, encryptionPubRaw)

    /** Hex-encoded destination hash. */
    val destHashHex: String = destHash.toHex()

    /** Sign data with Ed25519. Returns 64-byte signature. */
    fun sign(data: ByteArray): ByteArray = lock.read {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(signingPrivate)
        sig.update(data)
        sig.sign()
    }

    /** Verify an Ed25519 signature against this identity's key. */
    fun verify(data: ByteArray, signature: ByteArray): Boolean = lock.read {
        verifyWith(signingPublic, data, signature)
    }

    /** Persist keys as hex to the given store. */
    fun persist(store: KeyValueStore) {
        store.set(KEY_SIGNING_PRIV, signingPrivate.encoded.toHex())
        store.set(KEY_SIGNING_PUB, signingPublic.encoded.toHex())
        store.set(KEY_ENCRYPTION_PRIV, encryptionPrivate.encoded.toHex())
        store.set(KEY_ENCRYPTION_PUB, encryptionPublic.encoded.toHex())
    }

    companion object {
        private const val TAG = "Identity"
        const val DEST_HASH_LEN = 16

        private const val KEY_SIGNING_PRIV = "routing_signing_key_private"
        private const val KEY_SIGNING_PUB = "routing_signing_key_public"
        private const val KEY_ENCRYPTION_PRIV = "routing_encryption_key_private"
        private const val KEY_ENCRYPTION_PUB = "routing_encryption_key_public"

        /** Load existing identity from store, or generate a new one. */
        fun loadOrGenerate(store: KeyValueStore): Identity {
            val sigPrivHex = store.get(KEY_SIGNING_PRIV)
            val sigPubHex = store.get(KEY_SIGNING_PUB)
            val encPrivHex = store.get(KEY_ENCRYPTION_PRIV)
            val encPubHex = store.get(KEY_ENCRYPTION_PUB)

            if (sigPrivHex != null && sigPubHex != null && encPrivHex != null && encPubHex != null) {
                return try {
                    loadFromHex(sigPrivHex, sigPubHex, encPrivHex, encPubHex).also {
                        Log.i(TAG, "Routing identity loaded: ${it.destHashHex}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load identity, regenerating: ${e.message}")
                    generate().also { it.persist(store) }
                }
            }

            return generate().also {
                it.persist(store)
                Log.i(TAG, "Routing identity generated: ${it.destHashHex}")
            }
        }

        /**
         * Generate a fresh identity with new keypairs.
         *
         * Android 16 (API 36) only exposes Ed25519/X25519 KeyPairGenerator via
         * AndroidKeyStore, which requires KeyGenParameterSpec and won't export raw
         * private keys. We generate 32-byte seeds, wrap them in RFC 8410 PKCS#8
         * DER, and load via KeyFactory (which works on Conscrypt).
         * The JCA re-encoding includes the public key in PKCS#8 v2 format.
         */
        /**
         * Generate a fresh identity with new keypairs.
         * BouncyCastle is registered as the primary JCA provider in MeshSatApp,
         * providing software Ed25519/X25519 on all Android versions including 16+.
         */
        fun generate(): Identity {
            val sigKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val encKp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
            return Identity(sigKp.private, sigKp.public, encKp.private, encKp.public)
        }

        private fun loadFromHex(
            sigPrivHex: String,
            sigPubHex: String,
            encPrivHex: String,
            encPubHex: String,
        ): Identity {
            val edFactory = KeyFactory.getInstance("Ed25519")
            val sigPriv = edFactory.generatePrivate(PKCS8EncodedKeySpec(sigPrivHex.hexToBytes()))
            val sigPub = edFactory.generatePublic(X509EncodedKeySpec(sigPubHex.hexToBytes()))

            val xFactory = KeyFactory.getInstance("X25519")
            val encPriv = xFactory.generatePrivate(PKCS8EncodedKeySpec(encPrivHex.hexToBytes()))
            val encPub = xFactory.generatePublic(X509EncodedKeySpec(encPubHex.hexToBytes()))

            return Identity(sigPriv, sigPub, encPriv, encPub)
        }

        /** Compute 16-byte destination hash from raw public keys. */
        fun computeDestHash(signingPubRaw: ByteArray, encryptionPubRaw: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(signingPubRaw)
            digest.update(encryptionPubRaw)
            return digest.digest().copyOfRange(0, DEST_HASH_LEN)
        }

        /** Verify an Ed25519 signature against an arbitrary public key. */
        fun verifyWith(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
            return try {
                val sig = Signature.getInstance("Ed25519")
                sig.initVerify(publicKey)
                sig.update(data)
                sig.verify(signature)
            } catch (_: Exception) {
                false
            }
        }

        /** Verify an Ed25519 signature using raw 32-byte public key bytes. */
        fun verifyWithRaw(pubKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            if (pubKeyRaw.size != 32) return false
            return try {
                val pubKey = rawToEd25519Public(pubKeyRaw)
                verifyWith(pubKey, data, signature)
            } catch (_: Exception) {
                false
            }
        }
    }
}

// --- Crypto helpers used across the routing package ---

/** Generate a fresh X25519 ephemeral keypair. */
internal fun generateX25519KeyPair(): KeyPair =
    KeyPairGenerator.getInstance("X25519").generateKeyPair()

/** Perform X25519 ECDH. Returns 32-byte shared secret. */
internal fun performEcdh(privateKey: PrivateKey, remotePublic: PublicKey): ByteArray {
    val ka = KeyAgreement.getInstance("X25519")
    ka.init(privateKey)
    ka.doPhase(remotePublic, true)
    return ka.generateSecret()
}

/** Reconstruct an Ed25519 PublicKey from raw 32 bytes. */
internal fun rawToEd25519Public(raw: ByteArray): PublicKey {
    require(raw.size == 32) { "Ed25519 public key must be 32 bytes" }
    val der = ED25519_X509_PREFIX + raw
    return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
}

/** Reconstruct an X25519 PublicKey from raw 32 bytes. */
internal fun rawToX25519Public(raw: ByteArray): PublicKey {
    require(raw.size == 32) { "X25519 public key must be 32 bytes" }
    val der = X25519_X509_PREFIX + raw
    return KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(der))
}

/** Extract raw 32-byte key from X.509 DER-encoded public key. */
internal fun extractRaw(publicKey: PublicKey): ByteArray {
    val enc = publicKey.encoded
    // X.509 DER: 12-byte header + 32-byte raw key = 44 bytes
    require(enc.size == 44) { "Unexpected X.509 key length: ${enc.size}" }
    return enc.copyOfRange(12, 44)
}

// DER prefixes for Ed25519/X25519 X.509 SubjectPublicKeyInfo
private val ED25519_X509_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
    0x70, 0x03, 0x21, 0x00
)
private val X25519_X509_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
    0x6e, 0x03, 0x21, 0x00
)

// --- Hex conversion extensions ---

internal fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
