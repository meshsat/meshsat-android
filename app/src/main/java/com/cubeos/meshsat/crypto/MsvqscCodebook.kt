package com.cubeos.meshsat.crypto

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * MSVQ-SC codebook for pure-Kotlin decode (no ML runtime needed for RX).
 *
 * Loads codebook vectors and corpus index from assets, reconstructs
 * approximate text from wire-format indices via codebook vector sum +
 * nearest-neighbor corpus lookup.
 */
class MsvqscCodebook internal constructor(
    val version: Int,
    val stages: Int,
    val k: Int,
    val dim: Int,
    internal val vectors: Array<Array<FloatArray>>,  // [stage][entry][dim]
    private val corpus: List<String>,
    private val corpusEmbeddings: Array<FloatArray>, // [N][dim]
) {

    companion object {
        private const val TAG = "MsvqscCodebook"
        private const val CODEBOOK_MAGIC = "MSVQ"
        private const val CORPUS_MAGIC = "MCIX"

        /**
         * Load codebook + corpus index from Android assets.
         * Returns null if files not found or corrupt.
         */
        fun loadFromAssets(
            context: Context,
            codebookAsset: String = "codebook_v1.bin",
            corpusAsset: String = "corpus_index.bin",
        ): MsvqscCodebook? {
            return try {
                val cbStream = context.assets.open(codebookAsset)
                val ciStream = context.assets.open(corpusAsset)
                val cb = parseCodebook(cbStream)
                val (corpus, embeddings) = parseCorpusIndex(ciStream, cb.dim)
                MsvqscCodebook(
                    version = cb.version,
                    stages = cb.stages,
                    k = cb.k,
                    dim = cb.dim,
                    vectors = cb.vectors,
                    corpus = corpus,
                    corpusEmbeddings = embeddings,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load codebook: ${e.message}", e)
                null
            }
        }

        private data class RawCodebook(
            val version: Int,
            val stages: Int,
            val k: Int,
            val dim: Int,
            val vectors: Array<Array<FloatArray>>,
        )

        private fun parseCodebook(stream: InputStream): RawCodebook {
            val data = stream.readBytes()
            require(data.size >= 10) { "Codebook too short (${data.size} bytes)" }

            val magic = String(data, 0, 4, Charsets.US_ASCII)
            require(magic == CODEBOOK_MAGIC) { "Invalid codebook magic: $magic" }

            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            val version = buf.get().toInt() and 0xFF
            val stages = buf.get().toInt() and 0xFF
            val k = buf.short.toInt() and 0xFFFF
            val dim = buf.short.toInt() and 0xFFFF

            val vectors = Array(stages) { Array(k) { FloatArray(dim) } }
            for (s in 0 until stages) {
                for (e in 0 until k) {
                    for (d in 0 until dim) {
                        vectors[s][e][d] = buf.float
                    }
                }
            }

            Log.i(TAG, "Codebook loaded: v$version, $stages stages, K=$k, dim=$dim")
            return RawCodebook(version, stages, k, dim, vectors)
        }

        private fun parseCorpusIndex(stream: InputStream, expectedDim: Int): Pair<List<String>, Array<FloatArray>> {
            val data = stream.readBytes()
            require(data.size >= 11) { "Corpus index too short" }

            val magic = String(data, 0, 4, Charsets.US_ASCII)
            require(magic == CORPUS_MAGIC) { "Invalid corpus magic: $magic" }

            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            val _version = buf.get().toInt() and 0xFF
            val numEntries = buf.int
            val dim = buf.short.toInt() and 0xFFFF
            require(dim == expectedDim) { "Corpus dim $dim != codebook dim $expectedDim" }

            val texts = mutableListOf<String>()
            val embeddings = mutableListOf<FloatArray>()

            for (i in 0 until numEntries) {
                val textLen = buf.short.toInt() and 0xFFFF
                val textBytes = ByteArray(textLen)
                buf.get(textBytes)
                texts.add(String(textBytes, Charsets.UTF_8))

                val emb = FloatArray(dim) { buf.float }
                embeddings.add(emb)
            }

            Log.i(TAG, "Corpus loaded: $numEntries entries, dim=$dim")
            return texts to embeddings.toTypedArray()
        }
    }

    /**
     * Decode wire-format bytes to reconstructed text.
     * Pure math — no ML runtime needed.
     */
    fun decode(wire: ByteArray): String {
        val (indices, wireStages, _) = MsvqscWire.unpack(wire)
        return decodeIndices(indices, wireStages)
    }

    /**
     * Decode codebook indices to nearest corpus text.
     */
    fun decodeIndices(indices: IntArray, numStages: Int = indices.size): String {
        // Reconstruct embedding: sum codebook vectors at each stage
        val reconstructed = FloatArray(dim)
        for (s in 0 until minOf(numStages, stages, indices.size)) {
            val idx = indices[s]
            require(idx in 0 until k) { "Index $idx out of range (K=$k) at stage $s" }
            for (d in 0 until dim) {
                reconstructed[d] += vectors[s][idx][d]
            }
        }

        // Find nearest corpus entry by cosine similarity
        require(corpus.isNotEmpty()) { "No corpus loaded" }
        val reconNorm = norm(reconstructed)
        var bestIdx = 0
        var bestSim = -1f

        for (i in corpus.indices) {
            val corpusNorm = norm(corpusEmbeddings[i])
            val denom = reconNorm * corpusNorm
            if (denom < 1e-8f) continue // skip zero vectors to avoid NaN
            val sim = dot(reconstructed, corpusEmbeddings[i]) / denom
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }

        return corpus[bestIdx]
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun norm(v: FloatArray): Float = sqrt(dot(v, v))
}
