package com.cubeos.meshsat.routing

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Announce packet for cryptographic path discovery.
 * Wire-format compatible with Go's routing/announce.go.
 *
 * Wire format:
 *   flags(1) + hop_count(1) + context(1) + dest_hash(16) + signing_pub(32) +
 *   encryption_pub(32) + [app_data_len(2) + app_data(N)] + random(16) + signature(64)
 *
 * Minimum size: 163 bytes (no app data).
 */
class Announce(
    var flags: Byte,
    var hopCount: Byte,
    val context: Byte,
    val destHash: ByteArray,           // 16 bytes
    val signingPub: ByteArray,         // 32 bytes (raw Ed25519)
    val encryptionPub: ByteArray,      // 32 bytes (raw X25519)
    val appData: ByteArray?,           // optional
    val random: ByteArray,             // 16 bytes
    var signature: ByteArray,          // 64 bytes (Ed25519)
) {
    /** Serialize to wire format. */
    fun marshal(): ByteArray {
        var size = HEADER_LEN + CONTEXT_LEN + Identity.DEST_HASH_LEN +
            SIGNING_PUB_LEN + ENCRYPTION_PUB_LEN + RANDOM_LEN + SIGNATURE_LEN
        if (appData != null && appData.isNotEmpty()) {
            size += 2 + appData.size
        }

        val buf = ByteBuffer.allocate(size)
        buf.put(flags)
        buf.put(hopCount)
        buf.put(context)
        buf.put(destHash)
        buf.put(signingPub)
        buf.put(encryptionPub)
        if (appData != null && appData.isNotEmpty()) {
            buf.putShort(appData.size.toShort())
            buf.put(appData)
        }
        buf.put(random)
        buf.put(signature)
        return buf.array()
    }

    /** Verify signature and destination hash. */
    fun verify(): Boolean {
        // Verify dest hash matches public keys
        val computed = Identity.computeDestHash(signingPub, encryptionPub)
        if (!computed.contentEquals(destHash)) return false

        // Verify Ed25519 signature over signable body
        val body = signableBody()
        return Identity.verifyWithRaw(signingPub, body, signature)
    }

    /** Increment hop count for relay. Returns false if max hops exceeded. */
    fun incrementHop(): Boolean {
        if (hopCount.toInt() and 0xFF >= MAX_HOPS) return false
        hopCount = (hopCount + 1).toByte()
        return true
    }

    /**
     * Returns the bytes covered by the signature.
     * Includes everything except the signature and the mutable hop count.
     * Hop count is excluded because relays increment it.
     */
    internal fun signableBody(): ByteArray {
        var size = 1 + CONTEXT_LEN + Identity.DEST_HASH_LEN +
            SIGNING_PUB_LEN + ENCRYPTION_PUB_LEN + RANDOM_LEN
        if (appData != null && appData.isNotEmpty()) {
            size += 2 + appData.size
        }

        val buf = ByteBuffer.allocate(size)
        buf.put(flags)  // flags only, NOT hop count
        buf.put(context)
        buf.put(destHash)
        buf.put(signingPub)
        buf.put(encryptionPub)
        if (appData != null && appData.isNotEmpty()) {
            buf.putShort(appData.size.toShort())
            buf.put(appData)
        }
        buf.put(random)
        return buf.array()
    }

    companion object {
        private const val TAG = "Announce"

        // Wire format sizes
        const val HEADER_LEN = 2
        const val CONTEXT_LEN = 1
        const val RANDOM_LEN = 16
        const val SIGNING_PUB_LEN = 32
        const val ENCRYPTION_PUB_LEN = 32
        const val SIGNATURE_LEN = 64

        const val MIN_LEN = HEADER_LEN + CONTEXT_LEN + Identity.DEST_HASH_LEN +
            SIGNING_PUB_LEN + ENCRYPTION_PUB_LEN + RANDOM_LEN + SIGNATURE_LEN  // 163

        // Flag bits
        const val FLAG_IS_ANNOUNCE: Byte = 0x01
        const val FLAG_HAS_APP_DATA: Byte = 0x02
        const val FLAG_HAS_RATCHET: Byte = 0x04  // future

        // Context values
        const val CONTEXT_ANNOUNCE: Byte = 0x01

        // Max hops
        const val MAX_HOPS = 128

        /** Create a signed announce from a routing identity. */
        fun create(identity: Identity, appData: ByteArray? = null): Announce {
            var flags = FLAG_IS_ANNOUNCE
            if (appData != null && appData.isNotEmpty()) {
                flags = (flags.toInt() or FLAG_HAS_APP_DATA.toInt()).toByte()
            }

            val random = ByteArray(RANDOM_LEN)
            SecureRandom().nextBytes(random)

            val announce = Announce(
                flags = flags,
                hopCount = 0,
                context = CONTEXT_ANNOUNCE,
                destHash = identity.destHash.copyOf(),
                signingPub = identity.signingPubRaw.copyOf(),
                encryptionPub = identity.encryptionPubRaw.copyOf(),
                appData = appData,
                random = random,
                signature = ByteArray(0), // placeholder
            )

            announce.signature = identity.sign(announce.signableBody())
            return announce
        }

        /** Parse an announce from wire format. */
        fun unmarshal(data: ByteArray): Announce {
            require(data.size >= MIN_LEN) { "announce too short: ${data.size} < $MIN_LEN" }

            val buf = ByteBuffer.wrap(data)
            val flags = buf.get()
            require(flags.toInt() and FLAG_IS_ANNOUNCE.toInt() != 0) { "not an announce packet" }

            val hopCount = buf.get()
            val context = buf.get()

            val destHash = ByteArray(Identity.DEST_HASH_LEN)
            buf.get(destHash)

            val signingPub = ByteArray(SIGNING_PUB_LEN)
            buf.get(signingPub)

            val encryptionPub = ByteArray(ENCRYPTION_PUB_LEN)
            buf.get(encryptionPub)

            var appData: ByteArray? = null
            if (flags.toInt() and FLAG_HAS_APP_DATA.toInt() != 0) {
                require(buf.remaining() >= 2) { "truncated app data length" }
                val appLen = buf.short.toInt() and 0xFFFF
                require(buf.remaining() >= appLen) { "truncated app data" }
                appData = ByteArray(appLen)
                buf.get(appData)
            }

            require(buf.remaining() >= RANDOM_LEN + SIGNATURE_LEN) { "truncated random or signature" }

            val random = ByteArray(RANDOM_LEN)
            buf.get(random)

            val signature = ByteArray(SIGNATURE_LEN)
            buf.get(signature)

            return Announce(flags, hopCount, context, destHash, signingPub, encryptionPub, appData, random, signature)
        }
    }
}
