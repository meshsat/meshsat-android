package com.cubeos.meshsat.crypto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * MSVQ-SC encoder for TX: text → ONNX embedding → residual VQ → wire format.
 *
 * Requires ONNX Runtime Android and the sentence encoder model in assets.
 */
class MsvqscEncoder private constructor(
    private val session: OrtSession,
    private val env: OrtEnvironment,
    private val tokenizer: SimpleWordPieceTokenizer,
    private val codebook: MsvqscCodebook,
) {
    companion object {
        private const val TAG = "MsvqscEncoder"

        /**
         * Initialize encoder from Android assets.
         * Returns null if ONNX model or tokenizer not available.
         */
        fun loadFromAssets(
            context: Context,
            codebook: MsvqscCodebook,
            encoderAsset: String = "encoder.onnx",
        ): MsvqscEncoder? {
            return try {
                val env = OrtEnvironment.getEnvironment()
                val modelBytes = context.assets.open(encoderAsset).readBytes()
                val session = env.createSession(modelBytes)

                val tokenizer = SimpleWordPieceTokenizer.loadFromAssets(context)
                    ?: throw IllegalStateException("Tokenizer vocab not found in assets")

                Log.i(TAG, "ONNX encoder loaded (${modelBytes.size / 1024 / 1024}MB)")
                MsvqscEncoder(session, env, tokenizer, codebook)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ONNX encoder: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Encode text to MSVQ-SC wire format bytes.
     * @param text Input text to compress
     * @param maxStages Max VQ stages (fewer = smaller wire, lower fidelity)
     * @return Packed wire bytes, or null on failure
     */
    fun encode(text: String, maxStages: Int = 3): ByteArray? {
        return try {
            val embedding = embedText(text)
            val indices = quantize(embedding, maxStages)
            MsvqscWire.pack(indices)
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed: ${e.message}", e)
            null
        }
    }

    private fun embedText(text: String): FloatArray {
        val tokens = tokenizer.tokenize(text)
        val seqLen = tokens.ids.size.toLong()

        val inputIdsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokens.ids.map { it.toLong() }.toLongArray()),
            longArrayOf(1, seqLen),
        )
        try {
            val attMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.attentionMask.map { it.toLong() }.toLongArray()),
                longArrayOf(1, seqLen),
            )
            try {
                val results = session.run(
                    mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attMaskTensor,
                    )
                )
                try {
                    // Output: [1, seq_len, dim] → mean pooling → [dim]
                    @Suppress("UNCHECKED_CAST")
                    val output = results[0].value as Array<Array<FloatArray>>
                    val hidden = output[0] // [seq_len, dim]
                    val dim = hidden[0].size

                    // Mean pooling over non-masked tokens
                    val pooled = FloatArray(dim)
                    var count = 0f
                    for (i in hidden.indices) {
                        if (tokens.attentionMask[i] == 1) {
                            for (d in 0 until dim) pooled[d] += hidden[i][d]
                            count++
                        }
                    }
                    if (count > 0) for (d in 0 until dim) pooled[d] /= count

                    // L2 normalize
                    val n = sqrt(pooled.fold(0f) { acc, v -> acc + v * v })
                    if (n > 0) for (d in 0 until dim) pooled[d] /= n

                    return pooled
                } finally {
                    results.close()
                }
            } finally {
                attMaskTensor.close()
            }
        } finally {
            inputIdsTensor.close()
        }
    }

    private fun quantize(embedding: FloatArray, maxStages: Int): IntArray {
        val stages = minOf(maxStages, codebook.stages)
        val residual = embedding.copyOf()
        val indices = IntArray(stages)

        for (s in 0 until stages) {
            var bestIdx = 0
            var bestDist = Float.MAX_VALUE
            for (e in 0 until codebook.k) {
                var dist = 0f
                for (d in 0 until codebook.dim) {
                    val diff = residual[d] - codebook.vectors[s][e][d]
                    dist += diff * diff
                }
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = e
                }
            }
            indices[s] = bestIdx
            for (d in 0 until codebook.dim) {
                residual[d] -= codebook.vectors[s][bestIdx][d]
            }
        }

        return indices
    }

    fun close() {
        try { session.close() } catch (_: Exception) {}
    }
}
