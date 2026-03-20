package com.cubeos.meshsat.reticulum

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Reticulum-compatible announce data.
 *
 * This is the DATA portion of an ANNOUNCE packet (inside RnsPacket.data).
 * The RnsPacket header already carries the destination hash and hop count.
 *
 * Announce data layout:
 *   [0:32]    public_key (X25519 encryption key, 32 bytes)
 *   [32:42]   name_hash (10 bytes)
 *   [42:52]   random_hash (10 bytes: 5 random + 5 timestamp)
 *   [52:84]   signature (Ed25519, 64 bytes) — covers hash_material
 *   [84..]    app_data (optional, variable length)
 *
 * With optional ratchet (inserted before signature):
 *   [52:62]   ratchet_public_key_id (10 bytes, SHA-256(ratchet_pub)[:10])
 *   [62:94]   signature (64 bytes)
 *   [94..]    app_data (optional)
 *
 * The signing key (Ed25519 public key) is implicit — the receiver can derive it
 * by looking up the identity from the destination hash, or the announce carries
 * the full identity (signing_pub + encryption_pub) as app_data on first contact.
 *
 * NOTE: Reticulum's announce format puts the signing public key in the identity
 * (known via prior announce or path cache), not in every announce. For initial
 * discovery, MeshSat embeds both public keys in app_data.
 */
data class RnsAnnounce(
    val encryptionPub: ByteArray,    // 32 bytes (X25519 public key)
    val signingPub: ByteArray,       // 32 bytes (Ed25519 public key, for verification)
    val nameHash: ByteArray,         // 10 bytes
    val randomHash: ByteArray,       // 10 bytes
    val ratchetId: ByteArray?,       // 10 bytes (optional)
    val signature: ByteArray,        // 64 bytes (Ed25519)
    val appData: ByteArray?,         // optional application data
) {
    /**
     * Serialize the announce data for embedding in RnsPacket.data.
     *
     * Wire format:
     *   encryption_pub(32) + name_hash(10) + random_hash(10) +
     *   [ratchet_id(10)] + signature(64) + [app_data(N)]
     */
    fun marshal(): ByteArray {
        var size = RnsConstants.PUB_KEY_LEN + RnsConstants.NAME_HASH_LEN +
            RANDOM_HASH_LEN + RnsConstants.SIG_LEN
        if (ratchetId != null) size += RnsConstants.RATCHET_ID_LEN
        if (appData != null) size += appData.size

        val buf = ByteBuffer.allocate(size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(encryptionPub)
        buf.put(nameHash)
        buf.put(randomHash)
        if (ratchetId != null) buf.put(ratchetId)
        buf.put(signature)
        if (appData != null) buf.put(appData)
        return buf.array()
    }

    /**
     * Compute the hash material that is signed.
     * Covers: dest_hash + encryption_pub + name_hash + random_hash [+ ratchet_id] [+ app_data]
     *
     * The dest_hash is passed separately because it's in the packet header,
     * not in the announce data itself.
     */
    fun hashMaterial(destHash: ByteArray): ByteArray {
        var size = RnsConstants.DEST_HASH_LEN + RnsConstants.PUB_KEY_LEN +
            RnsConstants.NAME_HASH_LEN + RANDOM_HASH_LEN
        if (ratchetId != null) size += RnsConstants.RATCHET_ID_LEN
        if (appData != null) size += appData.size

        val buf = ByteBuffer.allocate(size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(destHash)
        buf.put(encryptionPub)
        buf.put(nameHash)
        buf.put(randomHash)
        if (ratchetId != null) buf.put(ratchetId)
        if (appData != null) buf.put(appData)
        return buf.array()
    }

    /**
     * Verify this announce's signature and destination hash integrity.
     *
     * @param destHash The destination hash from the packet header
     * @return true if signature is valid and dest hash matches the identity
     */
    fun verify(destHash: ByteArray): Boolean {
        // Verify destination hash matches computed hash from keys
        val computedIdentityHash = RnsDestination.identityHash(encryptionPub, signingPub)
        val computedDestHash = RnsDestination.truncatedHash(nameHash + computedIdentityHash)
        if (!computedDestHash.contentEquals(destHash)) return false

        // Verify Ed25519 signature over hash material
        val material = hashMaterial(destHash)
        return try {
            val pubKey = com.cubeos.meshsat.routing.rawToEd25519Public(signingPub)
            com.cubeos.meshsat.routing.Identity.verifyWith(pubKey, material, signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Compute the announce hash for deduplication.
     * announce_hash = SHA-256(dest_hash + random_hash)[:DEST_HASH_LEN]
     */
    fun announceHash(destHash: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(destHash)
        digest.update(randomHash)
        return digest.digest().copyOfRange(0, RnsConstants.DEST_HASH_LEN)
    }

    companion object {
        const val RANDOM_HASH_LEN = 10

        /** Minimum announce data size (no ratchet, no app data). */
        const val MIN_SIZE = RnsConstants.PUB_KEY_LEN + RnsConstants.NAME_HASH_LEN +
            RANDOM_HASH_LEN + RnsConstants.SIG_LEN  // 32 + 10 + 10 + 64 = 116

        /**
         * Create a signed announce.
         *
         * @param identity The local routing identity (for signing)
         * @param encryptionPubRaw Raw 32-byte X25519 public key
         * @param signingPubRaw Raw 32-byte Ed25519 public key
         * @param appName Application name (default: "meshsat")
         * @param aspects Destination aspects
         * @param appData Optional application-specific data
         * @param signFn Signing function: (data) → 64-byte Ed25519 signature
         */
        fun create(
            encryptionPubRaw: ByteArray,
            signingPubRaw: ByteArray,
            appName: String = RnsDestination.APP_NAME,
            aspects: Array<String> = arrayOf(RnsDestination.ASPECT_NODE),
            appData: ByteArray? = null,
            signFn: (ByteArray) -> ByteArray,
        ): Pair<RnsAnnounce, ByteArray> {
            val nHash = RnsDestination.nameHash(appName, *aspects)
            val destHash = RnsDestination.computeDestHash(
                encryptionPubRaw, signingPubRaw, appName, *aspects
            )

            // Random hash: 5 bytes random + 5 bytes timestamp
            val randomPart = ByteArray(5)
            SecureRandom().nextBytes(randomPart)
            val ts = (System.currentTimeMillis() / 1000).toInt()
            val rHash = ByteArray(RANDOM_HASH_LEN)
            System.arraycopy(randomPart, 0, rHash, 0, 5)
            rHash[5] = ((ts shr 24) and 0xFF).toByte()
            rHash[6] = ((ts shr 16) and 0xFF).toByte()
            rHash[7] = ((ts shr 8) and 0xFF).toByte()
            rHash[8] = (ts and 0xFF).toByte()
            rHash[9] = ((ts shr 28) and 0x0F).toByte() // extra precision

            val announce = RnsAnnounce(
                encryptionPub = encryptionPubRaw.copyOf(),
                signingPub = signingPubRaw.copyOf(),
                nameHash = nHash,
                randomHash = rHash,
                ratchetId = null,
                signature = ByteArray(0), // placeholder
                appData = appData,
            )

            // Sign the hash material
            val material = announce.hashMaterial(destHash)
            val sig = signFn(material)

            val signed = announce.copy(signature = sig)
            return signed to destHash
        }

        /**
         * Parse announce data from a received packet's data field.
         *
         * @param data The RnsPacket.data payload
         * @param hasRatchet Whether a ratchet ID is present (known from context or flag)
         */
        fun unmarshal(data: ByteArray, hasRatchet: Boolean = false): RnsAnnounce {
            val minSize = if (hasRatchet) MIN_SIZE + RnsConstants.RATCHET_ID_LEN else MIN_SIZE
            require(data.size >= minSize) { "announce data too short: ${data.size} < $minSize" }

            val buf = ByteBuffer.wrap(data)
            buf.order(ByteOrder.BIG_ENDIAN)

            val encPub = ByteArray(RnsConstants.PUB_KEY_LEN)
            buf.get(encPub)

            val nHash = ByteArray(RnsConstants.NAME_HASH_LEN)
            buf.get(nHash)

            val rHash = ByteArray(RANDOM_HASH_LEN)
            buf.get(rHash)

            var ratchetId: ByteArray? = null
            if (hasRatchet) {
                ratchetId = ByteArray(RnsConstants.RATCHET_ID_LEN)
                buf.get(ratchetId)
            }

            val sig = ByteArray(RnsConstants.SIG_LEN)
            buf.get(sig)

            val appData = if (buf.hasRemaining()) {
                ByteArray(buf.remaining()).also { buf.get(it) }
            } else null

            // We need signingPub for verification — for initial announces,
            // it should be in the appData or known from prior exchange.
            // Return with empty signingPub; caller must populate from context.
            return RnsAnnounce(
                encryptionPub = encPub,
                signingPub = ByteArray(0), // caller must set from appData or identity cache
                nameHash = nHash,
                randomHash = rHash,
                ratchetId = ratchetId,
                signature = sig,
                appData = appData,
            )
        }
    }
}

/**
 * MeshSat-specific app_data format for initial announces.
 *
 * On first contact, MeshSat embeds both public keys and device metadata
 * in the announce app_data field so the receiver can verify the identity
 * without prior knowledge.
 *
 * App data layout:
 *   [0:32]   signing_pub (Ed25519 public key, 32 bytes)
 *   [32:33]  device_type (1 byte: 0x01=bridge, 0x02=android, 0x03=hub)
 *   [33:34]  capabilities flags (1 byte)
 *   [34..]   additional metadata (optional, variable)
 */
object MeshSatAppData {
    const val DEVICE_BRIDGE: Byte = 0x01
    const val DEVICE_ANDROID: Byte = 0x02
    const val DEVICE_HUB: Byte = 0x03

    // Capability flags
    const val CAP_MESH: Byte = 0x01        // Has LoRa/Meshtastic
    const val CAP_SATELLITE: Byte = 0x02   // Has Iridium/Astrocast
    const val CAP_SMS: Byte = 0x04         // Has native SMS
    const val CAP_APRS: Byte = 0x08        // Has AX.25/APRS
    const val CAP_MQTT: Byte = 0x10        // Has MQTT/internet

    /** Minimum app data size (signing key + device type + capabilities). */
    const val MIN_SIZE = RnsConstants.PUB_KEY_LEN + 2  // 34

    fun encode(
        signingPubRaw: ByteArray,
        deviceType: Byte,
        capabilities: Byte,
    ): ByteArray {
        val buf = ByteBuffer.allocate(MIN_SIZE)
        buf.put(signingPubRaw)
        buf.put(deviceType)
        buf.put(capabilities)
        return buf.array()
    }

    data class Decoded(
        val signingPub: ByteArray,
        val deviceType: Byte,
        val capabilities: Byte,
    )

    fun decode(data: ByteArray): Decoded? {
        if (data.size < MIN_SIZE) return null
        val buf = ByteBuffer.wrap(data)
        val sigPub = ByteArray(RnsConstants.PUB_KEY_LEN)
        buf.get(sigPub)
        val devType = buf.get()
        val caps = buf.get()
        return Decoded(sigPub, devType, caps)
    }
}
