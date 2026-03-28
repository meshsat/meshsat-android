package com.cubeos.meshsat.timesync

/**
 * Time source with stratum (quality) ranking.
 * Lower stratum = more authoritative. Mirrors NTP stratum concept.
 */
sealed class TimeSource(val stratum: Int, val name: String) {
    /**
     * Cellular NITZ — cell tower time via Network Identity and Time Zone.
     * On Android, System.currentTimeMillis() uses NITZ when cellular is
     * connected. Stratum 0 because it's automatic and always-on when
     * the phone has signal — no GPS sky view needed. Sub-second accuracy
     * from the carrier's NTP-synced infrastructure.
     */
    data object CellularNITZ : TimeSource(0, "Cellular NITZ")

    /** GPS receiver fix — most accurate standalone source, stratum 1. */
    data object GPS : TimeSource(1, "GPS")

    /** Iridium MSSTM epoch counter — satellite-derived, stratum 1. */
    data object IridiumMSSTM : TimeSource(1, "Iridium MSSTM")

    /** Hub NTP relayed via MQTT — server-quality time, stratum 2. */
    data object HubNTP : TimeSource(2, "Hub NTP")

    /** Mesh consensus — derived from peer with best stratum. */
    class MeshConsensus(parentStratum: Int) : TimeSource(parentStratum + 1, "Mesh (s${parentStratum + 1})")

    /** Local RTC — free-running, no external sync. Only used when fully offline. */
    data object LocalRTC : TimeSource(16, "Local RTC")

    override fun toString(): String = "$name (stratum $stratum)"
}
