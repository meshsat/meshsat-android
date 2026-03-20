package com.cubeos.meshsat.aprs

/**
 * APRS-IS passcode calculator.
 *
 * The APRS-IS passcode is a public hash of the base callsign (without SSID).
 * Algorithm: http://www.aprs-is.net/Connecting.aspx
 *
 * [MESHSAT-230]
 */
object AprsIsPasscode {

    /**
     * Calculate APRS-IS passcode for a callsign.
     * Strips SSID if present (e.g., "PA3XYZ-10" → uses "PA3XYZ").
     *
     * @param callsign Station callsign (with or without SSID)
     * @return Passcode string (1-32767)
     */
    fun calculate(callsign: String): String {
        val base = callsign.split("-")[0].uppercase()
        if (base.isEmpty()) return "-1"

        var hash = 0x73E2 // Magic seed
        val chars = base.toCharArray()

        var i = 0
        while (i < chars.size) {
            hash = hash xor (chars[i].code shl 8)
            if (i + 1 < chars.size) {
                hash = hash xor chars[i + 1].code
            }
            i += 2
        }

        return (hash and 0x7FFF).toString()
    }
}
