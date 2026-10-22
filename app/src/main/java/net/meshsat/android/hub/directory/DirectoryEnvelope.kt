package net.meshsat.android.hub.directory

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The Hub's v2 directory envelope (contract C7.2; the Hub's internal/directory/envelope.go, the
 * Bridge's internal/hubreporter/direnvelope.go; fixture docs/fixtures/v1/directory_v2.json):
 * `{"v":2,"key_id","alg":"ES256","payload","sig"}`, the ECDSA P-256 signature (ASN.1) over the
 * exact payload bytes. Nothing of the payload is read before the signature verifies.
 * [MESHSAT-1642]
 */
object DirectoryEnvelope {
    const val VERSION = 2
    const val ALG = "ES256"

    const val FORMAT = "format"
    const val UNKNOWN_KEY = "unknown_key"
    const val BAD_SIGNATURE = "bad_signature"

    /** An envelope or a key this phone does not take, and why ([reason] is one of the constants). */
    class Refused(val reason: String, message: String) : Exception(message)

    // The order of the P-256 group: a key on any other curve is refused.
    private val P256_N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    /** A directory key's id: SHA-256 of its PKIX DER, hex. */
    fun keyId(pkix: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pkix).joinToString("") { "%02x".format(it) }

    /** The ECDSA P-256 public key in [pkix], or [Refused] with [UNKNOWN_KEY]. */
    fun p256(pkix: ByteArray): ECPublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pkix))
        } catch (e: Exception) {
            throw Refused(UNKNOWN_KEY, "not a PKIX public key: ${e.message}")
        }
        if (key !is ECPublicKey || key.params.order != P256_N) {
            throw Refused(UNKNOWN_KEY, "not an ECDSA P-256 key")
        }
        return key
    }

    /** Verify [env] under [trusted] (PKIX DER) and return its payload bytes. */
    fun open(env: JSONObject, trusted: ByteArray): ByteArray {
        val keyId = env.optString("key_id", "")
        val payload = bytes(env, "payload")
        val sig = bytes(env, "sig")
        if (env.optInt("v", 0) != VERSION || env.optString("alg", "") != ALG || payload == null || sig == null || sig.isEmpty()) {
            throw Refused(FORMAT, "not a v2 ES256 envelope")
        }
        if (keyId != keyId(trusted)) throw Refused(UNKNOWN_KEY, "signed by key $keyId, not the key this phone trusts")
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(p256(trusted))
                update(payload)
                verify(sig)
            }
        } catch (e: Refused) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok) throw Refused(BAD_SIGNATURE, "signature does not verify")
        return payload
    }

    /** A Go []byte field: standard base64. Null when it is missing or not base64. */
    internal fun bytes(o: JSONObject, name: String): ByteArray? {
        val s = o.optString(name, "")
        if (s.isEmpty()) return if (o.has(name)) ByteArray(0) else null
        return try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
