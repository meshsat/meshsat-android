package net.meshsat.android.rnode

/**
 * The RNode host protocol (RNS/Interfaces/RNodeInterface.py, the Bridge's internal/rnode):
 * KISS frames whose command byte configures and reads the LoRa radio, and CMD_DATA frames
 * that carry raw Reticulum packets. [MESHSAT-1553 P4]
 */
object RnodeProtocol {
    const val CMD_DATA = 0x00
    const val CMD_FREQUENCY = 0x01
    const val CMD_BANDWIDTH = 0x02
    const val CMD_TXPOWER = 0x03
    const val CMD_SF = 0x04
    const val CMD_CR = 0x05
    const val CMD_RADIO_STATE = 0x06
    const val CMD_RADIO_LOCK = 0x07
    const val CMD_DETECT = 0x08
    const val CMD_LEAVE = 0x0A
    const val CMD_ST_ALOCK = 0x0B
    const val CMD_LT_ALOCK = 0x0C
    const val CMD_READY = 0x0F
    const val CMD_STAT_RX = 0x21
    const val CMD_STAT_TX = 0x22
    const val CMD_STAT_RSSI = 0x23
    const val CMD_STAT_SNR = 0x24
    const val CMD_STAT_CHTM = 0x25
    const val CMD_STAT_PHYPRM = 0x26
    const val CMD_STAT_BAT = 0x27
    const val CMD_STAT_CSMA = 0x28
    const val CMD_STAT_TEMP = 0x29
    const val CMD_RANDOM = 0x40
    const val CMD_PLATFORM = 0x48
    const val CMD_MCU = 0x49
    const val CMD_FW_VERSION = 0x50
    const val CMD_RESET = 0x55
    const val CMD_ERROR = 0x90

    const val DETECT_REQ = 0x73
    const val DETECT_RESP = 0x46
    const val RADIO_STATE_OFF = 0x00
    const val RADIO_STATE_ON = 0x01

    const val ERROR_INITRADIO = 0x01
    const val ERROR_TXFAILED = 0x02
    const val ERROR_EEPROM_LOCKED = 0x03
    const val ERROR_QUEUE_FULL = 0x04
    const val ERROR_MEMORY_LOW = 0x05
    const val ERROR_MODEM_TIMEOUT = 0x06

    const val PLATFORM_AVR = 0x90
    const val PLATFORM_ESP32 = 0x80
    const val PLATFORM_NRF52 = 0x70

    const val RESET_MAGIC = 0xF8

    /** The largest data frame payload. */
    const val HW_MTU = 508
    /** Converts a reported RSSI byte to dBm. */
    const val RSSI_OFFSET = 157
    const val REQUIRED_FW_MAJOR = 1
    const val REQUIRED_FW_MINOR = 52
    const val FREQ_MIN = 137_000_000L
    const val FREQ_MAX = 3_000_000_000L

    /** The radio parameters an RNode is configured with. */
    data class Params(
        val frequency: Long,          // Hz
        val bandwidth: Long,          // Hz
        val txPower: Int,             // dBm
        val sf: Int,
        val cr: Int,
        val staLock: Double = 0.0,    // airtime limit short, percent, 0 = not set
        val ltaLock: Double = 0.0,    // airtime limit long, percent
    ) {
        /** The RNodeInterface range checks, or null. */
        fun validate(): String? = when {
            frequency < FREQ_MIN || frequency > FREQ_MAX -> "frequency $frequency Hz outside $FREQ_MIN..$FREQ_MAX"
            bandwidth < 7800 || bandwidth > 1_625_000 -> "bandwidth $bandwidth Hz outside 7800..1625000"
            txPower < 0 || txPower > 37 -> "tx power $txPower dBm outside 0..37"
            sf < 5 || sf > 12 -> "spreading factor $sf outside 5..12"
            cr < 5 || cr > 8 -> "coding rate $cr outside 5..8"
            staLock < 0 || staLock > 100 || ltaLock < 0 || ltaLock > 100 -> "airtime limits must be 0..100 percent"
            else -> null
        }

        /** The on-air bit rate (RNodeInterface.updateBitrate). */
        fun bitrate(): Double = bitrate(sf, cr, bandwidth)
    }

    fun bitrate(sf: Int, cr: Int, bw: Long): Double {
        if (sf == 0 || cr == 0 || bw == 0L) return 0.0
        return sf * ((4.0 / cr) / (Math.pow(2.0, sf.toDouble()) / (bw / 1000.0))) * 1000
    }

    // Command encoders.
    private fun u32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

    /** The detect, firmware, platform and MCU queries sent on open. */
    fun detectBurst(): ByteArray = byteArrayOf(
        Kiss.FEND.toByte(), CMD_DETECT.toByte(), DETECT_REQ.toByte(), Kiss.FEND.toByte(),
        CMD_FW_VERSION.toByte(), 0x00, Kiss.FEND.toByte(), CMD_PLATFORM.toByte(), 0x00, Kiss.FEND.toByte(), CMD_MCU.toByte(), 0x00, Kiss.FEND.toByte(),
    )
    fun leave(): ByteArray = byteArrayOf(Kiss.FEND.toByte(), CMD_LEAVE.toByte(), 0xFF.toByte(), Kiss.FEND.toByte())
    fun hardReset(): ByteArray = byteArrayOf(Kiss.FEND.toByte(), CMD_RESET.toByte(), RESET_MAGIC.toByte(), Kiss.FEND.toByte())
    fun setFrequency(hz: Long): ByteArray = Kiss.encode(CMD_FREQUENCY, u32(hz))
    fun setBandwidth(hz: Long): ByteArray = Kiss.encode(CMD_BANDWIDTH, u32(hz))
    fun setTxPower(dbm: Int): ByteArray = Kiss.encode(CMD_TXPOWER, byteArrayOf(dbm.toByte()))
    fun setSf(sf: Int): ByteArray = Kiss.encode(CMD_SF, byteArrayOf(sf.toByte()))
    fun setCr(cr: Int): ByteArray = Kiss.encode(CMD_CR, byteArrayOf(cr.toByte()))
    fun setStaLock(pct: Double): ByteArray = Kiss.encode(CMD_ST_ALOCK, u16((pct * 100).toInt()))
    fun setLtaLock(pct: Double): ByteArray = Kiss.encode(CMD_LT_ALOCK, u16((pct * 100).toInt()))
    fun setRadioState(on: Boolean): ByteArray = Kiss.encode(CMD_RADIO_STATE, byteArrayOf((if (on) RADIO_STATE_ON else RADIO_STATE_OFF).toByte()))
    fun dataFrame(packet: ByteArray): ByteArray = Kiss.encode(CMD_DATA, packet)

    fun platformName(p: Int): String = when (p) {
        PLATFORM_AVR -> "avr"; PLATFORM_ESP32 -> "esp32"; PLATFORM_NRF52 -> "nrf52"; else -> "0x%02x".format(p)
    }

    /** The values the radio reports. */
    data class Stats(
        var frequency: Long = 0, var bandwidth: Long = 0, var txPower: Int = 0, var sf: Int = 0, var cr: Int = 0,
        var radioOn: Boolean = false, var rssi: Int = 0, var snr: Double = 0.0, var quality: Double = 0.0,
        var airtimeShort: Double = 0.0, var airtimeLong: Double = 0.0, var channelLoadShort: Double = 0.0, var channelLoadLong: Double = 0.0,
        var currentRssi: Int = 0, var noiseFloor: Int = 0, var interference: Int? = null,
        var staLock: Double = 0.0, var ltaLock: Double = 0.0,
        var symbolTimeMs: Double = 0.0, var symbolRate: Int = 0, var preambleSymbols: Int = 0, var preambleTimeMs: Int = 0, var csmaSlotTimeMs: Int = 0, var csmaDifsMs: Int = 0,
        var batteryState: Int = 0, var batteryPercent: Int = 0, var temperatureC: Int? = null,
        var firmwareMajor: Int = 0, var firmwareMinor: Int = 0, var platform: Int = 0, var mcu: Int = 0, var detected: Boolean = false,
        var rxPackets: Long = 0, var txPackets: Long = 0, var rxBytes: Long = 0, var txBytes: Long = 0,
    ) {
        val bitrateBps: Double get() = bitrate(sf, cr, bandwidth)
        val firmware: String get() = "$firmwareMajor.$firmwareMinor"
    }

    /** A regional starting point; the four CrossTalk ships (RNodePresets.js), so a kit and its radios start on identical parameters. */
    data class Preset(val id: String, val label: String, val description: String, val params: Params)

    val PRESETS: List<Preset> = listOf(
        Preset("us-915", "US 915 MHz (starter)", "Common US ISM starting point around 915 MHz. Match your mesh if it uses a different center frequency.",
            Params(915_000_000, 125_000, 22, 7, 5)),
        Preset("eu-868", "EU 868 MHz (starter)", "Common EU ISM starting point at 867.2 MHz from Reticulum examples. Confirm local rules and mesh settings.",
            Params(867_200_000, 125_000, 14, 8, 5)),
        Preset("au-915", "AU/NZ 915 MHz (starter)", "Common starting point in the AU/NZ 915 MHz ISM band. Confirm local rules and mesh settings.",
            Params(915_000_000, 125_000, 22, 7, 5)),
        Preset("ism-433", "433 MHz (starter)", "For 433 MHz-capable RNodes where that band is allowed. Not all Heltec boards support this.",
            Params(433_000_000, 125_000, 12, 7, 5)),
    )

    fun preset(id: String): Preset? = PRESETS.firstOrNull { it.id == id }
}
