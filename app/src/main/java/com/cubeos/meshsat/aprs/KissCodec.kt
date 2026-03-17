package com.cubeos.meshsat.aprs

import java.io.ByteArrayOutputStream

/**
 * KISS TNC protocol encoder/decoder (TNC-2 spec).
 * Ported from meshsat Bridge internal/gateway/aprs_kiss.go.
 */
object KissCodec {

    const val FEND: Byte = 0xC0.toByte()   // Frame End
    const val FESC: Byte = 0xDB.toByte()   // Frame Escape
    const val TFEND: Byte = 0xDC.toByte()  // Transposed Frame End
    const val TFESC: Byte = 0xDD.toByte()  // Transposed Frame Escape
    const val DATA: Byte = 0x00            // Data frame command byte

    /**
     * Encode an AX.25 payload into a KISS frame.
     * Format: FEND + command(0x00) + escaped_data + FEND
     */
    fun encode(payload: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream(payload.size + 10)
        buf.write(FEND.toInt() and 0xFF)
        buf.write(DATA.toInt() and 0xFF)
        for (b in payload) {
            when (b) {
                FEND -> {
                    buf.write(FESC.toInt() and 0xFF)
                    buf.write(TFEND.toInt() and 0xFF)
                }
                FESC -> {
                    buf.write(FESC.toInt() and 0xFF)
                    buf.write(TFESC.toInt() and 0xFF)
                }
                else -> buf.write(b.toInt() and 0xFF)
            }
        }
        buf.write(FEND.toInt() and 0xFF)
        return buf.toByteArray()
    }

    /**
     * Decode a KISS frame payload (without outer FEND delimiters).
     * Returns the unescaped AX.25 data, or null if the frame is invalid.
     */
    fun decode(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null

        // First byte is command — 0x00 for data frames
        if (frame[0].toInt() and 0x0F != 0) return null

        val buf = ByteArrayOutputStream(frame.size)
        var escaped = false
        for (i in 1 until frame.size) {
            val b = frame[i]
            if (escaped) {
                when (b) {
                    TFEND -> buf.write(FEND.toInt() and 0xFF)
                    TFESC -> buf.write(FESC.toInt() and 0xFF)
                    else -> return null // invalid escape sequence
                }
                escaped = false
            } else if (b == FESC) {
                escaped = true
            } else {
                buf.write(b.toInt() and 0xFF)
            }
        }

        if (escaped) return null // trailing escape
        return buf.toByteArray()
    }
}
