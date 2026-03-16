package com.cubeos.meshsat.routing

import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages link establishment via 3-packet ECDH handshake, tracking, and data encryption.
 *
 * Handshake flow:
 *   1. Initiator → [LinkRequest] → Responder
 *   2. Responder → [LinkResponse] (signed) → Initiator
 *   3. Initiator → [LinkConfirm] (proof) → Responder
 *
 * After handshake, both sides have derived AES-256-GCM send/recv keys.
 *
 * Port of Go's routing/link.go (LinkManager).
 */
class LinkManager(private val identity: Identity) {

    private val lock = ReentrantReadWriteLock()
    private val links = HashMap<String, Link>()    // linkIdHex -> established link
    private val pending = HashMap<String, Link>()   // linkIdHex -> pending link request

    /**
     * Create a link request to the given destination.
     * Returns the serialized request packet and the pending link.
     */
    fun initiateLink(destHash: ByteArray): Pair<ByteArray, Link> {
        val ephKp = generateX25519KeyPair()
        val ephPubRaw = extractRaw(ephKp.public)

        val random = ByteArray(16)
        SecureRandom().nextBytes(random)

        val req = LinkRequest(
            destHash = destHash.copyOf(),
            ephemeralPubRaw = ephPubRaw,
            random = random,
        )
        val linkId = req.computeLinkId()
        val now = System.currentTimeMillis()

        val link = Link(
            id = linkId,
            destHash = destHash.copyOf(),
            state = LinkState.PENDING,
            localEphPrivate = ephKp.private,
            localEphPublic = ephKp.public,
            createdAt = now,
            lastActivity = now,
            isInitiator = true,
        )

        val key = linkId.toHex()
        lock.write { pending[key] = link }

        Log.i(TAG, "Link request initiated: ${link.idHex} dest=${destHash.toHex()}")
        return req.marshal() to link
    }

    /**
     * Process an incoming link request addressed to us.
     * Returns the serialized response packet, or throws if rejected.
     */
    fun handleLinkRequest(data: ByteArray): ByteArray {
        val req = LinkRequest.unmarshal(data)

        // Verify this request is addressed to us
        require(req.destHash.contentEquals(identity.destHash)) {
            "link request not addressed to us"
        }

        val linkId = req.computeLinkId()

        // Generate our ephemeral X25519 key
        val ephKp = generateX25519KeyPair()
        val ephPubRaw = extractRaw(ephKp.public)

        // ECDH: our ephemeral x their ephemeral
        val remotePub = rawToX25519Public(req.ephemeralPubRaw)
        val sharedSecret = performEcdh(ephKp.private, remotePub)

        // Sign: link_id + our ephemeral pub
        val signable = linkId + ephPubRaw
        val signature = identity.sign(signable)

        // Derive symmetric keys (responder: key1=recv, key2=send)
        val (key1, key2) = deriveSymKeys(sharedSecret, linkId)

        val now = System.currentTimeMillis()
        val link = Link(
            id = linkId,
            destHash = ByteArray(Identity.DEST_HASH_LEN), // initiator identity unknown
            state = LinkState.ESTABLISHED,
            localEphPrivate = ephKp.private,
            localEphPublic = ephKp.public,
            remoteEphPublic = remotePub,
            sharedSecret = sharedSecret,
            recvKey = key1,   // responder receives on key1
            sendKey = key2,   // responder sends on key2
            createdAt = now,
            lastActivity = now,
            isInitiator = false,
        )

        val idKey = linkId.toHex()
        lock.write { links[idKey] = link }

        val resp = LinkResponse(
            linkId = linkId,
            ephemeralPubRaw = ephPubRaw,
            signature = signature,
        )

        Log.i(TAG, "Link request accepted: ${link.idHex}")
        return resp.marshal()
    }

    /**
     * Process an incoming link response to our pending request.
     * [signingPubRaw] is the destination's known Ed25519 public key (from destination table).
     * Returns the serialized confirm packet.
     */
    fun handleLinkResponse(data: ByteArray, signingPubRaw: ByteArray): ByteArray {
        val resp = LinkResponse.unmarshal(data)
        val idKey = resp.linkId.toHex()

        val link = lock.write {
            val l = pending.remove(idKey) ?: error("no pending link for this ID")
            l
        }

        // Verify signature: Ed25519(signing_key, link_id + ephemeral_pub)
        val signable = resp.linkId + resp.ephemeralPubRaw
        require(Identity.verifyWithRaw(signingPubRaw, signable, resp.signature)) {
            "link response signature verification failed"
        }

        // ECDH: our ephemeral x their ephemeral
        val remotePub = rawToX25519Public(resp.ephemeralPubRaw)
        val sharedSecret = performEcdh(link.localEphPrivate!!, remotePub)

        // Derive symmetric keys (initiator: key1=send, key2=recv)
        val (key1, key2) = deriveSymKeys(sharedSecret, resp.linkId)

        link.remoteEphPublic = remotePub
        link.sharedSecret = sharedSecret
        link.sendKey = key1   // initiator sends on key1
        link.recvKey = key2   // initiator receives on key2
        link.state = LinkState.ESTABLISHED
        link.lastActivity = System.currentTimeMillis()

        lock.write { links[idKey] = link }

        // Build confirm proof
        val proof = computeConfirmProof(sharedSecret, resp.linkId)
        val confirm = LinkConfirm(linkId = resp.linkId, proof = proof)

        Log.i(TAG, "Link established (initiator): ${link.idHex}")
        return confirm.marshal()
    }

    /**
     * Process an incoming link confirmation.
     * Verifies the proof matches the shared secret.
     */
    fun handleLinkConfirm(data: ByteArray) {
        val confirm = LinkConfirm.unmarshal(data)
        val idKey = confirm.linkId.toHex()

        val link = lock.read { links[idKey] } ?: error("no link for this ID")
        check(link.state == LinkState.ESTABLISHED) { "link not in established state" }

        val expected = computeConfirmProof(link.sharedSecret!!, confirm.linkId)
        require(confirm.proof.contentEquals(expected)) { "link confirm proof mismatch" }

        link.lastActivity = System.currentTimeMillis()
        Log.i(TAG, "Link confirmed (responder): ${link.idHex}")
    }

    /** Get an established link by ID bytes. */
    fun getLink(linkId: ByteArray): Link? = lock.read { links[linkId.toHex()] }

    /** Get a pending link by ID bytes. */
    fun getPendingLink(linkId: ByteArray): Link? = lock.read { pending[linkId.toHex()] }

    /** All currently established links. */
    fun activeLinks(): List<Link> = lock.read {
        links.values.filter { it.state == LinkState.ESTABLISHED }
    }

    /** Close a link by ID. */
    fun closeLink(linkId: ByteArray) {
        val key = linkId.toHex()
        lock.write {
            links[key]?.state = LinkState.CLOSED
            links.remove(key)
            pending.remove(key)
        }
    }

    companion object {
        private const val TAG = "LinkManager"
    }
}
