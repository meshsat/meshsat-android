package com.cubeos.meshsat.reticulum

import java.security.MessageDigest

/**
 * Reticulum-compatible destination hash computation.
 *
 * Reticulum destination hash = truncated_hash(name_hash + identity_hash)
 *
 * Where:
 *   name_hash     = SHA-256(app_name.aspect1.aspect2...)[:10]   (80 bits)
 *   identity_hash = SHA-256(encryption_pub + signing_pub)[:16]  (128 bits)
 *   dest_hash     = SHA-256(name_hash + identity_hash)[:16]     (128 bits)
 *
 * NOTE: Reticulum orders keys as (X25519 encryption, Ed25519 signing) in identity hash,
 * which differs from the original MeshSat order (signing, encryption).
 */
object RnsDestination {

    /** Default MeshSat application name for Reticulum destinations. */
    const val APP_NAME = "meshsat"

    /** Standard aspects for MeshSat node destinations. */
    const val ASPECT_NODE = "node"

    /**
     * Compute the full SHA-256 hash of data.
     */
    fun fullHash(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    /**
     * Compute truncated hash: SHA-256(data)[:DEST_HASH_LEN].
     * Returns 16 bytes (128 bits).
     */
    fun truncatedHash(data: ByteArray): ByteArray {
        return fullHash(data).copyOfRange(0, RnsConstants.DEST_HASH_LEN)
    }

    /**
     * Compute the name hash for a destination.
     * name_hash = SHA-256("appname.aspect1.aspect2")[:NAME_HASH_LEN]
     *
     * @param appName Application name (e.g. "meshsat")
     * @param aspects Destination aspects (e.g. "node", "message")
     * @return 10-byte name hash
     */
    fun nameHash(appName: String, vararg aspects: String): ByteArray {
        val expandedName = expandName(appName, *aspects)
        return fullHash(expandedName.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, RnsConstants.NAME_HASH_LEN)
    }

    /**
     * Expand destination name to dotted notation.
     * Example: expandName("meshsat", "node") → "meshsat.node"
     */
    fun expandName(appName: String, vararg aspects: String): String {
        return if (aspects.isEmpty()) appName
        else appName + "." + aspects.joinToString(".")
    }

    /**
     * Compute Reticulum identity hash from raw public keys.
     *
     * identity_hash = SHA-256(encryption_pub + signing_pub)[:16]
     *
     * NOTE: Reticulum uses (X25519, Ed25519) order — encryption key first.
     *
     * @param encryptionPubRaw Raw 32-byte X25519 public key
     * @param signingPubRaw Raw 32-byte Ed25519 public key
     * @return 16-byte identity hash
     */
    fun identityHash(encryptionPubRaw: ByteArray, signingPubRaw: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(encryptionPubRaw)
        digest.update(signingPubRaw)
        return digest.digest().copyOfRange(0, RnsConstants.IDENTITY_HASH_LEN)
    }

    /**
     * Compute the full Reticulum destination hash.
     *
     * dest_hash = SHA-256(name_hash + identity_hash)[:16]
     *
     * This is the primary addressing mechanism in Reticulum. Two nodes with
     * identical keys but different app names will have different destination hashes.
     *
     * @param encryptionPubRaw Raw 32-byte X25519 public key
     * @param signingPubRaw Raw 32-byte Ed25519 public key
     * @param appName Application name (default: "meshsat")
     * @param aspects Destination aspects (default: "node")
     * @return 16-byte destination hash
     */
    fun computeDestHash(
        encryptionPubRaw: ByteArray,
        signingPubRaw: ByteArray,
        appName: String = APP_NAME,
        vararg aspects: String = arrayOf(ASPECT_NODE),
    ): ByteArray {
        val nHash = nameHash(appName, *aspects)
        val iHash = identityHash(encryptionPubRaw, signingPubRaw)
        val material = nHash + iHash
        return truncatedHash(material)
    }

    /**
     * Compute destination hash for a PLAIN destination (no identity).
     * dest_hash = SHA-256(name_hash)[:16]
     */
    fun computePlainDestHash(
        appName: String,
        vararg aspects: String,
    ): ByteArray {
        val nHash = nameHash(appName, *aspects)
        return truncatedHash(nHash)
    }

    /**
     * Generate a random hash (used for announce dedup, nonces, etc).
     * random_hash = SHA-256(random_bytes)[:DEST_HASH_LEN]
     */
    fun randomHash(): ByteArray {
        val random = ByteArray(RnsConstants.DEST_HASH_LEN)
        java.security.SecureRandom().nextBytes(random)
        return truncatedHash(random)
    }

    /**
     * Compute a ratchet ID from a ratchet public key.
     * ratchet_id = SHA-256(ratchet_pub)[:RATCHET_ID_LEN]
     */
    fun ratchetId(ratchetPubRaw: ByteArray): ByteArray {
        return fullHash(ratchetPubRaw).copyOfRange(0, RnsConstants.RATCHET_ID_LEN)
    }
}
