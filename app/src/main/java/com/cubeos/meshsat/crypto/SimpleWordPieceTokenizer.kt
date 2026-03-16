package com.cubeos.meshsat.crypto

import android.content.Context
import android.util.Log

/**
 * Minimal WordPiece tokenizer for all-MiniLM-L6-v2.
 * Loads vocab.txt from assets and performs basic tokenization
 * compatible with BERT-style models.
 */
class SimpleWordPieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val unkId: Int,
    private val clsId: Int,
    private val sepId: Int,
) {
    data class TokenResult(val ids: IntArray, val attentionMask: IntArray)

    companion object {
        private const val TAG = "WordPieceTokenizer"
        private const val MAX_SEQ_LEN = 128

        fun loadFromAssets(context: Context, vocabAsset: String = "vocab.txt"): SimpleWordPieceTokenizer? {
            return try {
                val lines = context.assets.open(vocabAsset).bufferedReader().readLines()
                val vocab = mutableMapOf<String, Int>()
                lines.forEachIndexed { idx, line -> vocab[line] = idx }

                val unkId = vocab["[UNK]"] ?: 100
                val clsId = vocab["[CLS]"] ?: 101
                val sepId = vocab["[SEP]"] ?: 102

                Log.i(TAG, "Vocab loaded: ${vocab.size} tokens")
                SimpleWordPieceTokenizer(vocab, unkId, clsId, sepId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load vocab: ${e.message}", e)
                null
            }
        }
    }

    fun tokenize(text: String): TokenResult {
        val tokens = mutableListOf(clsId)

        // Basic pre-tokenization: lowercase, split on whitespace and punctuation
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

        for (word in words) {
            val subTokens = wordPiece(word)
            if (tokens.size + subTokens.size >= MAX_SEQ_LEN - 1) break
            tokens.addAll(subTokens)
        }

        tokens.add(sepId)

        val ids = tokens.toIntArray()
        val mask = IntArray(ids.size) { 1 }
        return TokenResult(ids, mask)
    }

    private fun wordPiece(word: String): List<Int> {
        val result = mutableListOf<Int>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var found = false

            while (start < end) {
                val sub = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##${word.substring(start, end)}"
                }

                val id = vocab[sub]
                if (id != null) {
                    result.add(id)
                    start = end
                    found = true
                    break
                }
                end--
            }

            if (!found) {
                // Character not in vocab — add [UNK] and skip
                result.add(unkId)
                start++
            }
        }

        return result
    }
}
