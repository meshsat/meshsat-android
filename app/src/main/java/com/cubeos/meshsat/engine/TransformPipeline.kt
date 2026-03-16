package com.cubeos.meshsat.engine

import android.util.Base64
import android.util.Log
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.crypto.MsvqscCodebook
import com.cubeos.meshsat.crypto.MsvqscEncoder

/**
 * Applies ordered transforms to message payloads (compress, encrypt, encode).
 * Port of meshsat/internal/engine/transform.go for Android transports.
 *
 * Supported transforms on Android:
 * - "base64": Base64 encode/decode (required for text-only channels like SMS)
 * - "encrypt": AES-256-GCM encrypt/decrypt (requires "key" param)
 * - "msvqsc": MSVQ-SC lossy semantic compression (requires encoder for TX, codebook for RX)
 */
class TransformPipeline {

    var msvqscEncoder: MsvqscEncoder? = null
    var msvqscCodebook: MsvqscCodebook? = null

    /**
     * Apply egress transforms in order (compress -> encrypt -> base64).
     * @param data Raw payload bytes.
     * @param transformsJson JSON array of TransformSpec, e.g. [{"type":"encrypt","params":{"key":"..."}},{"type":"base64"}]
     * @return Transformed payload bytes.
     */
    fun applyEgress(data: ByteArray, transformsJson: String?): ByteArray {
        val transforms = TransformSpec.parseList(transformsJson)
        if (transforms.isEmpty()) return data

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
    fun applyIngress(data: ByteArray, transformsJson: String?): ByteArray {
        val transforms = TransformSpec.parseList(transformsJson)
        if (transforms.isEmpty()) return data

        var result = data
        for (i in transforms.indices.reversed()) {
            result = reverseTransform(transforms[i], result)
        }
        return result
    }

    private fun applyTransform(t: TransformSpec, data: ByteArray): ByteArray = when (t.type) {
        "base64" -> {
            Base64.encode(data, Base64.NO_WRAP)
        }

        "encrypt" -> {
            val key = t.params["key"]
                ?: throw TransformException("encrypt transform requires a 'key' param")
            AesGcmCrypto.encrypt(data, key)
        }

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
        "base64" -> {
            Base64.decode(data, Base64.DEFAULT)
        }

        "encrypt" -> {
            val key = t.params["key"]
                ?: throw TransformException("encrypt transform requires a 'key' param")
            AesGcmCrypto.decrypt(data, key)
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
                        if (t.params["key"].isNullOrBlank()) {
                            errors += "encrypt transform requires a 'key' param"
                        }
                    }
                    "msvqsc" -> {
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
