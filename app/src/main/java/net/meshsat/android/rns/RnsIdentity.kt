package net.meshsat.android.rns

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * An RNS identity (RNS/Identity.py): an X25519 encryption key and an Ed25519
 * signing key. Public key bytes are x25519 pub (32) || ed25519 pub (32); private
 * bytes, as Identity.from_bytes takes them, x25519 private (32) || ed25519 seed (32).
 *
 * Built on BouncyCastle's lightweight API so it needs no JCA provider order and
 * runs the same on the phone and in JVM tests. The app's routing identity (JCA keys
 * in the secure key store) converts with [fromRouting], keeping the keys and so the
 * hashes the phone already announces.
 */
class RnsIdentity private constructor(
    private val encPriv: X25519PrivateKeyParameters?,
    private val encPub: X25519PublicKeyParameters,
    private val sigPriv: Ed25519PrivateKeyParameters?,
    private val sigPub: Ed25519PublicKeyParameters,
) {
    val encPubRaw: ByteArray = encPub.encoded
    val sigPubRaw: ByteArray = sigPub.encoded

    /** The 64-byte announce public key. */
    val publicBytes: ByteArray = encPubRaw + sigPubRaw

    /** SHA-256(public key)[:16], the transport id of a node running on this identity. */
    val hash: ByteArray = Rns.identityHash(encPubRaw, sigPubRaw)
    val hashHex: String get() = hash.hex()

    val hasPrivateKey: Boolean get() = encPriv != null && sigPriv != null

    /** x25519 private (32) || ed25519 seed (32), RNS Identity.get_private_key order. */
    fun privateBytes(): ByteArray {
        val e = encPriv ?: throw IllegalStateException("identity holds no private key")
        val s = sigPriv ?: throw IllegalStateException("identity holds no private key")
        return e.encoded + s.encoded
    }

    fun sign(data: ByteArray): ByteArray {
        val s = sigPriv ?: throw IllegalStateException("identity cannot sign")
        val signer = Ed25519Signer()
        signer.init(true, s)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun verify(data: ByteArray, signature: ByteArray): Boolean = verifySignature(sigPub, data, signature)

    fun destHash(name: String): ByteArray = Rns.destHash(Rns.nameHash(name), hash)

    /**
     * The data field of an encrypted single packet to this identity: ephemeral x25519
     * pub (32) || Token(HKDF(64, shared, salt = identity hash)). With [ratchetPub] the
     * ECDH runs against the destination's latest announced ratchet instead of its
     * identity key (Identity.encrypt).
     */
    fun encrypt(plaintext: ByteArray, ratchetPub: ByteArray? = null): ByteArray {
        val eph = X25519PrivateKeyParameters(rng)
        val target = if (ratchetPub != null) X25519PublicKeyParameters(ratchetPub, 0) else encPub
        val shared = agree(eph, target)
        val tok = Token(Hkdf.derive(Rns.DERIVED_KEY_LEN, shared, hash))
        return eph.generatePublicKey().encoded + tok.encrypt(plaintext)
    }

    /**
     * Reverses [encrypt]. [ratchetPrivs] are this identity's own ratchet private keys,
     * newest first, tried before the identity key. Returns the plaintext and the index
     * of the ratchet that worked (-1 for the identity key), or null when nothing does.
     */
    fun decrypt(data: ByteArray, ratchetPrivs: List<ByteArray> = emptyList()): Pair<ByteArray, Int>? {
        val e = encPriv ?: throw IllegalStateException("identity cannot decrypt")
        if (data.size <= Rns.PUB_LEN) return null
        val peerPub = try { X25519PublicKeyParameters(data, 0) } catch (_: Exception) { return null }
        val ct = data.copyOfRange(Rns.PUB_LEN, data.size)
        fun attempt(prv: X25519PrivateKeyParameters): ByteArray? {
            val shared = try { agree(prv, peerPub) } catch (_: Exception) { return null }
            return try { Token(Hkdf.derive(Rns.DERIVED_KEY_LEN, shared, hash)).decrypt(ct) } catch (_: WireException) { null }
        }
        for ((i, r) in ratchetPrivs.withIndex()) {
            if (r.size != Rns.RATCHET_LEN) continue
            attempt(X25519PrivateKeyParameters(r, 0))?.let { return it to i }
        }
        attempt(e)?.let { return it to -1 }
        return null
    }

    override fun toString(): String = "<RnsIdentity $hashHex>"

    companion object {
        private val rng = SecureRandom()

        fun generate(): RnsIdentity {
            val e = X25519PrivateKeyParameters(rng)
            val s = Ed25519PrivateKeyParameters(rng)
            return RnsIdentity(e, e.generatePublicKey(), s, s.generatePublicKey())
        }

        /** RNS Identity.from_bytes: x25519 private (32) || ed25519 seed (32). */
        fun fromPrivateBytes(prv: ByteArray): RnsIdentity {
            if (prv.size != 64) throw WireException("private key must be 64 bytes, got ${prv.size}")
            val e = X25519PrivateKeyParameters(prv, 0)
            val s = Ed25519PrivateKeyParameters(prv, 32)
            return RnsIdentity(e, e.generatePublicKey(), s, s.generatePublicKey())
        }

        /** A public-only identity from the 64-byte announce key: encrypts to and verifies, never signs or decrypts. */
        fun fromPublicBytes(pub: ByteArray): RnsIdentity {
            if (pub.size != Rns.KEY_LEN) throw WireException("public key must be 64 bytes, got ${pub.size}")
            return RnsIdentity(null, X25519PublicKeyParameters(pub, 0), null, Ed25519PublicKeyParameters(pub, 32))
        }

        fun fromPublicBytesOrNull(pub: ByteArray): RnsIdentity? = try { fromPublicBytes(pub) } catch (_: Exception) { null }

        /** The app's routing identity as an RNS identity: same keys, so the same hashes. */
        fun fromRouting(identity: net.meshsat.android.routing.Identity): RnsIdentity =
            fromPrivateBytes(identity.encryptionPrivRaw + identity.signingSeedRaw)

        fun verifySignature(sigPub: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            if (sigPub.size != Rns.PUB_LEN || signature.size != Rns.SIG_LEN) return false
            return try { verifySignature(Ed25519PublicKeyParameters(sigPub, 0), data, signature) } catch (_: Exception) { false }
        }

        private fun verifySignature(pub: Ed25519PublicKeyParameters, data: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != Rns.SIG_LEN) return false
            val v = Ed25519Signer()
            v.init(false, pub)
            v.update(data, 0, data.size)
            return v.verifySignature(signature)
        }

        private fun agree(prv: X25519PrivateKeyParameters, pub: X25519PublicKeyParameters): ByteArray {
            val a = X25519Agreement()
            a.init(prv)
            val out = ByteArray(a.agreementSize)
            a.calculateAgreement(pub, out, 0)
            return out
        }

        /** A fresh x25519 ratchet private key (32 bytes). */
        fun generateRatchet(): ByteArray = X25519PrivateKeyParameters(rng).encoded

        /** A fresh x25519 private key for a link handshake (32 bytes); its public key is [ratchetPublic]. */
        fun generateX25519(): ByteArray = X25519PrivateKeyParameters(rng).encoded

        /** X25519 ECDH on raw keys. */
        fun x25519Agree(prv: ByteArray, pub: ByteArray): ByteArray = agree(X25519PrivateKeyParameters(prv, 0), X25519PublicKeyParameters(pub, 0))

        /** A fresh ed25519 seed (32 bytes) for a link's ephemeral signing key. */
        fun generateSigningSeed(): ByteArray = Ed25519PrivateKeyParameters(rng).encoded

        fun signingPublicOfSeed(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

        fun signWithSeed(seed: ByteArray, data: ByteArray): ByteArray {
            val s = Ed25519Signer()
            s.init(true, Ed25519PrivateKeyParameters(seed, 0))
            s.update(data, 0, data.size)
            return s.generateSignature()
        }

        fun ratchetPublic(prv: ByteArray): ByteArray = X25519PrivateKeyParameters(prv, 0).generatePublicKey().encoded

        /** The 32 raw private bytes inside an RFC 8410 PKCS#8 encoding (Ed25519 seed or X25519 scalar). */
        fun rawPrivateFromPkcs8(encoded: ByteArray): ByteArray {
            val info = PrivateKeyInfo.getInstance(encoded)
            val octets = ASN1OctetString.getInstance(info.parsePrivateKey()).octets
            if (octets.size != 32) throw WireException("PKCS#8 private key is ${octets.size} bytes, want 32")
            return octets
        }
    }
}
