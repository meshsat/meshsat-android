package net.meshsat.android.rns

/**
 * An announce (RNS/Destination.py announce, RNS/Identity.py validate_announce).
 * Data field: public key (64) || name hash (10) || random hash (10) ||
 * [ratchet (32), when the header's context flag is set] || signature (64) || app data.
 * The signature covers dest hash || public key || name hash || random hash ||
 * [ratchet] || app data. The random hash is 5 random bytes and a 5-byte big-endian
 * emission time, which transport nodes compare to decide whether a re-announce is
 * newer than the path they hold.
 */
class Announce(
    val destHash: ByteArray,
    var hops: Int,
    val contextFlag: Int,
    var context: Int,
    val publicKey: ByteArray,
    val nameHash: ByteArray,
    val random: ByteArray,
    val ratchet: ByteArray?,
    var signature: ByteArray,
    val appData: ByteArray,
) {
    val encPub: ByteArray get() = publicKey.copyOfRange(0, Rns.PUB_LEN)
    val sigPub: ByteArray get() = publicKey.copyOfRange(Rns.PUB_LEN, Rns.KEY_LEN)

    /** The announced identity, public only. */
    val identity: RnsIdentity by lazy { RnsIdentity.fromPublicBytes(publicKey) }

    fun payload(): ByteArray {
        var out = publicKey + nameHash + random
        if (contextFlag != 0) out += (ratchet ?: ByteArray(Rns.RATCHET_LEN))
        return out + signature + appData
    }

    /** The packet as the originator emits it: HEADER_1, broadcast. */
    fun packet(): ByteArray = Header(
        headerType = Rns.HEADER_1, contextFlag = contextFlag, transportType = Rns.TRANSPORT_BROADCAST,
        destType = Rns.DEST_SINGLE, packetType = Rns.PACKET_ANNOUNCE, hops = hops, destHash = destHash,
        context = context, data = payload(),
    ).pack()

    /**
     * The packet as a transport node rebroadcasts it: HEADER_2 with the transport's
     * IDENTITY hash, transport type TRANSPORT, the given hops and context (NONE, or
     * PATH_RESPONSE when it answers a path request).
     */
    fun transportPacket(transportId: ByteArray, hops: Int, context: Int): ByteArray = Header(
        headerType = Rns.HEADER_2, contextFlag = contextFlag, transportType = Rns.TRANSPORT_TRANSPORT,
        destType = Rns.DEST_SINGLE, packetType = Rns.PACKET_ANNOUNCE, hops = hops, transportId = transportId,
        destHash = destHash, context = context, data = payload(),
    ).pack()

    /** Emission time in unix seconds from the random hash, or 0 when it is not a plausible timestamp. */
    fun emittedAt(): Long = blobTimestamp(random).let { if (it < 1_600_000_000L || it > 4_000_000_000L) 0 else it }

    fun signableBody(): ByteArray {
        var out = destHash + publicKey + nameHash + random
        if (contextFlag != 0) out += (ratchet ?: ByteArray(Rns.RATCHET_LEN))
        return out + appData
    }

    /** Null when valid, otherwise why not. */
    fun verify(): String? {
        if (publicKey.size != Rns.KEY_LEN) return "public key is ${publicKey.size} bytes"
        val computed = Rns.destHash(nameHash, Rns.identityHash(encPub, sigPub))
        if (!computed.contentEquals(destHash)) return "destination hash mismatch"
        if (!RnsIdentity.verifySignature(sigPub, signableBody(), signature)) return "invalid announce signature"
        return null
    }

    companion object {
        /** pubkey(64) + name(10) + random(10) + signature(64). */
        const val MIN_PAYLOAD = Rns.KEY_LEN + Rns.NAME_HASH_LEN + Rns.RANDOM_HASH_LEN + Rns.SIG_LEN   // 148
        const val RATCHET_PAYLOAD = MIN_PAYLOAD + Rns.RATCHET_LEN                                   // 180

        /** The 5-byte big-endian timestamp at the end of a 10-byte random hash. */
        fun blobTimestamp(blob: ByteArray): Long {
            if (blob.size < Rns.RANDOM_HASH_LEN) return 0
            var ts = 0L
            for (i in 5 until 10) ts = (ts shl 8) or (blob[i].toLong() and 0xFF)
            return ts
        }

        /**
         * A signed announce for [name] on [identity], with the ratchet public key when the
         * destination runs ratchets (Destination.announce).
         */
        fun create(identity: RnsIdentity, name: String, appData: ByteArray?, ratchetPub: ByteArray? = null,
                   nowSec: Long = System.currentTimeMillis() / 1000, pathResponse: Boolean = false): Announce {
            val random = ByteArray(Rns.RANDOM_HASH_LEN)
            Rns.randomBytes(5).copyInto(random, 0)
            for (i in 0 until 5) random[5 + i] = ((nowSec shr (8 * (4 - i))) and 0xFF).toByte()
            val a = Announce(
                destHash = identity.destHash(name), hops = 0, contextFlag = if (ratchetPub != null) 1 else 0,
                context = if (pathResponse) Rns.CTX_PATH_RESPONSE else Rns.CTX_NONE,
                publicKey = identity.publicBytes, nameHash = Rns.nameHash(name), random = random,
                ratchet = ratchetPub?.copyOf(), signature = ByteArray(0), appData = appData ?: ByteArray(0),
            )
            a.signature = identity.sign(a.signableBody())
            return a
        }

        /** Parses the data field of an announce packet whose header is already parsed. */
        fun unpackPayload(data: ByteArray, destHash: ByteArray, hops: Int, contextFlag: Int, context: Int = Rns.CTX_NONE): Announce {
            val min = if (contextFlag != 0) RATCHET_PAYLOAD else MIN_PAYLOAD
            if (data.size < min) throw WireException("announce payload ${data.size} bytes, need $min")
            var pos = 0
            val pub = data.copyOfRange(pos, pos + Rns.KEY_LEN); pos += Rns.KEY_LEN
            val name = data.copyOfRange(pos, pos + Rns.NAME_HASH_LEN); pos += Rns.NAME_HASH_LEN
            val random = data.copyOfRange(pos, pos + Rns.RANDOM_HASH_LEN); pos += Rns.RANDOM_HASH_LEN
            var ratchet: ByteArray? = null
            if (contextFlag != 0) { ratchet = data.copyOfRange(pos, pos + Rns.RATCHET_LEN); pos += Rns.RATCHET_LEN }
            val sig = data.copyOfRange(pos, pos + Rns.SIG_LEN); pos += Rns.SIG_LEN
            val app = data.copyOfRange(pos, data.size)
            return Announce(destHash, hops, contextFlag, context, pub, name, random, ratchet, sig, app)
        }

        fun unpackPacket(raw: ByteArray): Announce {
            val h = Header.unpack(raw)
            if (h.packetType != Rns.PACKET_ANNOUNCE) throw WireException("not an announce")
            return unpackPayload(h.data, h.destHash, h.hops, h.contextFlag, h.context)
        }
    }
}
