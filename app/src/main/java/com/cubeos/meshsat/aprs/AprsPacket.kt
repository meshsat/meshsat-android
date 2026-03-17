package com.cubeos.meshsat.aprs

import kotlin.math.abs

/**
 * APRS packet encoder/decoder for position reports and messages.
 * Ported from meshsat Bridge internal/gateway/aprs_packet.go.
 */
data class AprsPacket(
    val source: String = "",
    val dest: String = "",
    val path: String = "",
    val dataType: Char = ' ',
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val symbol: String = "",
    val comment: String = "",
    val message: String = "",
    val msgTo: String = "",
    val msgId: String = "",
    val raw: String = "",
)

object AprsCodec {

    /**
     * Parse an APRS packet from a decoded AX.25 frame.
     */
    fun parse(frame: Ax25Frame): AprsPacket {
        val pathStr = if (frame.path.isNotEmpty()) {
            frame.path.joinToString(",") { it.format() }
        } else ""

        val info = frame.info
        if (info.isEmpty()) {
            return AprsPacket(
                source = frame.src.format(),
                dest = frame.dst.format(),
                path = pathStr,
                raw = String(info),
            )
        }

        val dataType = info[0].toInt().toChar()
        var pkt = AprsPacket(
            source = frame.src.format(),
            dest = frame.dst.format(),
            path = pathStr,
            dataType = dataType,
            raw = String(info),
        )

        when (dataType) {
            '!', '=' -> {
                // Position without timestamp
                pkt = parsePosition(pkt, String(info, 1, info.size - 1))
            }
            '/', '@' -> {
                // Position with timestamp (skip 7-char timestamp + 1)
                if (info.size > 8) {
                    pkt = parsePosition(pkt, String(info, 8, info.size - 8))
                }
            }
            ':' -> {
                // Message
                pkt = parseMessage(pkt, String(info, 1, info.size - 1))
            }
        }

        return pkt
    }

    /**
     * Encode an APRS uncompressed position string.
     * Returns: !DDMM.MMN/DDDMM.MMW-comment
     */
    fun encodePosition(lat: Double, lon: Double, symbolTable: Char = '/', symbolCode: Char = '-', comment: String = ""): ByteArray {
        val absLat = abs(lat)
        val latDir = if (lat >= 0) 'N' else 'S'
        val latDeg = absLat.toInt()
        val latMin = (absLat - latDeg) * 60.0

        val absLon = abs(lon)
        val lonDir = if (lon >= 0) 'E' else 'W'
        val lonDeg = absLon.toInt()
        val lonMin = (absLon - lonDeg) * 60.0

        return String.format(
            "!%02d%05.2f%c%c%03d%05.2f%c%c%s",
            latDeg, latMin, latDir, symbolTable,
            lonDeg, lonMin, lonDir, symbolCode,
            comment,
        ).toByteArray()
    }

    /**
     * Encode an APRS message.
     * Format: :ADDRESSEE :message text{seq
     */
    fun encodeMessage(to: String, text: String, msgId: String = ""): ByteArray {
        val padded = to.padEnd(9)
        return if (msgId.isNotEmpty()) {
            ":$padded:$text{$msgId".toByteArray()
        } else {
            ":$padded:$text".toByteArray()
        }
    }

    private fun parsePosition(pkt: AprsPacket, s: String): AprsPacket {
        if (s.length < 19) return pkt.copy(comment = s)

        val lat = parseAprsLat(s.substring(0, 8)) ?: return pkt.copy(comment = s)
        val lon = parseAprsLon(s.substring(9, 18)) ?: return pkt.copy(comment = s)
        val symbol = "${s[8]}${s[18]}"
        val comment = if (s.length > 19) s.substring(19) else ""

        return pkt.copy(lat = lat, lon = lon, symbol = symbol, comment = comment)
    }

    private fun parseMessage(pkt: AprsPacket, s: String): AprsPacket {
        if (s.length < 11) return pkt
        val msgTo = s.substring(0, 9).trim()
        if (s.length < 11 || s[9] != ':') return pkt.copy(msgTo = msgTo)

        var msg = s.substring(10)
        var msgId = ""
        val braceIdx = msg.lastIndexOf('{')
        if (braceIdx >= 0) {
            msgId = msg.substring(braceIdx + 1)
            msg = msg.substring(0, braceIdx)
        }

        return pkt.copy(msgTo = msgTo, message = msg, msgId = msgId)
    }

    /** Parse "DDMM.MMN" format latitude. */
    private fun parseAprsLat(s: String): Double? {
        if (s.length != 8) return null
        val deg = s.substring(0, 2).toDoubleOrNull() ?: return null
        val min = s.substring(2, 7).toDoubleOrNull() ?: return null
        val lat = deg + min / 60.0
        return if (s[7] == 'S') -lat else lat
    }

    /** Parse "DDDMM.MMW" format longitude. */
    private fun parseAprsLon(s: String): Double? {
        if (s.length != 9) return null
        val deg = s.substring(0, 3).toDoubleOrNull() ?: return null
        val min = s.substring(3, 8).toDoubleOrNull() ?: return null
        val lon = deg + min / 60.0
        return if (s[8] == 'W') -lon else lon
    }
}
