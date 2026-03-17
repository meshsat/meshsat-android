package com.cubeos.meshsat.aprs

/**
 * Configuration for the APRS channel.
 * Ported from meshsat Bridge internal/gateway/aprs_config.go.
 */
data class AprsConfig(
    val kissHost: String = "localhost",
    val kissPort: Int = 8001,
    val callsign: String = "",
    val ssid: Int = 10, // -10 = igate convention
    val frequencyMhz: Double = 144.800, // EU APRS frequency
) {
    /** Validate required fields. Returns error message or null if valid. */
    fun validate(): String? {
        if (callsign.isBlank()) return "callsign is required for APRS"
        if (ssid < 0 || ssid > 15) return "SSID must be 0-15"
        if (kissPort <= 0 || kissPort > 65535) return "KISS port must be 1-65535"
        return null
    }

    /** Format "host:port" address string. */
    fun kissAddress(): String = "$kissHost:$kissPort"

    /** Format callsign with SSID. */
    fun formattedCallsign(): String = if (ssid == 0) callsign else "$callsign-$ssid"
}
