package com.cubeos.meshsat.aprs

/**
 * AX.25 UI frame encoder/decoder.
 * Ported from meshsat Bridge internal/gateway/aprs_packet.go.
 */

data class Ax25Address(val call: String, val ssid: Int = 0) {
    fun format(): String = if (ssid == 0) call else "$call-$ssid"
}

data class Ax25Frame(
    val dst: Ax25Address,
    val src: Ax25Address,
    val path: List<Ax25Address> = emptyList(),
    val info: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ax25Frame) return false
        return dst == other.dst && src == other.src && path == other.path && info.contentEquals(other.info)
    }
    override fun hashCode(): Int = dst.hashCode() xor src.hashCode() xor info.contentHashCode()
}

object Ax25Codec {

    /**
     * Encode an AX.25 UI frame for transmission.
     */
    fun encode(dst: Ax25Address, src: Ax25Address, path: List<Ax25Address>, info: ByteArray): ByteArray {
        val buf = mutableListOf<Byte>()

        // Destination (7 bytes)
        buf.addAll(encodeAddress(dst, last = false).toList())

        // Source (7 bytes) — last flag if no path
        buf.addAll(encodeAddress(src, last = path.isEmpty()).toList())

        // Path
        for ((i, p) in path.withIndex()) {
            buf.addAll(encodeAddress(p, last = i == path.lastIndex).toList())
        }

        // Control: UI frame (0x03), PID: no layer 3 (0xF0)
        buf.add(0x03)
        buf.add(0xF0.toByte())

        // Information field
        buf.addAll(info.toList())

        return buf.toByteArray()
    }

    /**
     * Decode an AX.25 UI frame from raw bytes.
     * Returns null if the frame is invalid.
     */
    fun decode(data: ByteArray): Ax25Frame? {
        if (data.size < 16) return null // minimum: dst(7) + src(7) + ctrl(1) + pid(1)

        val dst = decodeAddress(data, 0)
        val src = decodeAddress(data, 7)

        var offset = 14
        val path = mutableListOf<Ax25Address>()

        // Check if source has "last" flag unset → path follows
        if (data[13].toInt() and 0x01 == 0) {
            while (offset + 7 <= data.size) {
                val addr = decodeAddress(data, offset)
                val last = data[offset + 6].toInt() and 0x01 == 1
                path.add(addr)
                offset += 7
                if (last) break
            }
        }

        // Control + PID
        if (offset + 2 > data.size) return null
        val ctrl = data[offset].toInt() and 0xFF
        val pid = data[offset + 1].toInt() and 0xFF
        if (ctrl != 0x03 || pid != 0xF0) return null
        offset += 2

        val info = if (offset < data.size) data.copyOfRange(offset, data.size) else ByteArray(0)

        return Ax25Frame(dst = dst, src = src, path = path, info = info)
    }

    private fun encodeAddress(addr: Ax25Address, last: Boolean): ByteArray {
        val call = addr.call.uppercase().padEnd(6).take(6)
        val buf = ByteArray(7)
        for (i in 0 until 6) {
            buf[i] = (call[i].code shl 1).toByte()
        }
        // SSID byte: bits 5-1 = SSID, bit 0 = last flag, bits 6-5 = reserved (set)
        val ssid = (addr.ssid and 0x0F).toByte()
        buf[6] = ((ssid.toInt() shl 1) or 0x60 or (if (last) 0x01 else 0x00)).toByte()
        return buf
    }

    private fun decodeAddress(data: ByteArray, offset: Int): Ax25Address {
        val call = StringBuilder()
        for (i in 0 until 6) {
            val c = (data[offset + i].toInt() and 0xFF) shr 1
            if (c.toChar() != ' ') call.append(c.toChar())
        }
        val ssid = (data[offset + 6].toInt() shr 1) and 0x0F
        return Ax25Address(call = call.toString(), ssid = ssid)
    }
}
