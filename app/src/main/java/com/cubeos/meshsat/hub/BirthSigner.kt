package com.cubeos.meshsat.hub

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Signs MQTT birth messages with ECDSA-P256-SHA256.
 *
 * The Hub verifies signatures to prevent bridge spoofing.
 * Uses the same EC private key from the mTLS client certificate.
 *
 * Signing process:
 * 1. Add "certificate" field (base64-encoded PEM)
 * 2. Remove "signature" field
 * 3. Serialize to canonical JSON (sorted keys, no whitespace)
 * 4. SHA-256 hash → ECDSA-P256 sign → base64 encode
 * 5. Add "signature" field to the birth payload
 */
object BirthSigner {
    private const val TAG = "BirthSigner"

    /**
     * Sign a birth JSONObject in-place. Adds "certificate" and "signature" fields.
     *
     * @param birth The birth JSON (will be modified)
     * @param certPem Bridge TLS certificate PEM
     * @param keyPem Bridge TLS private key PEM (SEC1 or PKCS#8)
     * @return true if signed successfully, false on error (birth published unsigned)
     */
    fun sign(birth: JSONObject, certPem: String, keyPem: String): Boolean {
        if (certPem.isBlank() || keyPem.isBlank()) {
            Log.d(TAG, "No cert/key available, birth will be unsigned")
            return false
        }

        try {
            // Add certificate (base64 of PEM string)
            birth.put("certificate", Base64.encodeToString(certPem.toByteArray(), Base64.NO_WRAP))

            // Remove signature for canonical serialization
            birth.remove("signature")

            // Build canonical JSON (sorted keys, no whitespace)
            val canonical = toCanonicalJson(birth)

            // Sign with ECDSA P-256 SHA-256
            val privateKey = loadEcPrivateKey(keyPem)
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(canonical.toByteArray(Charsets.UTF_8))
            val sig = signer.sign() // ASN.1 DER format

            // Add signature
            birth.put("signature", Base64.encodeToString(sig, Base64.NO_WRAP))

            Log.i(TAG, "Birth signed (${sig.size} bytes, canonical ${canonical.length} chars)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Birth signing failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Canonical JSON from JSONObject.
     */
    fun toCanonicalJson(json: JSONObject): String {
        return toCanonicalJson(jsonObjectToMap(json))
    }

    /**
     * Canonical JSON: sorted keys, no whitespace after : or ,
     * Must produce byte-identical output to Go's json.Marshal(map[string]interface{})
     */
    fun toCanonicalJson(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        writeCanonicalMap(map, sb)
        return sb.toString()
    }

    /** Convert JSONObject to a Map (handles Android's JSONObject API). */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val names = obj.names() ?: return map
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            val value = obj.opt(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> (0 until value.length()).map { jsonValueNormalize(value.get(it)) }
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    private fun jsonValueNormalize(value: Any?): Any? = when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValueNormalize(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun writeCanonicalMap(map: Map<String, Any?>, sb: StringBuilder) {
        sb.append("{")
        val keys = map.keys.sorted()
        keys.forEachIndexed { i, key ->
            if (i > 0) sb.append(",")
            sb.append("\"").append(escapeJson(key)).append("\":")
            writeCanonicalValue(map[key], sb)
        }
        sb.append("}")
    }

    private fun writeCanonicalValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is Double -> {
                if (value == value.toLong().toDouble()) sb.append(value.toLong())
                else sb.append(value)
            }
            is Float -> {
                if (value == value.toLong().toFloat()) sb.append(value.toLong())
                else sb.append(value)
            }
            is String -> sb.append("\"").append(escapeJson(value)).append("\"")
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                writeCanonicalMap(value as Map<String, Any?>, sb)
            }
            is List<*> -> {
                sb.append("[")
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",")
                    writeCanonicalValue(v, sb)
                }
                sb.append("]")
            }
            else -> sb.append("\"").append(escapeJson(value.toString())).append("\"")
        }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Load an EC private key from PEM. Handles both SEC1 (BEGIN EC PRIVATE KEY)
     * and PKCS#8 (BEGIN PRIVATE KEY) formats.
     */
    private fun loadEcPrivateKey(pem: String): ECPrivateKey {
        val isSec1 = pem.contains("BEGIN EC PRIVATE KEY")

        val stripped = pem
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val der = Base64.decode(stripped, Base64.DEFAULT)

        val keyBytes = if (isSec1) {
            // SEC1 → PKCS#8 wrapper: prepend the EC algorithm OID header
            wrapSec1InPkcs8(der)
        } else {
            der // Already PKCS#8
        }

        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(keySpec) as ECPrivateKey
    }

    /**
     * Wrap a SEC1 EC private key in PKCS#8 envelope.
     * PKCS#8 = SEQUENCE { version, AlgorithmIdentifier(EC, P-256 OID), OCTET STRING(SEC1 key) }
     */
    private fun wrapSec1InPkcs8(sec1Der: ByteArray): ByteArray {
        // PKCS#8 header for EC P-256 (secp256r1 / prime256v1)
        // 30 (SEQUENCE) + len + 02 01 00 (version=0) + 30 13 (AlgId SEQUENCE) +
        //   06 07 2a 86 48 ce 3d 02 01 (EC OID) + 06 08 2a 86 48 ce 3d 03 01 07 (P-256 OID) +
        //   04 (OCTET STRING) + len + sec1Der
        val algId = byteArrayOf(
            0x30, 0x13,
            0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07,
        )
        val version = byteArrayOf(0x02, 0x01, 0x00) // INTEGER 0

        // OCTET STRING wrapping sec1Der
        val octetString = if (sec1Der.size < 128) {
            byteArrayOf(0x04, sec1Der.size.toByte()) + sec1Der
        } else {
            byteArrayOf(0x04, 0x81.toByte(), sec1Der.size.toByte()) + sec1Der
        }

        val innerLen = version.size + algId.size + octetString.size
        val outer = if (innerLen < 128) {
            byteArrayOf(0x30, innerLen.toByte())
        } else {
            byteArrayOf(0x30, 0x81.toByte(), innerLen.toByte())
        }

        return outer + version + algId + octetString
    }
}
