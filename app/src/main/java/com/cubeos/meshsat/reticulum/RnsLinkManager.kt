package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.routing.Identity
import com.cubeos.meshsat.routing.extractRaw
import com.cubeos.meshsat.routing.generateX25519KeyPair
import com.cubeos.meshsat.routing.performEcdh
import com.cubeos.meshsat.routing.rawToX25519Public
import com.cubeos.meshsat.routing.toHex
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Reticulum-compatible link manager.
 *
 * Performs 2-packet ECDH handshake (Reticulum style):
 *   1. Initiator → LINKREQUEST packet → Responder
 *   2. Responder → PROOF packet (signed) → Initiator
 *
 * After ECDH, symmetric keys are derived via HKDF with link_id as salt.
 *
 * Key differences from legacy LinkManager:
 * - Link ID is 16 bytes (truncated hash) instead of 32
 * - All packets framed in RnsPacket (2-byte header + dest hash)
 * - Key derivation uses HKDF instead of SHA-256
 * - Supports mode negotiation via signalling bytes
 * - No separate confirm packet (Reticulum uses LRRTT context instead)
 */
class RnsLinkManager(
    private val identity: Identity,
    private val localDestHash: ByteArray,
) {
    private val lock = ReentrantReadWriteLock()
    private val links = HashMap<String, RnsLink>()
    private val pending = HashMap<String, PendingLink>()

    /** Ephemeral state for a pending outbound link request. */
    private class PendingLink(
        val linkId: ByteArray,
        val destHash: ByteArray,
        val ephPrivate: PrivateKey,
        val ephPublic: PublicKey,
        val signalling: RnsSignalling,
        val createdAt: Long = System.currentTimeMillis(),
    )

    /**
     * Create a link request to the given destination.
     *
     * @param destHash Target node's 16-byte Reticulum destination hash
     * @param encryptionMode Preferred encryption mode
     * @return Serialized RnsPacket (LINKREQUEST) ready to send
     */
    fun initiateLink(
        destHash: ByteArray,
        encryptionMode: RnsEncryptionMode = RnsEncryptionMode.AES_256_GCM,
    ): ByteArray {
        val ephKp = generateX25519KeyPair()
        val ephPubRaw = extractRaw(ephKp.public)

        val signalling = RnsSignalling(encryptionMode = encryptionMode)

        val reqData = RnsLinkRequest(
            ephemeralPub = ephPubRaw,
            signingPub = identity.signingPubRaw,
            signalling = signalling,
        )

        // Frame as RnsPacket LINKREQUEST
        val packet = RnsPacket.linkRequest(destHash, reqData.marshal())

        // Compute link ID = truncated_hash(packet data without transport headers)
        // Per Reticulum: link_id = truncated_hash(hashable_part_of_request)
        val linkId = computeLinkId(reqData.marshal())

        val pendingLink = PendingLink(
            linkId = linkId,
            destHash = destHash.copyOf(),
            ephPrivate = ephKp.private,
            ephPublic = ephKp.public,
            signalling = signalling,
        )

        lock.write { pending[linkId.toHex()] = pendingLink }
        Log.i(TAG, "Link request initiated: ${linkId.toHex()} dest=${destHash.toHex()}")

        return packet.marshal()
    }

    /**
     * Process an incoming link request addressed to us.
     *
     * @param raw Raw RnsPacket bytes
     * @return Serialized RnsPacket (PROOF) to send back, or null if rejected
     */
    fun handleLinkRequest(raw: ByteArray): ByteArray? {
        val packet = RnsPacket.unmarshal(raw)
        if (packet.packetType != RnsConstants.PACKET_LINKREQUEST) return null

        // Verify addressed to us
        if (!packet.destHash.contentEquals(localDestHash)) {
            Log.d(TAG, "Link request not for us: ${packet.destHash.toHex()}")
            return null
        }

        val reqData = RnsLinkRequest.unmarshal(packet.data)

        // Compute link ID from request data
        val linkId = computeLinkId(packet.data)

        // Check not already established
        if (lock.read { links.containsKey(linkId.toHex()) }) {
            Log.d(TAG, "Link already exists: ${linkId.toHex()}")
            return null
        }

        // Generate our ephemeral X25519 key
        val ephKp = generateX25519KeyPair()
        val ephPubRaw = extractRaw(ephKp.public)

        // ECDH: our ephemeral × their ephemeral
        val remotePub = rawToX25519Public(reqData.ephemeralPub)
        val sharedSecret = performEcdh(ephKp.private, remotePub)

        // Negotiate encryption mode (use initiator's preference if we support it)
        val mode = reqData.signalling.encryptionMode
        val respSignalling = RnsSignalling(encryptionMode = mode)

        // Sign: link_id + our ephemeral pub + our signing pub + signalling
        val signable = linkId + ephPubRaw + identity.signingPubRaw + respSignalling.marshal()
        val signature = identity.sign(signable)

        // Derive keys via HKDF
        val (sendKey, recvKey) = RnsHkdf.deriveLinkKeys(
            sharedSecret = sharedSecret,
            salt = linkId,
            isInitiator = false,
        )

        val link = RnsLink(
            id = linkId,
            destHash = ByteArray(RnsConstants.DEST_HASH_LEN), // initiator dest unknown until identify
            state = RnsLinkState.ACTIVE,
            encryptionMode = mode,
            sharedSecret = sharedSecret,
            sendKey = sendKey,
            recvKey = recvKey,
            isInitiator = false,
        )

        lock.write { links[linkId.toHex()] = link }

        // Build proof packet — addressed to the link_id (not the destination)
        val proofData = RnsLinkProof(
            signature = signature,
            ephemeralPub = ephPubRaw,
            signalling = respSignalling,
        )

        // Proof is sent as a PROOF packet with dest_hash = link_id padded/truncated
        val proofPacket = RnsPacket.proof(linkId, proofData.marshal())

        Log.i(TAG, "Link request accepted: ${linkId.toHex()}")
        return proofPacket.marshal()
    }

    /**
     * Process an incoming link proof (response to our request).
     *
     * @param raw Raw RnsPacket bytes
     * @param signingPubRaw Remote node's Ed25519 public key (from destination table)
     * @return The established RnsLink, or null if verification failed
     */
    fun handleLinkProof(raw: ByteArray, signingPubRaw: ByteArray): RnsLink? {
        val packet = RnsPacket.unmarshal(raw)
        if (packet.packetType != RnsConstants.PACKET_PROOF) return null

        val proofData = RnsLinkProof.unmarshal(packet.data)

        // The dest_hash of the proof packet IS the link_id
        val linkIdHex = packet.destHash.toHex()

        val pendingLink = lock.write { pending.remove(linkIdHex) }
        if (pendingLink == null) {
            Log.d(TAG, "No pending link for proof: $linkIdHex")
            return null
        }

        // Verify signature: Ed25519(signing_key, link_id + ephemeral_pub + sig_pub + signalling)
        val signable = pendingLink.linkId + proofData.ephemeralPub +
            signingPubRaw + proofData.signalling.marshal()
        if (!Identity.verifyWithRaw(signingPubRaw, signable, proofData.signature)) {
            Log.w(TAG, "Link proof signature verification failed: $linkIdHex")
            return null
        }

        // ECDH: our ephemeral × their ephemeral
        val remotePub = rawToX25519Public(proofData.ephemeralPub)
        val sharedSecret = performEcdh(pendingLink.ephPrivate, remotePub)

        // Negotiate encryption mode
        val mode = proofData.signalling.encryptionMode

        // Derive keys via HKDF
        val (sendKey, recvKey) = RnsHkdf.deriveLinkKeys(
            sharedSecret = sharedSecret,
            salt = pendingLink.linkId,
            isInitiator = true,
        )

        val link = RnsLink(
            id = pendingLink.linkId,
            destHash = pendingLink.destHash,
            state = RnsLinkState.ACTIVE,
            encryptionMode = mode,
            sharedSecret = sharedSecret,
            sendKey = sendKey,
            recvKey = recvKey,
            isInitiator = true,
        )

        lock.write { links[linkIdHex] = link }
        Log.i(TAG, "Link established (initiator): $linkIdHex")
        return link
    }

    /** Get an active link by link ID. */
    fun getLink(linkId: ByteArray): RnsLink? = lock.read { links[linkId.toHex()] }

    /** All currently active links. */
    fun activeLinks(): List<RnsLink> = lock.read {
        links.values.filter { it.state == RnsLinkState.ACTIVE }
    }

    /** Close a link by ID. */
    fun closeLink(linkId: ByteArray) {
        val key = linkId.toHex()
        lock.write {
            links[key]?.state = RnsLinkState.CLOSED
            links.remove(key)
            pending.remove(key)
        }
    }

    /** Number of pending + active links. */
    fun linkCount(): Pair<Int, Int> = lock.read { pending.size to links.size }

    companion object {
        private const val TAG = "RnsLinkManager"

        /**
         * Compute link ID from link request data.
         * link_id = truncated_hash(request_data) — 16 bytes.
         */
        fun computeLinkId(requestData: ByteArray): ByteArray {
            return RnsDestination.truncatedHash(requestData)
        }
    }
}
