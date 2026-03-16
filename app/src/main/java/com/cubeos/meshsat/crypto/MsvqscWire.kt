package com.cubeos.meshsat.crypto

import android.util.Base64

/**
 * MSVQ-SC wire format: pack/unpack codebook indices for SMS transport.
 *
 * Wire format: [1B header: 4-bit stages | 4-bit version] [2B uint16 LE per stage]
 * Over SMS: base64-encoded wire bytes (optionally wrapped in AES-GCM).
 */
object MsvqscWire {

    const val VERSION = 1
    private const val HEADER_SIZE = 1
    private const val INDEX_SIZE = 2 // uint16 LE per stage

    /** Pack VQ indices into wire format bytes. */
    fun pack(indices: IntArray): ByteArray {
        val stages = indices.size
        val header = ((stages and 0x0F) shl 4) or (VERSION and 0x0F)
        val buf = ByteArray(HEADER_SIZE + stages * INDEX_SIZE)
        buf[0] = header.toByte()
        for (i in indices.indices) {
            val idx = indices[i]
            val offset = HEADER_SIZE + i * INDEX_SIZE
            buf[offset] = (idx and 0xFF).toByte()
            buf[offset + 1] = ((idx shr 8) and 0xFF).toByte()
        }
        return buf
    }

    /** Unpack wire format bytes to indices. Returns (indices, stages, version). */
    fun unpack(data: ByteArray): Triple<IntArray, Int, Int> {
        require(data.size >= HEADER_SIZE) { "Wire data too short" }
        val header = data[0].toInt() and 0xFF
        val stages = (header shr 4) and 0x0F
        val version = header and 0x0F
        require(version == VERSION) { "Unsupported wire version $version" }
        val expectedLen = HEADER_SIZE + stages * INDEX_SIZE
        require(data.size >= expectedLen) { "Wire data too short: need $expectedLen, got ${data.size}" }

        val indices = IntArray(stages)
        for (s in 0 until stages) {
            val offset = HEADER_SIZE + s * INDEX_SIZE
            indices[s] = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8)
        }
        return Triple(indices, stages, version)
    }

    /** Encode wire bytes to base64 string (for SMS). */
    fun toBase64(wire: ByteArray): String =
        Base64.encodeToString(wire, Base64.NO_WRAP)

    /** Decode base64 string back to wire bytes. */
    fun fromBase64(text: String): ByteArray =
        Base64.decode(text.trim(), Base64.DEFAULT)

    /** Wire overhead in bytes for a given stage count. */
    fun wireSize(stages: Int): Int = HEADER_SIZE + stages * INDEX_SIZE

    /**
     * Check if raw bytes look like an MSVQ-SC wire payload.
     * The header byte has version in lower nibble and stages (1-8) in upper nibble.
     * Valid: version=1, stages in 1..8, total length matches.
     */
    fun looksLikeMsvqsc(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val header = data[0].toInt() and 0xFF
        val stages = (header shr 4) and 0x0F
        val version = header and 0x0F
        if (version != VERSION) return false
        if (stages !in 1..8) return false
        val expectedLen = HEADER_SIZE + stages * INDEX_SIZE
        return data.size == expectedLen
    }
}
