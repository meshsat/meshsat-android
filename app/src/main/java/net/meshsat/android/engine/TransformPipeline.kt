package net.meshsat.android.engine

import android.util.Log
import net.meshsat.android.codec.Smaz2Bytes
import net.meshsat.android.codec.Smaz2Dict
import net.meshsat.android.codec.Smaz2Exception
import net.meshsat.android.codec.Smaz2g
import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.crypto.MsvqscCodebook
import net.meshsat.android.crypto.MsvqscEncoder

/**
 * Applies ordered transforms to message payloads (compress, encrypt, encode).
 * Port of meshsat/internal/engine/transform.go for Android transports.
 *
 * Supported transforms on Android:
 * - "base64": Base64 encode/decode (required for text-only channels like SMS)
 * - "encrypt": AES-256-GCM encrypt/decrypt ("key" param, or "key_ref" through [keyResolver])
 * - "msvqsc": MSVQ-SC lossy semantic compression (requires encoder for TX, codebook for RX)
 * - "smaz2", "smaz2g": SMAZ2 with the "dict" param's table, byte for byte as the Bridge
 *   (smaz2g adds its mode byte, HeMB-NG S3-1; [Smaz2Bytes], [Smaz2g])
 */
class TransformPipeline {

    var msvqscEncoder: MsvqscEncoder? = null
    var msvqscCodebook: MsvqscCodebook? = null

    /**
     * Resolves an encrypt step's "key_ref" ("channel:address", e.g. "iridium:shared") to its
     * 64-hex AES key, null when there is none: the step then fails, it never runs without a key.
     */
    var keyResolver: ((String) -> String?)? = null

    /** A fixed nonce for the encrypt step, set only by the shared test vectors. */
    internal var fixedNonce: ByteArray? = null

    /**
     * Apply egress transforms in order (compress -> encrypt -> base64).
     * @param data Raw payload bytes.
     * @param transformsJson JSON array of TransformSpec, e.g. [{"type":"encrypt","params":{"key":"..."}},{"type":"base64"}]
     * @return Transformed payload bytes.
     */
    fun applyEgress(data: ByteArray, transformsJson: String?): ByteArray =
        applyEgress(data, TransformSpec.parseList(transformsJson))

    /** [applyEgress] on a parsed chain. */
    fun applyEgress(data: ByteArray, transforms: List<TransformSpec>): ByteArray {
        var result = data
        for (t in transforms) {
            result = applyTransform(t, result)
        }
        return result
    }

    /**
     * Apply ingress transforms in reverse order (decode -> decrypt -> decompress).
     * @param data Transformed payload bytes.
     * @param transformsJson JSON array of TransformSpec.
     * @return Original payload bytes.
     */
    fun applyIngress(data: ByteArray, transformsJson: String?): ByteArray =
        applyIngress(data, TransformSpec.parseList(transformsJson))

    /** [applyIngress] on a parsed chain. */
    fun applyIngress(data: ByteArray, transforms: List<TransformSpec>): ByteArray {
        var result = data
        for (i in transforms.indices.reversed()) {
            result = reverseTransform(transforms[i], result)
        }
        return result
    }

    private fun encryptKey(t: TransformSpec): String {
        t.params["key"]?.takeIf { it.isNotBlank() }?.let { return it }
        val ref = t.params["key_ref"]
            ?: throw TransformException("encrypt transform requires a 'key' or 'key_ref' param")
        return keyResolver?.invoke(ref)?.takeIf { it.isNotBlank() }
            ?: throw TransformException("encrypt transform: no key for '$ref'")
    }

    private fun applyTransform(t: TransformSpec, data: ByteArray): ByteArray = when (t.type) {
        // java.util.Base64, not android.util.Base64: the chains run in the JVM tests as well.
        "base64" -> java.util.Base64.getEncoder().encode(data)

        "encrypt" -> {
            val key = encryptKey(t)
            try {
                AesGcmCrypto.encrypt(data, key, fixedNonce)
            } catch (e: Exception) {
                throw TransformException("encrypt: ${e.javaClass.simpleName}")
            }
        }

        "smaz2" -> Smaz2Bytes.compress(data, Smaz2Dict.of(t.params["dict"]))

        "smaz2g" -> Smaz2g.encode(data, Smaz2Dict.of(t.params["dict"]))

        "msvqsc" -> {
            val encoder = msvqscEncoder
            if (encoder == null) {
                Log.w(TAG, "transform: msvqsc encoder not available, passing through")
                data
            } else {
                val maxStages = parseMaxStages(t.params)
                encoder.encode(String(data, Charsets.UTF_8), maxStages)
                    ?: throw TransformException("msvqsc encode failed")
            }
        }

        else -> {
            Log.w(TAG, "transform: unknown type '${t.type}', skipping")
            data
        }
    }

    private fun reverseTransform(t: TransformSpec, data: ByteArray): ByteArray = when (t.type) {
        // Lenient as android.util.Base64.DEFAULT was: line breaks and other non-alphabet bytes are skipped.
        "base64" -> java.util.Base64.getMimeDecoder().decode(data)

        "encrypt" -> try {
            AesGcmCrypto.decrypt(data, encryptKey(t))
        } catch (e: TransformException) {
            throw e
        } catch (e: Exception) {
            // A wrong key, a changed tag or ciphertext, a cut message: never a guess.
            throw TransformException("decrypt: ${e.javaClass.simpleName}")
        }

        "smaz2" -> try {
            Smaz2Bytes.decompress(data, Smaz2Dict.of(t.params["dict"]))
        } catch (e: Smaz2Exception) {
            throw TransformException(e.message ?: "smaz2")
        }

        "smaz2g" -> try {
            Smaz2g.decode(data, Smaz2Dict.of(t.params["dict"]))
        } catch (e: Smaz2Exception) {
            throw TransformException(e.message ?: "smaz2g")
        }

        "msvqsc" -> {
            val codebook = msvqscCodebook
                ?: throw TransformException("msvqsc: codebook not available for decode")
            codebook.decode(data).toByteArray(Charsets.UTF_8)
        }

        else -> {
            Log.w(TAG, "transform: unknown reverse type '${t.type}', skipping")
            data
        }
    }

    private fun parseMaxStages(params: Map<String, String>): Int {
        val s = params["stages"]
        return when {
            s.isNullOrBlank() || s == "auto" -> 3 // Android default
            else -> s.toIntOrNull() ?: 3
        }
    }

    companion object {
        private const val TAG = "TransformPipeline"
        private const val AES_GCM_OVERHEAD = 28 // 12 nonce + 16 tag

        /**
         * Validate a transform chain against a channel's capabilities.
         * @param transformsJson JSON array of TransformSpec.
         * @param binaryCapable Whether the channel supports binary payloads.
         * @param maxPayload Channel's max payload size (0 = unlimited).
         * @return Pair of (warnings, errors). Non-empty errors = invalid chain.
         */
        fun validate(
            transformsJson: String?,
            binaryCapable: Boolean,
            maxPayload: Int,
        ): Pair<List<String>, List<String>> {
            val warnings = mutableListOf<String>()
            val errors = mutableListOf<String>()

            val transforms = try {
                TransformSpec.parseList(transformsJson)
            } catch (e: Exception) {
                return emptyList<String>() to listOf("Invalid transforms JSON: ${e.message}")
            }

            if (transforms.isEmpty()) return emptyList<String>() to emptyList()

            var hasBinaryOutput = false
            var endsWithBase64 = false
            var hasBase64 = false

            for (t in transforms) {
                when (t.type) {
                    "encrypt" -> {
                        hasBinaryOutput = true
                        endsWithBase64 = false
                        if (t.params["key"].isNullOrBlank() && t.params["key_ref"].isNullOrBlank()) {
                            errors += "encrypt transform requires a 'key' or 'key_ref' param"
                        }
                    }
                    "msvqsc", "smaz2", "smaz2g" -> {
                        hasBinaryOutput = true
                        endsWithBase64 = false
                    }
                    "base64" -> {
                        hasBinaryOutput = false
                        endsWithBase64 = true
                        hasBase64 = true
                    }
                }
            }

            // Text-only transport with binary output
            if (!binaryCapable && hasBinaryOutput && !endsWithBase64) {
                errors += "Text-only transport (SMS) requires base64 as the final transform after encrypt/compress"
            }

            // Estimate usable capacity on constrained channels
            if (maxPayload > 0) {
                var usable = maxPayload
                for (i in transforms.indices.reversed()) {
                    when (transforms[i].type) {
                        "base64" -> usable = usable * 3 / 4
                        "encrypt" -> usable -= AES_GCM_OVERHEAD
                    }
                }
                if (usable < 20) {
                    warnings += "Transforms leave very little usable payload (~$usable bytes of $maxPayload)"
                } else if (hasBase64 && usable.toDouble() / maxPayload < 0.6) {
                    warnings += "Transforms reduce usable capacity to ~$usable bytes (of $maxPayload max)"
                }
            }

            return warnings to errors
        }
    }
}

class TransformException(message: String) : RuntimeException(message)
