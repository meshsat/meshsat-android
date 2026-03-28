package com.cubeos.meshsat.timesync

/**
 * Time source with stratum (quality) ranking.
 * Lower stratum = more authoritative. Mirrors NTP stratum concept.
 */
sealed class TimeSource(val stratum: Int, val name: String) {
    /** GPS receiver fix — most accurate, stratum 1. */
    data object GPS : TimeSource(1, "GPS")

    /** Iridium MSSTM epoch counter — satellite-derived, stratum 1. */
    data object IridiumMSSTM : TimeSource(1, "Iridium MSSTM")

    /** Hub NTP relayed via MQTT — server-quality time, stratum 2. */
    data object HubNTP : TimeSource(2, "Hub NTP")

    /** Mesh consensus — derived from peer with best stratum. */
    class MeshConsensus(parentStratum: Int) : TimeSource(parentStratum + 1, "Mesh (s${parentStratum + 1})")

    /** Local RTC — free-running hardware clock, drifts ~2 ppm. */
    data object LocalRTC : TimeSource(16, "Local RTC")

    override fun toString(): String = "$name (stratum $stratum)"
}
