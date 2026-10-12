package net.meshsat.android.rns

/**
 * The link handshake wire format (RNS/Link.py): request, proof, signalling bytes,
 * the RTT packet, identification and keepalives.
 */
object LinkWire {
    const val LINK_ID_LEN = Rns.HASH_LEN
    const val EC_PUB_SIZE = 64               // initiator x25519 pub (32) || ed25519 pub (32)
    const val MTU_SIZE = 3                   // the signalling bytes
    const val MTU_BYTEMASK = 0x1FFFFF
    const val MODE_BYTEMASK = 0xE0
    const val MODE_AES128_CBC = 0x00
    const val MODE_AES256_CBC = 0x01         // the only mode RNS 1.5.x enables
    const val MODE_DEFAULT = MODE_AES256_CBC
    const val KEEPALIVE_INITIATOR = 0xFF
    const val KEEPALIVE_RESPONSE = 0xFE

    /** Largest plaintext of one link packet at an MTU: floor((mtu - 1 - 19 - 48) / 16) * 16 - 1, 431 at 500. */
    fun mdu(mtu: Int): Int = ((mtu - Rns.IFAC_MIN_SIZE - Rns.HEADER_MINSIZE - Rns.TOKEN_OVERHEAD) / Rns.AES_BLOCK) * Rns.AES_BLOCK - 1

    /** big-endian uint32((mtu & 0x1FFFFF) | (((mode << 5) & 0xE0) << 16))[1:]. */
    fun signalling(mtu: Int, mode: Int): ByteArray {
        val v = (mtu and MTU_BYTEMASK) or (((mode shl 5) and MODE_BYTEMASK) shl 16)
        return byteArrayOf(((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    /** (mtu, mode) from signalling bytes; (500, default) when absent. */
    fun parseSignalling(s: ByteArray, offset: Int = 0): Pair<Int, Int> {
        if (s.size < offset + MTU_SIZE) return Rns.MTU to MODE_DEFAULT
        val b0 = s[offset].toInt() and 0xFF
        val mtu = ((b0 shl 16) or ((s[offset + 1].toInt() and 0xFF) shl 8) or (s[offset + 2].toInt() and 0xFF)) and MTU_BYTEMASK
        return mtu to ((b0 and MODE_BYTEMASK) shr 5)
    }

    /** The data of a LINKREQUEST packet: eph x25519 pub || eph ed25519 pub || [signalling]. */
    class Request(val ephPub: ByteArray, val ephSigPub: ByteArray, val mtu: Int = Rns.MTU, val mode: Int = MODE_DEFAULT, val signalled: Boolean = true) {
        fun pack(): ByteArray = ephPub + ephSigPub + signalling(mtu, mode)

        companion object {
            fun unpack(data: ByteArray): Request {
                if (data.size != EC_PUB_SIZE && data.size != EC_PUB_SIZE + MTU_SIZE) throw WireException("link request must be 64 or 67 bytes, got ${data.size}")
                val eph = data.copyOfRange(0, Rns.PUB_LEN)
                val sig = data.copyOfRange(Rns.PUB_LEN, EC_PUB_SIZE)
                var mtu = Rns.MTU; var mode = MODE_DEFAULT; var signalled = false
                if (data.size == EC_PUB_SIZE + MTU_SIZE) { val (m, md) = parseSignalling(data, EC_PUB_SIZE); mtu = m; mode = md; signalled = true }
                if (mode != MODE_AES256_CBC) throw WireException("link mode $mode not enabled")
                return Request(eph, sig, mtu, mode, signalled)
            }
        }
    }

    /**
     * The link id: the truncated hash of the request packet's hashable part with the
     * signalling bytes removed (Link.link_id_from_lr_packet).
     */
    fun linkIdFromRequestPacket(raw: ByteArray): ByteArray {
        val h = Header.unpack(raw)
        var hashable = Header.hashablePart(raw)
        val diff = h.data.size - EC_PUB_SIZE
        if (diff > 0) hashable = hashable.copyOf(hashable.size - diff)
        return Rns.sha256(hashable).copyOf(LINK_ID_LEN)
    }

    /** The data of the LRPROOF packet: signature (64) || responder eph x25519 pub (32) || [signalling]. */
    class ProofData(val signature: ByteArray, val ephPub: ByteArray, val mtu: Int = Rns.MTU, val mode: Int = MODE_DEFAULT, val signalled: Boolean = true) {
        fun pack(): ByteArray = signature + ephPub + signalling(mtu, mode)

        companion object {
            fun unpack(data: ByteArray): ProofData {
                val base = Rns.SIG_LEN + Rns.PUB_LEN
                if (data.size != base && data.size != base + MTU_SIZE) throw WireException("link proof must be 96 or 99 bytes, got ${data.size}")
                val sig = data.copyOfRange(0, Rns.SIG_LEN)
                val eph = data.copyOfRange(Rns.SIG_LEN, base)
                var mtu = Rns.MTU; var mode = MODE_DEFAULT; var signalled = false
                if (data.size == base + MTU_SIZE) { val (m, md) = parseSignalling(data, base); mtu = m; mode = md; signalled = true }
                return ProofData(sig, eph, mtu, mode, signalled)
            }
        }
    }

    /** What the responder's IDENTITY signs: link id || responder eph pub || responder identity ed25519 pub || signalling. */
    fun proofSignedData(linkId: ByteArray, ephPub: ByteArray, identitySigPub: ByteArray, mtu: Int, mode: Int, signalled: Boolean = true): ByteArray {
        val base = linkId + ephPub + identitySigPub
        return if (signalled) base + signalling(mtu, mode) else base
    }

    /** The link key: HKDF(64, shared, salt = link id). */
    fun deriveLinkKey(shared: ByteArray, linkId: ByteArray): ByteArray = Hkdf.derive(Rns.DERIVED_KEY_LEN, shared, linkId)

    /** The LRRTT plaintext: the RTT in seconds as msgpack float64. */
    fun rttData(rttSeconds: Double): ByteArray = Msgpack.pack(MpFloat(rttSeconds))

    fun parseRttData(b: ByteArray): Double? {
        val v = try { Msgpack.unpack(b).first } catch (_: Exception) { return null }
        return when (v) {
            is MpFloat -> v.value
            is MpInt -> v.value.toDouble()
            else -> null
        }
    }

    /** The LINKIDENTIFY plaintext: public key (64) || sign(link id || public key). */
    fun identifyData(identity: RnsIdentity, linkId: ByteArray): ByteArray {
        val pub = identity.publicBytes
        return pub + identity.sign(linkId + pub)
    }

    /** Verifies a LINKIDENTIFY plaintext and returns the identified 64-byte public key, or null. */
    fun parseIdentify(data: ByteArray, linkId: ByteArray): ByteArray? {
        if (data.size != Rns.KEY_LEN + Rns.SIG_LEN) return null
        val pub = data.copyOfRange(0, Rns.KEY_LEN)
        val sigPub = pub.copyOfRange(Rns.PUB_LEN, Rns.KEY_LEN)
        if (!RnsIdentity.verifySignature(sigPub, linkId + pub, data.copyOfRange(Rns.KEY_LEN, data.size))) return null
        return pub
    }
}
