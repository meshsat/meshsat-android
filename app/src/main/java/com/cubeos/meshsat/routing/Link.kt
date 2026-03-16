package com.cubeos.meshsat.routing

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Link state in the 3-packet handshake lifecycle. */
enum class LinkState {
    PENDING,      // request sent, waiting for response
    ESTABLISHED,  // ECDH complete, symmetric keys derived
    CLOSED,       // explicitly closed or timed out
}

/**
 * An established or pending cryptographic link between two nodes.
 * Contains symmetric AES-256-GCM keys derived from ECDH.
 */
class Link(
    val id: ByteArray,                   // 32 bytes (SHA-256 of link request)
    val destHash: ByteArray,             // 16 bytes (remote destination)
    @Volatile var state: LinkState,
    val localEphPrivate: PrivateKey?,     // our ephemeral X25519 private key
    val localEphPublic: PublicKey?,       // our ephemeral X25519 public key
    var remoteEphPublic: PublicKey? = null,
    var sharedSecret: ByteArray? = null,
    var sendKey: ByteArray? = null,       // AES-256 key for sending
    var recvKey: ByteArray? = null,       // AES-256 key for receiving
    var sendNonce: Long = 0,
    var recvNonce: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var lastActivity: Long = System.currentTimeMillis(),
    val isInitiator: Boolean,
) {
    val idHex: String get() = id.toHex().take(32)  // first 16 bytes hex for readability

    /** Encrypt plaintext using the link's send key with AES-256-GCM. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        check(state == LinkState.ESTABLISHED && sendKey != null && sendKey!!.size == SYM_KEY_LEN) {
            "link not established"
        }

        val key = SecretKeySpec(sendKey, "AES")
        val cipher = Cipher.getInstance(GCM_ALGORITHM)

        // Nonce: 8-byte counter (big-endian) + 4-byte zeros = 12 bytes
        val nonce = ByteArray(GCM_NONCE_SIZE)
        ByteBuffer.wrap(nonce).putLong(sendNonce)
        sendNonce++

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend nonce to ciphertext
        return nonce + ciphertext
    }

    /** Decrypt data using the link's receive key with AES-256-GCM. */
    fun decrypt(data: ByteArray): ByteArray {
        check(state == LinkState.ESTABLISHED && recvKey != null && recvKey!!.size == SYM_KEY_LEN) {
            "link not established"
        }
        require(data.size > GCM_NONCE_SIZE) { "ciphertext too short" }

        val key = SecretKeySpec(recvKey, "AES")
        val cipher = Cipher.getInstance(GCM_ALGORITHM)
        val nonce = data.copyOfRange(0, GCM_NONCE_SIZE)
        val ciphertext = data.copyOfRange(GCM_NONCE_SIZE, data.size)

        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val plaintext = cipher.doFinal(ciphertext)
        recvNonce++
        return plaintext
    }

    companion object {
        const val SYM_KEY_LEN = 32
        private const val GCM_NONCE_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_ALGORITHM = "AES/GCM/NoPadding"
    }
}

// --- Link packet type identifiers ---

const val PACKET_LINK_REQUEST: Byte = 0x10
const val PACKET_LINK_RESPONSE: Byte = 0x11
const val PACKET_LINK_CONFIRM: Byte = 0x12
const val PACKET_LINK_DATA: Byte = 0x13

// Wire sizes
const val LINK_ID_LEN = 32
const val LINK_REQUEST_LEN = 1 + Identity.DEST_HASH_LEN + 32 + 16   // 65
const val LINK_RESPONSE_LEN = 1 + LINK_ID_LEN + 32 + 64            // 129
const val LINK_CONFIRM_LEN = 1 + LINK_ID_LEN + 32                   // 65
// Total handshake: 65 + 129 + 65 = 259 bytes (fits Iridium SBD at 340)

// --- Link Request ---

/** First packet in the 3-packet handshake. */
data class LinkRequest(
    val destHash: ByteArray,          // 16 bytes
    val ephemeralPubRaw: ByteArray,   // 32 bytes (raw X25519)
    val random: ByteArray,            // 16 bytes
) {
    fun marshal(): ByteArray {
        val buf = ByteBuffer.allocate(LINK_REQUEST_LEN)
        buf.put(PACKET_LINK_REQUEST)
        buf.put(destHash)
        buf.put(ephemeralPubRaw)
        buf.put(random)
        return buf.array()
    }

    /** Compute link ID as SHA-256 of the marshaled request. */
    fun computeLinkId(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(marshal())
    }

    companion object {
        fun unmarshal(data: ByteArray): LinkRequest {
            require(data.size >= LINK_REQUEST_LEN) { "link request too short" }
            require(data[0] == PACKET_LINK_REQUEST) { "not a link request" }

            val buf = ByteBuffer.wrap(data, 1, LINK_REQUEST_LEN - 1)
            val destHash = ByteArray(Identity.DEST_HASH_LEN)
            buf.get(destHash)
            val ephPub = ByteArray(32)
            buf.get(ephPub)
            val random = ByteArray(16)
            buf.get(random)
            return LinkRequest(destHash, ephPub, random)
        }
    }
}

// --- Link Response ---

/** Second packet: responder's ephemeral key + Ed25519 signature. */
data class LinkResponse(
    val linkId: ByteArray,            // 32 bytes
    val ephemeralPubRaw: ByteArray,   // 32 bytes (raw X25519)
    val signature: ByteArray,         // 64 bytes (Ed25519)
) {
    fun marshal(): ByteArray {
        val buf = ByteBuffer.allocate(LINK_RESPONSE_LEN)
        buf.put(PACKET_LINK_RESPONSE)
        buf.put(linkId)
        buf.put(ephemeralPubRaw)
        buf.put(signature)
        return buf.array()
    }

    companion object {
        fun unmarshal(data: ByteArray): LinkResponse {
            require(data.size >= LINK_RESPONSE_LEN) { "link response too short" }
            require(data[0] == PACKET_LINK_RESPONSE) { "not a link response" }

            val buf = ByteBuffer.wrap(data, 1, LINK_RESPONSE_LEN - 1)
            val linkId = ByteArray(LINK_ID_LEN)
            buf.get(linkId)
            val ephPub = ByteArray(32)
            buf.get(ephPub)
            val signature = ByteArray(64)
            buf.get(signature)
            return LinkResponse(linkId, ephPub, signature)
        }
    }
}

// --- Link Confirm ---

/** Third packet: proof that initiator derived the shared secret. */
data class LinkConfirm(
    val linkId: ByteArray,    // 32 bytes
    val proof: ByteArray,     // 32 bytes: SHA-256(shared_secret + link_id + "confirm")
) {
    fun marshal(): ByteArray {
        val buf = ByteBuffer.allocate(LINK_CONFIRM_LEN)
        buf.put(PACKET_LINK_CONFIRM)
        buf.put(linkId)
        buf.put(proof)
        return buf.array()
    }

    companion object {
        fun unmarshal(data: ByteArray): LinkConfirm {
            require(data.size >= LINK_CONFIRM_LEN) { "link confirm too short" }
            require(data[0] == PACKET_LINK_CONFIRM) { "not a link confirm" }

            val buf = ByteBuffer.wrap(data, 1, LINK_CONFIRM_LEN - 1)
            val linkId = ByteArray(LINK_ID_LEN)
            buf.get(linkId)
            val proof = ByteArray(32)
            buf.get(proof)
            return LinkConfirm(linkId, proof)
        }
    }
}

// --- Key derivation helpers ---

/** Compute SHA-256(shared_secret + link_id + "confirm"). */
internal fun computeConfirmProof(sharedSecret: ByteArray, linkId: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(sharedSecret)
    digest.update(linkId)
    digest.update("confirm".toByteArray())
    return digest.digest()
}

/**
 * Derive send and receive AES-256 keys from ECDH shared secret.
 * key1 = SHA-256(shared_secret + link_id + "key1")
 * key2 = SHA-256(shared_secret + link_id + "key2")
 *
 * Initiator uses (key1=send, key2=recv); responder uses (key1=recv, key2=send).
 */
internal fun deriveSymKeys(sharedSecret: ByteArray, linkId: ByteArray): Pair<ByteArray, ByteArray> {
    val d1 = MessageDigest.getInstance("SHA-256")
    d1.update(sharedSecret)
    d1.update(linkId)
    d1.update("key1".toByteArray())
    val key1 = d1.digest()

    val d2 = MessageDigest.getInstance("SHA-256")
    d2.update(sharedSecret)
    d2.update(linkId)
    d2.update("key2".toByteArray())
    val key2 = d2.digest()

    return key1 to key2
}
