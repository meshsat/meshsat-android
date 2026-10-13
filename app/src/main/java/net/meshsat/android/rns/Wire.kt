package net.meshsat.android.rns

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Reticulum wire format, byte for byte as Python RNS 1.5.x writes it
 * (RNS/Packet.py, RNS/Identity.py, RNS/Transport.py, RNS/Cryptography).
 *
 * This is a port of the Bridge's `internal/reticulum`, which is verified against
 * upstream by golden tests; the Kotlin side is verified the same way
 * (`rns/GoldenVectorTest`, `rns/OracleTest`). Nothing in here knows about
 * sockets, radios or the app; the node in [Node] uses it. [MESHSAT-1553]
 *
 * The older `net.meshsat.android.reticulum` package is NOT this format: it omits
 * the context byte unless a context flag is set, reads a 10-byte ratchet and has
 * its own link handshake. It stays for the MeshSat-private traffic that falls
 * through the node (meshsat.node, HeMB, time sync, the Hub relay).
 */
object Rns {
    const val MTU = 500
    const val HEADER_MINSIZE = 19            // flags + hops + dest(16) + context
    const val HEADER_MAXSIZE = 35            // flags + hops + transport(16) + dest(16) + context
    const val IFAC_MIN_SIZE = 1
    const val MDU = MTU - HEADER_MAXSIZE - IFAC_MIN_SIZE   // 464

    const val HASH_LEN = 16                  // truncated hash (destinations, link ids, transport ids)
    const val FULL_HASH_LEN = 32
    const val NAME_HASH_LEN = 10
    const val SIG_LEN = 64
    const val PUB_LEN = 32                   // one x25519 or ed25519 public key
    const val KEY_LEN = 64                   // announce public key: x25519 pub || ed25519 pub
    const val RATCHET_LEN = 32
    const val RANDOM_HASH_LEN = 10           // 5 random + 5 timestamp (big endian seconds)
    const val PATHFINDER_M = 128             // maximum hops
    const val ANNOUNCE_CAP = 2               // percent of an interface's time
    const val TOKEN_OVERHEAD = 48            // iv(16) + hmac(32)
    const val AES_BLOCK = 16
    const val DERIVED_KEY_LEN = 64

    /** Largest plaintext of one encrypted single packet at MTU 500 (RNS/Packet.py ENCRYPTED_MDU). */
    const val ENCRYPTED_MDU = ((MDU - TOKEN_OVERHEAD - PUB_LEN) / AES_BLOCK) * AES_BLOCK - 1   // 383

    // Packet types (flags bits 1..0)
    const val PACKET_DATA = 0x00
    const val PACKET_ANNOUNCE = 0x01
    const val PACKET_LINKREQUEST = 0x02
    const val PACKET_PROOF = 0x03

    // Destination types (flags bits 3..2)
    const val DEST_SINGLE = 0x00
    const val DEST_GROUP = 0x01
    const val DEST_PLAIN = 0x02
    const val DEST_LINK = 0x03

    // Transport types (flags bit 4)
    const val TRANSPORT_BROADCAST = 0x00
    const val TRANSPORT_TRANSPORT = 0x01

    // Header types (flags bit 6)
    const val HEADER_1 = 0x00
    const val HEADER_2 = 0x01

    // Context byte
    const val CTX_NONE = 0x00
    const val CTX_RESOURCE = 0x01
    const val CTX_RESOURCE_ADV = 0x02
    const val CTX_RESOURCE_REQ = 0x03
    const val CTX_RESOURCE_HMU = 0x04
    const val CTX_RESOURCE_PRF = 0x05
    const val CTX_RESOURCE_ICL = 0x06
    const val CTX_RESOURCE_RCL = 0x07
    const val CTX_CACHE_REQUEST = 0x08
    const val CTX_REQUEST = 0x09
    const val CTX_RESPONSE = 0x0A
    const val CTX_PATH_RESPONSE = 0x0B
    const val CTX_COMMAND = 0x0C
    const val CTX_COMMAND_STATUS = 0x0D
    const val CTX_CHANNEL = 0x0E
    const val CTX_KEEPALIVE = 0xFA
    const val CTX_LINKIDENTIFY = 0xFB
    const val CTX_LINKCLOSE = 0xFC
    const val CTX_LINKPROOF = 0xFD
    const val CTX_LRRTT = 0xFE
    const val CTX_LRPROOF = 0xFF

    // Transport timing (RNS/Transport.py, RNS/Link.py), seconds unless named otherwise
    const val PATHFINDER_R = 1               // announce rebroadcast retries
    const val PATHFINDER_G = 5.0             // grace before a rebroadcast
    const val PATHFINDER_RW = 0.5            // random window on top of it
    const val PATHFINDER_E = 60L * 60 * 24 * 7  // path expiry, one week
    const val LOCAL_REBROADCASTS_MAX = 2
    const val PATH_REQUEST_TIMEOUT = 15
    const val PATH_REQUEST_GRACE = 0.4
    const val REVERSE_TIMEOUT = 8 * 60
    const val LINK_TIMEOUT = 60 * 60 * 2
    const val PER_HOP_TIMEOUT = 6
    const val KEEPALIVE_MAX = 360
    const val KEEPALIVE_MIN = 5
    const val STALE_GRACE = 5

    /** The PLAIN destination every transport node listens on for path requests. */
    const val PATH_REQUEST_DEST_NAME = "rnstransport.path.request"

    val PATH_REQUEST_DEST_HASH: ByteArray by lazy { plainDestHash(PATH_REQUEST_DEST_NAME) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    fun truncatedHash(vararg parts: ByteArray): ByteArray = sha256(*parts).copyOf(HASH_LEN)

    /** SHA-256("app.aspect1.aspect2")[:10]. */
    fun nameHash(name: String): ByteArray = sha256(name.toByteArray(Charsets.UTF_8)).copyOf(NAME_HASH_LEN)

    /** SHA-256(x25519 pub || ed25519 pub)[:16]. */
    fun identityHash(encPub: ByteArray, sigPub: ByteArray): ByteArray = truncatedHash(encPub, sigPub)

    /** SHA-256(name hash || identity hash)[:16]. */
    fun destHash(nameHash: ByteArray, identityHash: ByteArray): ByteArray = truncatedHash(nameHash, identityHash)

    /** A PLAIN destination's hash: SHA-256(name hash)[:16], no identity. */
    fun plainDestHash(name: String): ByteArray = truncatedHash(nameHash(name))

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    private val rng = SecureRandom()
}

/** Thrown for bytes that do not parse as the wire format says. */
class WireException(message: String) : Exception(message)

/** Lower-case hex of a byte array, for callers outside this package. */
fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun String.unhex(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> ((Character.digit(this[2 * i], 16) shl 4) or Character.digit(this[2 * i + 1], 16)).toByte() }
}

internal fun ByteArray?.sameAs(other: ByteArray?): Boolean = this != null && other != null && contentEquals(other)

/**
 * A parsed packet header plus its data (RNS/Packet.py). Every packet carries a
 * context byte; the context FLAG (bit 5) is a separate signal, used by announces
 * to say a ratchet is present.
 */
class Header(
    var headerType: Int = Rns.HEADER_1,
    var contextFlag: Int = 0,
    var transportType: Int = Rns.TRANSPORT_BROADCAST,
    var destType: Int = Rns.DEST_SINGLE,
    var packetType: Int = Rns.PACKET_DATA,
    var hops: Int = 0,
    var transportId: ByteArray? = null,
    var destHash: ByteArray,
    var context: Int = Rns.CTX_NONE,
    var data: ByteArray = ByteArray(0),
) {
    /** [7:IFAC][6:header type][5:context flag][4:transport type][3..2:dest type][1..0:packet type]; IFAC is the interface's. */
    fun flags(): Int =
        ((headerType and 1) shl 6) or ((contextFlag and 1) shl 5) or ((transportType and 1) shl 4) or
            ((destType and 3) shl 2) or (packetType and 3)

    fun headerSize(): Int = if (headerType == Rns.HEADER_2) Rns.HEADER_MAXSIZE else Rns.HEADER_MINSIZE

    fun pack(): ByteArray {
        val out = ByteArray(headerSize() + data.size)
        out[0] = flags().toByte()
        out[1] = (hops and 0xFF).toByte()
        var pos = 2
        if (headerType == Rns.HEADER_2) {
            val tid = transportId ?: throw WireException("HEADER_2 packet needs a transport id")
            require(tid.size == Rns.HASH_LEN)
            tid.copyInto(out, pos); pos += Rns.HASH_LEN
        }
        require(destHash.size == Rns.HASH_LEN) { "destination hash must be 16 bytes" }
        destHash.copyInto(out, pos); pos += Rns.HASH_LEN
        out[pos++] = context.toByte()
        data.copyInto(out, pos)
        return out
    }

    companion object {
        /**
         * Parses a raw packet. Like RNS Packet.unpack it rejects a hop count at or
         * above PATHFINDER_M and an empty data field (a peer drops those too).
         */
        fun unpack(raw: ByteArray): Header {
            if (raw.size < Rns.HEADER_MINSIZE) throw WireException("packet too short: ${raw.size}")
            val flags = raw[0].toInt() and 0xFF
            val h = Header(
                headerType = (flags and 0x40) shr 6,
                contextFlag = (flags and 0x20) shr 5,
                transportType = (flags and 0x10) shr 4,
                destType = (flags and 0x0C) shr 2,
                packetType = flags and 0x03,
                hops = raw[1].toInt() and 0xFF,
                destHash = ByteArray(0),
            )
            if (h.hops >= Rns.PATHFINDER_M) throw WireException("hops ${h.hops} at or above PATHFINDER_M")
            var pos = 2
            if (h.headerType == Rns.HEADER_2) {
                if (raw.size < Rns.HEADER_MAXSIZE) throw WireException("HEADER_2 packet too short: ${raw.size}")
                h.transportId = raw.copyOfRange(pos, pos + Rns.HASH_LEN); pos += Rns.HASH_LEN
            }
            h.destHash = raw.copyOfRange(pos, pos + Rns.HASH_LEN); pos += Rns.HASH_LEN
            h.context = raw[pos].toInt() and 0xFF; pos++
            if (pos >= raw.size) throw WireException("zero-length data field")
            h.data = raw.copyOfRange(pos, raw.size)
            return h
        }

        fun unpackOrNull(raw: ByteArray): Header? = try { unpack(raw) } catch (_: WireException) { null }

        /**
         * The bytes RNS hashes: flags masked to dest type and packet type, then
         * everything after the hops byte, skipping a HEADER_2 transport id. The hash
         * so survives the HEADER_1 <-> HEADER_2 rewrite in transport, which is what
         * makes proofs and reverse tables work across hops (Packet.get_hashable_part).
         */
        fun hashablePart(raw: ByteArray): ByteArray {
            if (raw.size < 2) return ByteArray(0)
            val start = if ((raw[0].toInt() and 0x40) != 0) 2 + Rns.HASH_LEN else 2
            if (raw.size < start) return ByteArray(0)
            val out = ByteArray(1 + raw.size - start)
            out[0] = (raw[0].toInt() and 0x0F).toByte()
            raw.copyInto(out, 1, start, raw.size)
            return out
        }

        fun packetHash(raw: ByteArray): ByteArray = Rns.sha256(hashablePart(raw))

        fun truncatedPacketHash(raw: ByteArray): ByteArray = packetHash(raw).copyOf(Rns.HASH_LEN)

        /**
         * Converts a packet to the HEADER_2 form a transport node emits: header type
         * 2, transport type TRANSPORT, the next hop's transport id after the hops byte.
         * The hops byte is left alone; callers set it.
         */
        fun rewriteForTransport(raw: ByteArray, transportId: ByteArray): ByteArray {
            if (raw.size < Rns.HEADER_MINSIZE) return raw
            val f = raw[0].toInt() and 0xFF
            if ((f and 0x40) != 0) {
                val out = raw.copyOf()
                transportId.copyInto(out, 2)
                out[0] = (f or 0x10).toByte()
                return out
            }
            val out = ByteArray(raw.size + Rns.HASH_LEN)
            out[0] = ((f and 0x0F) or (Rns.HEADER_2 shl 6) or (Rns.TRANSPORT_TRANSPORT shl 4) or (f and 0x20)).toByte()
            out[1] = raw[1]
            transportId.copyInto(out, 2)
            raw.copyInto(out, 2 + Rns.HASH_LEN, 2, raw.size)
            return out
        }

        /** The reverse: HEADER_2 back to HEADER_1 broadcast, as a transport does on the last hop. */
        fun stripTransport(raw: ByteArray): ByteArray {
            val f = raw[0].toInt() and 0xFF
            if (raw.size < Rns.HEADER_MAXSIZE || (f and 0x40) == 0) return raw
            val out = ByteArray(raw.size - Rns.HASH_LEN)
            out[0] = ((f and 0x0F) or (f and 0x20)).toByte()
            out[1] = raw[1]
            raw.copyInto(out, 2, 2 + Rns.HASH_LEN, raw.size)
            return out
        }
    }
}

/** HKDF-SHA256 (RFC 5869) as RNS.Cryptography.hkdf: an empty salt is 32 zero bytes, no info by default. */
object Hkdf {
    private const val HASH_LEN = 32

    fun derive(length: Int, ikm: ByteArray, salt: ByteArray? = null, info: ByteArray? = null): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "bad HKDF length $length" }
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val prk = hmac(actualSalt, ikm)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            mac.reset()
            mac.update(prev)
            if (info != null) mac.update(info)
            mac.update(counter.toByte())
            prev = mac.doFinal()
            val n = minOf(HASH_LEN, length - filled)
            prev.copyInto(out, filled, 0, n)
            filled += n
            counter++
        }
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

/**
 * RNS.Cryptography.Token: a Fernet-style token without version and timestamp.
 * iv(16) || AES-CBC(PKCS7(plaintext)) || HMAC-SHA256(iv || ciphertext)(32). A 64-byte
 * key is AES-256 with signing key = key[:32] and encryption key = key[32:]; a
 * 32-byte key is AES-128 with 16-byte halves. The signing half comes FIRST.
 */
class Token(key: ByteArray) {
    private val signingKey: ByteArray
    private val encryptionKey: ByteArray

    init {
        when (key.size) {
            32 -> { signingKey = key.copyOfRange(0, 16); encryptionKey = key.copyOfRange(16, 32) }
            64 -> { signingKey = key.copyOfRange(0, 32); encryptionKey = key.copyOfRange(32, 64) }
            else -> throw WireException("token key must be 32 or 64 bytes, got ${key.size}")
        }
    }

    fun encrypt(data: ByteArray): ByteArray {
        val iv = Rns.randomBytes(Rns.AES_BLOCK)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(data)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(signingKey, "HmacSHA256")) }
        mac.update(iv); mac.update(ct)
        return iv + ct + mac.doFinal()
    }

    fun verifyHmac(token: ByteArray): Boolean {
        if (token.size <= 32) return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(signingKey, "HmacSHA256")) }
        mac.update(token, 0, token.size - 32)
        return MessageDigest.isEqual(token.copyOfRange(token.size - 32, token.size), mac.doFinal())
    }

    /** Verifies the HMAC and decrypts, or throws [WireException]. */
    fun decrypt(token: ByteArray): ByteArray {
        if (token.size < Rns.AES_BLOCK + Rns.AES_BLOCK + 32) throw WireException("token too short (${token.size} bytes)")
        if (!verifyHmac(token)) throw WireException("token HMAC invalid")
        val iv = token.copyOfRange(0, Rns.AES_BLOCK)
        val ct = token.copyOfRange(Rns.AES_BLOCK, token.size - 32)
        if (ct.size % Rns.AES_BLOCK != 0) throw WireException("token ciphertext not block aligned")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        return try { cipher.doFinal(ct) } catch (e: Exception) { throw WireException("token padding invalid") }
    }
}

/**
 * Interface access codes (Transport.handle_outgoing_ifac / handle_ifac, Reticulum.IFAC_SALT).
 * Two peers with the same network name and/or passphrase derive one 64-byte key whose
 * second half seeds an Ed25519 signing key. Every packet carries the last [size] bytes
 * of the signature over the clean packet after the hops byte, and everything but the
 * tag is XOR-masked with HKDF(tag, salt = key). Bit 7 of the flags marks an IFAC packet.
 */
class Ifac(netname: String, netkey: String, val size: Int = DEFAULT_SIZE) {
    val key: ByteArray
    private val signer: RnsIdentity

    init {
        if (netname.isEmpty() && netkey.isEmpty()) throw WireException("IFAC needs a network name or passphrase")
        if (size !in Rns.IFAC_MIN_SIZE..64) throw WireException("IFAC size must be 1..64")
        key = ifacKey(netname, netkey)
        signer = RnsIdentity.fromPrivateBytes(key)
    }

    fun wrap(raw: ByteArray): ByteArray {
        if (raw.size < 2) return raw
        val sig = signer.sign(raw)
        val tag = sig.copyOfRange(sig.size - size, sig.size)
        val mask = Hkdf.derive(raw.size + size, tag, key)
        val out = ByteArray(raw.size + size)
        out[0] = (raw[0].toInt() or 0x80).toByte()
        out[1] = raw[1]
        tag.copyInto(out, 2)
        raw.copyInto(out, 2 + size, 2, raw.size)
        for (i in out.indices) {
            if (i >= 2 && i < 2 + size) continue
            out[i] = (out[i].toInt() xor mask[i].toInt()).toByte()
        }
        out[0] = (out[0].toInt() or 0x80).toByte()
        return out
    }

    /** Verifies and strips the access code, or throws [WireException]. */
    fun unwrap(raw: ByteArray): ByteArray {
        if (raw.size <= 2 + size) throw WireException("IFAC packet too short")
        if ((raw[0].toInt() and 0x80) == 0) throw WireException("IFAC flag missing")
        val tag = raw.copyOfRange(2, 2 + size)
        val mask = Hkdf.derive(raw.size, tag, key)
        val unmasked = ByteArray(raw.size)
        for (i in raw.indices) {
            unmasked[i] = if (i >= 2 && i < 2 + size) raw[i] else (raw[i].toInt() xor mask[i].toInt()).toByte()
        }
        val clean = ByteArray(raw.size - size)
        clean[0] = (unmasked[0].toInt() and 0x7F).toByte()
        clean[1] = unmasked[1]
        unmasked.copyInto(clean, 2, 2 + size, unmasked.size)
        val sig = signer.sign(clean)
        if (!MessageDigest.isEqual(sig.copyOfRange(sig.size - size, sig.size), tag)) throw WireException("IFAC tag invalid")
        return clean
    }

    companion object {
        const val DEFAULT_SIZE = 16
        val SALT: ByteArray = "adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8".unhex()

        fun ifacKey(netname: String, netkey: String): ByteArray {
            var origin = ByteArray(0)
            if (netname.isNotEmpty()) origin += Rns.sha256(netname.toByteArray(Charsets.UTF_8))
            if (netkey.isNotEmpty()) origin += Rns.sha256(netkey.toByteArray(Charsets.UTF_8))
            return Hkdf.derive(64, Rns.sha256(origin), SALT)
        }

        fun hasFlag(raw: ByteArray): Boolean = raw.isNotEmpty() && (raw[0].toInt() and 0x80) != 0
    }
}

/** HDLC-like framing of RNS TCP and serial interfaces (RNS/Interfaces/TCPInterface.py HDLC). */
object Hdlc {
    const val FLAG: Byte = 0x7E
    const val ESC: Byte = 0x7D
    const val ESC_MASK: Int = 0x20

    fun escape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + data.size / 4 + 2)
        for (b in data) {
            when (b) {
                ESC -> { out.write(ESC.toInt()); out.write(ESC.toInt() xor ESC_MASK) }
                FLAG -> { out.write(ESC.toInt()); out.write(FLAG.toInt() xor ESC_MASK) }
                else -> out.write(b.toInt())
            }
        }
        return out.toByteArray()
    }

    fun unescape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            if (data[i] == ESC && i + 1 < data.size) {
                out.write(data[i + 1].toInt() xor ESC_MASK); i += 2
            } else {
                out.write(data[i].toInt()); i++
            }
        }
        return out.toByteArray()
    }

    fun frame(data: ByteArray): ByteArray {
        val esc = escape(data)
        val out = ByteArray(esc.size + 2)
        out[0] = FLAG
        esc.copyInto(out, 1)
        out[out.size - 1] = FLAG
        return out
    }

    /** Pulls complete frames out of a byte stream; frames shorter than a header are dropped. */
    class FrameReader {
        private var buf = ByteArray(0)

        fun feed(data: ByteArray): List<ByteArray> {
            buf += data
            val frames = ArrayList<ByteArray>()
            while (true) {
                val start = buf.indexOf(FLAG)
                if (start < 0) { buf = ByteArray(0); break }
                var end = -1
                for (i in start + 1 until buf.size) if (buf[i] == FLAG) { end = i; break }
                if (end < 0) { buf = buf.copyOfRange(start, buf.size); break }
                val escaped = buf.copyOfRange(start + 1, end)
                if (escaped.size >= Rns.HEADER_MINSIZE) frames.add(unescape(escaped))
                buf = buf.copyOfRange(end, buf.size)
            }
            return frames
        }
    }
}

/**
 * Packet proofs (Identity.prove, Packet.PacketReceipt). A proof for a packet to a
 * SINGLE destination is a PROOF packet to the truncated hash of the proved packet,
 * data = signature over the packet hash (implicit, the RNS default) or
 * hash(32) || signature (explicit). Link proofs are always explicit and signed by
 * the link's signing key.
 */
object Proof {
    const val IMPLICIT_LEN = Rns.SIG_LEN
    const val EXPLICIT_LEN = Rns.FULL_HASH_LEN + Rns.SIG_LEN

    fun packet(provedRaw: ByteArray, proofData: ByteArray): ByteArray = Header(
        headerType = Rns.HEADER_1, destType = Rns.DEST_SINGLE, packetType = Rns.PACKET_PROOF,
        destHash = Header.truncatedPacketHash(provedRaw), context = Rns.CTX_NONE, data = proofData,
    ).pack()

    /** True when proofData proves packetHash under sigPub, in either form (PacketReceipt.validate_proof). */
    fun validate(proofData: ByteArray, packetHash: ByteArray, sigPub: ByteArray): Boolean {
        val sig = when (proofData.size) {
            IMPLICIT_LEN -> proofData
            EXPLICIT_LEN -> {
                if (!proofData.copyOfRange(0, Rns.FULL_HASH_LEN).contentEquals(packetHash)) return false
                proofData.copyOfRange(Rns.FULL_HASH_LEN, EXPLICIT_LEN)
            }
            else -> return false
        }
        return RnsIdentity.verifySignature(sigPub, packetHash, sig)
    }
}
