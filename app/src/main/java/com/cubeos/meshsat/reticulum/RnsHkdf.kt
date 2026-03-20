package com.cubeos.meshsat.reticulum

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (HMAC-based Key Derivation Function) per RFC 5869.
 *
 * Used by Reticulum for deriving symmetric link keys from ECDH shared secrets.
 * Replaces the legacy SHA-256(shared_secret + link_id + "key1/key2") approach.
 */
object RnsHkdf {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32  // SHA-256 output

    /**
     * HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
     *
     * @param salt Optional salt (defaults to all-zero bytes of hash length)
     * @param ikm Input Keying Material (the ECDH shared secret)
     * @return Pseudorandom Key (32 bytes)
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = salt ?: ByteArray(HASH_LEN)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand: OKM = T(1) || T(2) || ... || T(N) truncated to length
     *
     * @param prk Pseudorandom Key (from extract)
     * @param info Optional context (can be null)
     * @param length Desired output length in bytes
     * @return Output Keying Material
     */
    fun expand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "requested length too large" }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val n = (length + HASH_LEN - 1) / HASH_LEN
        val okm = ByteArray(n * HASH_LEN)
        var prev = ByteArray(0)

        for (i in 1..n) {
            mac.reset()
            mac.update(prev)
            if (info != null) mac.update(info)
            mac.update(i.toByte())
            prev = mac.doFinal()
            System.arraycopy(prev, 0, okm, (i - 1) * HASH_LEN, HASH_LEN)
        }

        return okm.copyOfRange(0, length)
    }

    /**
     * Full HKDF: Extract-then-Expand.
     *
     * @param length Desired output length in bytes
     * @param deriveFrom Input keying material (ECDH shared secret)
     * @param salt Salt (link_id for Reticulum links)
     * @param context Optional context info
     * @return Derived key material
     */
    fun derive(
        length: Int,
        deriveFrom: ByteArray,
        salt: ByteArray? = null,
        context: ByteArray? = null,
    ): ByteArray {
        val prk = extract(salt, deriveFrom)
        return expand(prk, context, length)
    }

    /**
     * Derive send and receive AES-256 keys for a Reticulum link.
     *
     * Produces 64 bytes of key material via HKDF, split into two 32-byte keys.
     * Initiator: first 32 bytes = send, second 32 bytes = recv.
     * Responder: first 32 bytes = recv, second 32 bytes = send.
     *
     * @param sharedSecret 32-byte ECDH shared secret
     * @param salt Link ID (16 bytes, used as HKDF salt per Reticulum spec)
     * @param isInitiator Whether this node initiated the link
     * @return Pair of (sendKey, recvKey), each 32 bytes
     */
    fun deriveLinkKeys(
        sharedSecret: ByteArray,
        salt: ByteArray,
        isInitiator: Boolean,
    ): Pair<ByteArray, ByteArray> {
        val keyMaterial = derive(
            length = 64,
            deriveFrom = sharedSecret,
            salt = salt,
        )

        val key1 = keyMaterial.copyOfRange(0, 32)
        val key2 = keyMaterial.copyOfRange(32, 64)

        return if (isInitiator) key1 to key2 else key2 to key1
    }
}
