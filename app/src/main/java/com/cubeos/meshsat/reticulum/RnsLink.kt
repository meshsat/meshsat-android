package com.cubeos.meshsat.reticulum

import com.cubeos.meshsat.routing.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Link state in the Reticulum handshake lifecycle. */
enum class RnsLinkState {
    PENDING,      // request sent, waiting for proof
    HANDSHAKE,    // proof received, confirming
    ACTIVE,       // ECDH complete, symmetric keys derived
    STALE,        // no activity for STALE_TIME
    CLOSED,       // explicitly closed or timed out
}

/** Encryption modes supported for link data. */
enum class RnsEncryptionMode(val id: Byte) {
    AES_256_CBC(0x00),   // Reticulum default
    AES_256_GCM(0x01),   // MeshSat extension (preferred)
    ;

    companion object {
        fun fromId(id: Byte): RnsEncryptionMode = entries.firstOrNull { it.id == id } ?: AES_256_CBC
    }
}

/**
 * A Reticulum-compatible cryptographic link between two nodes.
 *
 * Differences from legacy MeshSat Link:
 * - Link ID is 16 bytes (truncated hash) instead of 32 bytes (full SHA-256)
 * - Key derivation uses HKDF instead of SHA-256
 * - Supports both AES-256-CBC (Reticulum compat) and AES-256-GCM (MeshSat preferred)
 * - Keepalive is 1 byte (0xFF/0xFE) instead of 34 bytes
 * - Signalling bytes negotiate MTU and encryption mode
 */
class RnsLink(
    val id: ByteArray,                   // 16 bytes (truncated hash of link request)
    val destHash: ByteArray,             // 16 bytes (remote destination)
    @Volatile var state: RnsLinkState,
    val encryptionMode: RnsEncryptionMode = RnsEncryptionMode.AES_256_GCM,
    var sharedSecret: ByteArray? = null,
    var derivedKey: ByteArray? = null,    // HKDF-derived key material
    var sendKey: ByteArray? = null,       // AES key for sending
    var recvKey: ByteArray? = null,       // AES key for receiving
    var sendNonce: Long = 0,
    var recvNonce: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var lastActivity: Long = System.currentTimeMillis(),
    val isInitiator: Boolean,
) {
    val idHex: String get() = id.toHex()

    /** Encrypt plaintext using the link's send key. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        check(state == RnsLinkState.ACTIVE && sendKey != null) { "link not active" }
        return when (encryptionMode) {
            RnsEncryptionMode.AES_256_GCM -> encryptGcm(plaintext)
            RnsEncryptionMode.AES_256_CBC -> encryptCbc(plaintext)
        }
    }

    /** Decrypt data using the link's receive key. */
    fun decrypt(data: ByteArray): ByteArray {
        check(state == RnsLinkState.ACTIVE && recvKey != null) { "link not active" }
        return when (encryptionMode) {
            RnsEncryptionMode.AES_256_GCM -> decryptGcm(data)
            RnsEncryptionMode.AES_256_CBC -> decryptCbc(data)
        }
    }

    private fun encryptGcm(plaintext: ByteArray): ByteArray {
        val key = SecretKeySpec(sendKey, "AES")
        val cipher = Cipher.getInstance(GCM_ALGORITHM)
        val nonce = ByteArray(GCM_NONCE_SIZE)
        ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN).putLong(sendNonce)
        sendNonce++
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    private fun decryptGcm(data: ByteArray): ByteArray {
        require(data.size > GCM_NONCE_SIZE) { "ciphertext too short" }
        val key = SecretKeySpec(recvKey, "AES")
        val cipher = Cipher.getInstance(GCM_ALGORITHM)
        val nonce = data.copyOfRange(0, GCM_NONCE_SIZE)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        recvNonce++
        return cipher.doFinal(data, GCM_NONCE_SIZE, data.size - GCM_NONCE_SIZE)
    }

    private fun encryptCbc(plaintext: ByteArray): ByteArray {
        val key = SecretKeySpec(sendKey, "AES")
        val cipher = Cipher.getInstance(CBC_ALGORITHM)
        val iv = ByteArray(CBC_IV_SIZE)
        java.security.SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return iv + cipher.doFinal(plaintext)
    }

    private fun decryptCbc(data: ByteArray): ByteArray {
        require(data.size > CBC_IV_SIZE) { "ciphertext too short" }
        val key = SecretKeySpec(recvKey, "AES")
        val cipher = Cipher.getInstance(CBC_ALGORITHM)
        val iv = data.copyOfRange(0, CBC_IV_SIZE)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(data, CBC_IV_SIZE, data.size - CBC_IV_SIZE)
    }

    companion object {
        const val LINK_ID_LEN = RnsConstants.DEST_HASH_LEN  // 16 bytes (truncated hash)

        private const val GCM_NONCE_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_ALGORITHM = "AES/GCM/NoPadding"
        private const val CBC_IV_SIZE = 16
        private const val CBC_ALGORITHM = "AES/CBC/PKCS5Padding"
    }
}

// --- Link handshake packet data (inside RnsPacket.data) ---

/**
 * Signalling bytes for link establishment (3 bytes).
 *   [0:2] mtu (uint16 big-endian) — 0 = use default (500)
 *   [2]   encryption_mode — 0x00=AES-256-CBC, 0x01=AES-256-GCM
 */
data class RnsSignalling(
    val mtu: Int = RnsConstants.MTU,
    val encryptionMode: RnsEncryptionMode = RnsEncryptionMode.AES_256_GCM,
) {
    fun marshal(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putShort(mtu.toShort())
        buf.put(encryptionMode.id)
        return buf.array()
    }

    companion object {
        const val SIZE = 3

        fun unmarshal(data: ByteArray, offset: Int = 0): RnsSignalling {
            require(data.size >= offset + SIZE) { "signalling too short" }
            val buf = ByteBuffer.wrap(data, offset, SIZE)
            buf.order(ByteOrder.BIG_ENDIAN)
            val mtu = buf.short.toInt() and 0xFFFF
            val mode = RnsEncryptionMode.fromId(buf.get())
            return RnsSignalling(if (mtu == 0) RnsConstants.MTU else mtu, mode)
        }
    }
}

/**
 * Link request data (67 bytes).
 * Carried inside RnsPacket(type=LINKREQUEST, dest=target_dest_hash).
 *
 *   [0:32]  X25519 ephemeral public key (initiator)
 *   [32:64] Ed25519 signing public key (initiator identity)
 *   [64:67] signalling bytes (MTU + encryption mode)
 */
data class RnsLinkRequest(
    val ephemeralPub: ByteArray,  // 32 bytes (X25519)
    val signingPub: ByteArray,    // 32 bytes (Ed25519)
    val signalling: RnsSignalling,
) {
    fun marshal(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.put(ephemeralPub)
        buf.put(signingPub)
        buf.put(signalling.marshal())
        return buf.array()
    }

    companion object {
        const val SIZE = RnsConstants.PUB_KEY_LEN * 2 + RnsSignalling.SIZE  // 67

        fun unmarshal(data: ByteArray): RnsLinkRequest {
            require(data.size >= SIZE) { "link request data too short: ${data.size} < $SIZE" }
            val buf = ByteBuffer.wrap(data)
            val ephPub = ByteArray(RnsConstants.PUB_KEY_LEN)
            buf.get(ephPub)
            val sigPub = ByteArray(RnsConstants.PUB_KEY_LEN)
            buf.get(sigPub)
            val sig = RnsSignalling.unmarshal(data, RnsConstants.PUB_KEY_LEN * 2)
            return RnsLinkRequest(ephPub, sigPub, sig)
        }
    }
}

/**
 * Link proof data (99 bytes).
 * Carried inside RnsPacket(type=PROOF, dest=link_id_as_dest).
 *
 *   [0:64]  Ed25519 signature (over link_id + pub + sig_pub + signalling)
 *   [64:96] X25519 ephemeral public key (responder)
 *   [96:99] signalling bytes
 */
data class RnsLinkProof(
    val signature: ByteArray,     // 64 bytes (Ed25519)
    val ephemeralPub: ByteArray,  // 32 bytes (X25519)
    val signalling: RnsSignalling,
) {
    fun marshal(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.put(signature)
        buf.put(ephemeralPub)
        buf.put(signalling.marshal())
        return buf.array()
    }

    companion object {
        const val SIZE = RnsConstants.SIG_LEN + RnsConstants.PUB_KEY_LEN + RnsSignalling.SIZE  // 99

        fun unmarshal(data: ByteArray): RnsLinkProof {
            require(data.size >= SIZE) { "link proof data too short: ${data.size} < $SIZE" }
            val buf = ByteBuffer.wrap(data)
            val sig = ByteArray(RnsConstants.SIG_LEN)
            buf.get(sig)
            val ephPub = ByteArray(RnsConstants.PUB_KEY_LEN)
            buf.get(ephPub)
            val signalling = RnsSignalling.unmarshal(data, RnsConstants.SIG_LEN + RnsConstants.PUB_KEY_LEN)
            return RnsLinkProof(sig, ephPub, signalling)
        }
    }
}
