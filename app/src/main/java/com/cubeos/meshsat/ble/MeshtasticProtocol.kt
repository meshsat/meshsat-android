package com.cubeos.meshsat.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight Meshtastic protobuf decoder/encoder.
 *
 * Handles the subset of Meshtastic protobufs needed for text messaging:
 * - FromRadio (field 2 = MeshPacket)
 * - MeshPacket (from, to, channel, decoded.portnum, decoded.payload)
 * - ToRadio (field 1 = MeshPacket for sending)
 *
 * This avoids pulling in the full Meshtastic protobuf dependency.
 * Wire format: varint field tags + varint/length-delimited values.
 */
object MeshtasticProtocol {

    const val PORTNUM_TEXT_MESSAGE = 1
    const val PORTNUM_POSITION = 3
    const val PORTNUM_NODEINFO = 4
    const val PORTNUM_TELEMETRY = 67

    /** A decoded mesh text message. */
    data class MeshTextMessage(
        val from: Long,
        val to: Long,
        val channel: Int,
        val portnum: Int,
        val text: String,
        val id: Long = 0,
    )

    /** Parse a fromRadio protobuf response, extracting text messages. Returns null if not a text message. */
    fun parseFromRadio(data: ByteArray): MeshTextMessage? {
        // FromRadio: field 2 (LEN) = MeshPacket
        val meshPacketBytes = extractField(data, fieldNumber = 2, wireType = 2) ?: return null
        return parseMeshPacket(meshPacketBytes)
    }

    /** Parse a MeshPacket protobuf. */
    private fun parseMeshPacket(data: ByteArray): MeshTextMessage? {
        val from = extractVarint(data, fieldNumber = 1) ?: 0L
        val to = extractVarint(data, fieldNumber = 2) ?: 0L
        val channel = extractVarint(data, fieldNumber = 3)?.toInt() ?: 0
        val id = extractVarint(data, fieldNumber = 6) ?: 0L

        // field 4 = decoded (SubPacket/Data)
        val decodedBytes = extractField(data, fieldNumber = 4, wireType = 2) ?: return null

        // SubPacket: field 1 = portnum (varint), field 2 = payload (bytes)
        val portnum = extractVarint(decodedBytes, fieldNumber = 1)?.toInt() ?: return null
        val payload = extractField(decodedBytes, fieldNumber = 2, wireType = 2) ?: return null

        if (portnum != PORTNUM_TEXT_MESSAGE) return null

        return MeshTextMessage(
            from = from,
            to = to,
            channel = channel,
            portnum = portnum,
            text = String(payload, Charsets.UTF_8),
            id = id,
        )
    }

    /** Encode a text message as a ToRadio protobuf. */
    fun encodeTextMessage(text: String, to: Long = 0xFFFFFFFFL, channel: Int = 0): ByteArray {
        // Data: portnum=1, payload=text
        val payloadBytes = text.toByteArray(Charsets.UTF_8)
        val dataProto = encodeVarintField(1, PORTNUM_TEXT_MESSAGE.toLong()) +
                encodeBytesField(2, payloadBytes)

        // MeshPacket: to, channel, decoded
        val meshPacket = encodeVarintField(2, to) +
                encodeVarintField(3, channel.toLong()) +
                encodeBytesField(4, dataProto)

        // ToRadio: field 1 = MeshPacket
        return encodeBytesField(1, meshPacket)
    }

    /** Format a node number as hex string (e.g., !27ca8f1c). */
    fun formatNodeId(num: Long): String = "!%08x".format(num)

    // --- Protobuf primitives ---

    private fun extractVarint(data: ByteArray, fieldNumber: Int): Long? {
        val tag = (fieldNumber shl 3) or 0 // wire type 0 = varint
        var i = 0
        while (i < data.size) {
            val (currentTag, tagLen) = readVarint(data, i)
            i += tagLen
            val wireType = (currentTag and 0x07).toInt()
            val field = (currentTag shr 3).toInt()

            if (field == fieldNumber && wireType == 0) {
                val (value, _) = readVarint(data, i)
                return value
            }

            // Skip this field
            i = skipField(data, i, wireType) ?: return null
        }
        return null
    }

    private fun extractField(data: ByteArray, fieldNumber: Int, wireType: Int): ByteArray? {
        var i = 0
        while (i < data.size) {
            val (currentTag, tagLen) = readVarint(data, i)
            i += tagLen
            val wt = (currentTag and 0x07).toInt()
            val field = (currentTag shr 3).toInt()

            if (field == fieldNumber && wt == wireType) {
                if (wt == 2) { // length-delimited
                    val (len, lenBytes) = readVarint(data, i)
                    i += lenBytes
                    val end = i + len.toInt()
                    if (end > data.size) return null
                    return data.copyOfRange(i, end)
                }
            }

            i = skipField(data, i, wt) ?: return null
        }
        return null
    }

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to (i - offset)
    }

    private fun skipField(data: ByteArray, offset: Int, wireType: Int): Int? {
        return when (wireType) {
            0 -> { // varint
                val (_, len) = readVarint(data, offset)
                offset + len
            }
            1 -> offset + 8 // 64-bit
            2 -> { // length-delimited
                val (len, lenBytes) = readVarint(data, offset)
                offset + lenBytes + len.toInt()
            }
            5 -> offset + 4 // 32-bit
            else -> null
        }
    }

    private fun encodeVarint(value: Long): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            result.add(b.toByte())
        } while (v != 0L)
        return result.toByteArray()
    }

    private fun encodeVarintField(fieldNumber: Int, value: Long): ByteArray {
        val tag = (fieldNumber shl 3) or 0
        return encodeVarint(tag.toLong()) + encodeVarint(value)
    }

    private fun encodeBytesField(fieldNumber: Int, data: ByteArray): ByteArray {
        val tag = (fieldNumber shl 3) or 2
        return encodeVarint(tag.toLong()) + encodeVarint(data.size.toLong()) + data
    }
}
