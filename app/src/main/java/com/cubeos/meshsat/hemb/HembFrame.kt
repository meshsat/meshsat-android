package com.cubeos.meshsat.hemb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HeMB frame format — compact (8B) and extended (16B) headers.
 * Wire-compatible with Go bridge's internal/hemb/frame.go.
 *
 * CRC-8 uses ITU-T polynomial 0x07 (not GF(256) — separate algorithm).
 */
object HembFrame {
    const val EXTENDED_HEADER_LEN = 16
    const val COMPACT_HEADER_LEN = 8
    const val MAGIC_0: Byte = 0x48 // 'H'
    const val MAGIC_1: Byte = 0x4D // 'M'

    const val HEADER_MODE_COMPACT = "compact"
    const val HEADER_MODE_EXTENDED = "extended"
    const val HEADER_MODE_IMPLICIT = "implicit"

    const val FLAG_DATA: Int = 0x00
    const val FLAG_REPAIR: Int = 0x01
    const val FLAG_ACK: Int = 0x02
    const val FLAG_CTRL: Int = 0x03

    /** CRC-8 (ITU-T polynomial 0x07). */
    fun crc8(data: ByteArray, offset: Int = 0, length: Int = data.size): Byte {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1
                crc = crc and 0xFF
            }
        }
        return crc.toByte()
    }

    /** Returns true if the data starts with a valid HeMB frame header. */
    fun isHembFrame(data: ByteArray): Boolean {
        if (data.size >= EXTENDED_HEADER_LEN &&
            data[0] == MAGIC_0 && data[1] == MAGIC_1
        ) {
            return crc8(data, 0, 15) == data[15]
        }
        if (data.size >= COMPACT_HEADER_LEN) {
            return crc8(data, 0, 7) == data[7]
        }
        return false
    }

    /** Returns header overhead in bytes for a given mode. */
    fun headerOverhead(mode: String): Int = when (mode) {
        HEADER_MODE_COMPACT -> COMPACT_HEADER_LEN
        HEADER_MODE_EXTENDED -> EXTENDED_HEADER_LEN
        HEADER_MODE_IMPLICIT -> 0
        else -> EXTENDED_HEADER_LEN
    }

    /** Marshal an extended header + coded symbol into a frame. */
    fun marshalExtended(
        streamId: Int,
        sym: HembCodedSymbol,
        bearerIndex: Int,
        totalN: Int,
        flags: Int = FLAG_DATA,
    ): ByteArray {
        val k = sym.k
        val frameSize = EXTENDED_HEADER_LEN + k + sym.data.size
        val buf = ByteBuffer.allocate(frameSize).order(ByteOrder.LITTLE_ENDIAN)

        // Bytes 0-1: magic
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        // Byte 2: version(2b) | streamID bits 3:0 (4b) | flags(2b)
        buf.put(((streamId and 0x0F shl 2) or (flags and 0x03)).toByte())
        // Byte 3: streamID bits 7:0
        buf.put((streamId and 0xFF).toByte())
        // Bytes 4-5: sequence (LE) — use symbolIndex
        buf.putShort(sym.symbolIndex.toShort())
        // Byte 6: K
        buf.put(k.toByte())
        // Byte 7: N
        buf.put(totalN.toByte())
        // Byte 8: bearer index
        buf.put(bearerIndex.toByte())
        // Bytes 9-10: generation ID (LE)
        buf.putShort(sym.genId.toShort())
        // Bytes 11-12: total payload size (LE)
        buf.putShort(0.toShort())
        // Byte 13: TTL
        buf.put(0.toByte())
        // Byte 14: extended flags
        buf.put(0.toByte())
        // Byte 15: CRC-8 (computed over bytes 0-14)
        val arr = buf.array()
        arr[15] = crc8(arr, 0, 15)

        // Coefficients + coded data
        System.arraycopy(sym.coefficients, 0, arr, EXTENDED_HEADER_LEN, k)
        System.arraycopy(sym.data, 0, arr, EXTENDED_HEADER_LEN + k, sym.data.size)

        return arr
    }

    /** Marshal a compact header + coded symbol into a frame. */
    fun marshalCompact(
        streamId: Int,
        sym: HembCodedSymbol,
        bearerIndex: Int,
        totalN: Int,
        flags: Int = FLAG_DATA,
    ): ByteArray {
        val k = sym.k
        val frameSize = COMPACT_HEADER_LEN + k + sym.data.size
        val arr = ByteArray(frameSize)

        // Byte 0: version(2b) | streamID(4b) | flags(2b)
        arr[0] = (((streamId and 0x0F) shl 2) or (flags and 0x03)).toByte()
        // Byte 1: sequence bits 11:4
        arr[1] = ((sym.symbolIndex shr 4) and 0xFF).toByte()
        // Byte 2: sequence bits 3:0 (4b) | K low nibble (4b) — wait, Go uses different layout
        // Actually follow Go's compact layout exactly:
        // Byte 1: sequence[11:4]
        // Byte 2: K (8b)
        arr[2] = k.toByte()
        // Byte 3: N (8b)
        arr[3] = totalN.toByte()
        // Byte 4: bearerIndex(4b) | genID bits 9:6 (4b)
        arr[4] = (((bearerIndex and 0x0F) shl 4) or ((sym.genId shr 6) and 0x0F)).toByte()
        // Byte 5: genID bits 5:0 (6b) | TTL(2b)
        arr[5] = ((sym.genId and 0x3F) shl 2).toByte()
        // Byte 6: sequence bits 3:0 (4b) | reserved(4b)
        arr[6] = ((sym.symbolIndex and 0x0F) shl 4).toByte()
        // Byte 7: CRC-8
        arr[7] = crc8(arr, 0, 7)

        // Coefficients + coded data
        System.arraycopy(sym.coefficients, 0, arr, COMPACT_HEADER_LEN, k)
        System.arraycopy(sym.data, 0, arr, COMPACT_HEADER_LEN + k, sym.data.size)

        return arr
    }

    /**
     * Parse a HeMB frame into its components.
     * Returns null if the frame is invalid.
     */
    fun parseSymbol(data: ByteArray): ParsedSymbol? {
        // Extended header (magic bytes present)
        if (data.size >= EXTENDED_HEADER_LEN && data[0] == MAGIC_0 && data[1] == MAGIC_1) {
            if (crc8(data, 0, 15) != data[15]) return null

            val flags = data[2].toInt() and 0x03
            val streamId = ((data[2].toInt() and 0xFF) shr 2 and 0x0F) or
                ((data[3].toInt() and 0xFF) shl 4)
            val sequence = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
            val k = data[6].toInt() and 0xFF
            val n = data[7].toInt() and 0xFF
            val bearerIdx = data[8].toInt() and 0xFF
            val genId = (data[9].toInt() and 0xFF) or ((data[10].toInt() and 0xFF) shl 8)

            val coeffEnd = EXTENDED_HEADER_LEN + k
            if (data.size < coeffEnd + 1) return null
            val coeffs = data.copyOfRange(EXTENDED_HEADER_LEN, coeffEnd)
            val codedData = data.copyOfRange(coeffEnd, data.size)

            return ParsedSymbol(
                streamId = streamId and 0xFF,
                bearerIndex = bearerIdx,
                symbol = HembCodedSymbol(
                    genId = genId,
                    symbolIndex = sequence,
                    k = k,
                    coefficients = coeffs,
                    data = codedData,
                ),
                n = n,
                flags = flags,
                headerMode = HEADER_MODE_EXTENDED,
            )
        }

        // Compact header fallback
        if (data.size >= COMPACT_HEADER_LEN) {
            if (crc8(data, 0, 7) != data[7]) return null

            val flags = data[0].toInt() and 0x03
            val streamId = (data[0].toInt() and 0xFF) shr 2 and 0x0F
            val k = data[2].toInt() and 0xFF
            val n = data[3].toInt() and 0xFF
            val bearerIdx = (data[4].toInt() and 0xFF) shr 4 and 0x0F
            val genId = (((data[4].toInt() and 0x0F) shl 6) or
                ((data[5].toInt() and 0xFF) shr 2)) and 0x3FF
            val sequence = (((data[1].toInt() and 0xFF) shl 4) or
                ((data[6].toInt() and 0xFF) shr 4)) and 0xFFF

            val coeffEnd = COMPACT_HEADER_LEN + k
            if (data.size < coeffEnd + 1) return null
            val coeffs = data.copyOfRange(COMPACT_HEADER_LEN, coeffEnd)
            val codedData = data.copyOfRange(coeffEnd, data.size)

            return ParsedSymbol(
                streamId = streamId,
                bearerIndex = bearerIdx,
                symbol = HembCodedSymbol(
                    genId = genId,
                    symbolIndex = sequence,
                    k = k,
                    coefficients = coeffs,
                    data = codedData,
                ),
                n = n,
                flags = flags,
                headerMode = HEADER_MODE_COMPACT,
            )
        }

        return null
    }

    /**
     * Promote a compact header frame to extended header for relay/DTN.
     */
    fun promoteHeader(compactFrame: ByteArray): ByteArray? {
        val parsed = parseSymbol(compactFrame) ?: return null
        if (parsed.headerMode == HEADER_MODE_EXTENDED) return compactFrame // already extended
        return marshalExtended(
            streamId = parsed.streamId,
            sym = parsed.symbol,
            bearerIndex = parsed.bearerIndex,
            totalN = parsed.n,
            flags = parsed.flags,
        )
    }

    data class ParsedSymbol(
        val streamId: Int,
        val bearerIndex: Int,
        val symbol: HembCodedSymbol,
        val n: Int,
        val flags: Int = FLAG_DATA,
        val headerMode: String = HEADER_MODE_EXTENDED,
    )
}
