package com.cubeos.meshsat.astrocast

/**
 * Astrocast Astronode S serial protocol encoder/decoder.
 *
 * Frame format: [STX 0x02][opcode as 2 hex chars][payload as hex chars][CRC16 as 4 hex chars][ETX 0x03]
 * CRC-16: init 0xFFFF, computed over opcode+payload bytes, byte-swapped before encoding.
 *
 * Reference: https://github.com/Astrocast/astronode-c-library
 */
object AstrocastProtocol {

    const val STX: Byte = 0x02
    const val ETX: Byte = 0x03
    const val MAX_PAYLOAD_BYTES = 160
    const val ANSWER_TIMEOUT_MS = 1500L

    // --- Request opcodes (asset → module) ---
    object OpCode {
        // Configuration
        const val CFG_W: Byte = 0x05
        const val CFG_R: Byte = 0x15
        const val CFG_SAVE: Byte = 0x10.toByte()
        const val CFG_RESET: Byte = 0x11.toByte()

        // WiFi
        const val WIFI_W: Byte = 0x06

        // Satellite search config
        const val SSC_W: Byte = 0x07

        // RTC
        const val RTC_R: Byte = 0x17

        // Next contact opportunity (ephemeris)
        const val EPH_R: Byte = 0x18.toByte()

        // Device info
        const val GUID_R: Byte = 0x19
        const val SN_R: Byte = 0x1A
        const val PN_R: Byte = 0x1B

        // Payload (uplink)
        const val PLD_W: Byte = 0x25
        const val PLD_DEQUEUE: Byte = 0x26
        const val PLD_CLEAR: Byte = 0x27

        // Geolocation
        const val GEO_W: Byte = 0x35

        // SAK (satellite acknowledgment)
        const val SAK_R: Byte = 0x45
        const val SAK_CL: Byte = 0x46

        // Command (downlink)
        const val CMD_R: Byte = 0x47
        const val CMD_CL: Byte = 0x48

        // Reset clear
        const val RES_CL: Byte = 0x55

        // GPIO
        const val GPO_S: Byte = 0x62
        const val GPI_R: Byte = 0x63

        // Event
        const val EVT_R: Byte = 0x65

        // Context save
        const val CTX_SAVE: Byte = 0x66

        // Performance counters
        const val PERF_R: Byte = 0x67
        const val PERF_CL: Byte = 0x68

        // Module state
        const val MST_R: Byte = 0x69

        // Last contact
        const val LCD_R: Byte = 0x6A

        // Environment
        const val END_R: Byte = 0x6B
    }

    // --- Response opcodes (module → asset) = request | 0x80 ---
    object RspCode {
        const val CFG_W: Byte = 0x85.toByte()
        const val CFG_R: Byte = 0x95.toByte()
        const val CFG_SAVE: Byte = 0x90.toByte()
        const val CFG_RESET: Byte = 0x91.toByte()
        const val WIFI_W: Byte = 0x86.toByte()
        const val SSC_W: Byte = 0x87.toByte()
        const val RTC_R: Byte = 0x97.toByte()
        const val EPH_R: Byte = 0x98.toByte()
        const val GUID_R: Byte = 0x99.toByte()
        const val SN_R: Byte = 0x9A.toByte()
        const val PN_R: Byte = 0x9B.toByte()
        const val PLD_W: Byte = 0xA5.toByte()
        const val PLD_DEQUEUE: Byte = 0xA6.toByte()
        const val PLD_CLEAR: Byte = 0xA7.toByte()
        const val GEO_W: Byte = 0xB5.toByte()
        const val SAK_R: Byte = 0xC5.toByte()
        const val SAK_CL: Byte = 0xC6.toByte()
        const val CMD_R: Byte = 0xC7.toByte()
        const val CMD_CL: Byte = 0xC8.toByte()
        const val RES_CL: Byte = 0xD5.toByte()
        const val EVT_R: Byte = 0xE5.toByte()
        const val CTX_SAVE: Byte = 0xE6.toByte()
        const val PERF_R: Byte = 0xE7.toByte()
        const val PERF_CL: Byte = 0xE8.toByte()
        const val MST_R: Byte = 0xE9.toByte()
        const val LCD_R: Byte = 0xEA.toByte()
        const val END_R: Byte = 0xEB.toByte()
        const val ERROR: Byte = 0xFF.toByte()
    }

    // --- Error codes ---
    object ErrorCode {
        const val CRC_NOT_VALID = 0x0001
        const val LENGTH_NOT_VALID = 0x0011
        const val OPCODE_NOT_VALID = 0x0121
        const val FORMAT_NOT_VALID = 0x0601
        const val FLASH_WRITING_FAILED = 0x0611
        const val BUFFER_FULL = 0x2501
        const val DUPLICATE_ID = 0x2511
        const val BUFFER_EMPTY = 0x2601
        const val INVALID_POS = 0x3501
        const val NO_ACK = 0x4501
        const val NO_CLEAR = 0x4601

        fun name(code: Int): String = when (code) {
            CRC_NOT_VALID -> "CRC_NOT_VALID"
            LENGTH_NOT_VALID -> "LENGTH_NOT_VALID"
            OPCODE_NOT_VALID -> "OPCODE_NOT_VALID"
            FORMAT_NOT_VALID -> "FORMAT_NOT_VALID"
            FLASH_WRITING_FAILED -> "FLASH_WRITING_FAILED"
            BUFFER_FULL -> "BUFFER_FULL"
            DUPLICATE_ID -> "DUPLICATE_ID"
            BUFFER_EMPTY -> "BUFFER_EMPTY"
            INVALID_POS -> "INVALID_POS"
            NO_ACK -> "NO_ACK"
            NO_CLEAR -> "NO_CLEAR"
            else -> "UNKNOWN(0x${code.toString(16)})"
        }
    }

    // --- Event flags (from EVT_R response byte 0) ---
    object EventFlag {
        const val SAK_AVAILABLE = 0x01
        const val RESET_EVENT = 0x02
        const val CMD_AVAILABLE = 0x04
        const val MSG_BUSY = 0x08
    }

    /** Parsed response from the Astronode module. */
    data class Response(
        val opcode: Byte,
        val payload: ByteArray,
    ) {
        val isError: Boolean get() = opcode == RspCode.ERROR
        val errorCode: Int
            get() = if (isError && payload.size >= 2) {
                (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
            } else 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Response) return false
            return opcode == other.opcode && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * opcode.hashCode() + payload.contentHashCode()
    }

    /**
     * Encode a command frame: STX + hex(opcode+payload) + hex(CRC16) + ETX.
     */
    fun encodeFrame(opcode: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val data = byteArrayOf(opcode) + payload
        val crc = crc16(data)

        val hex = StringBuilder()
        for (b in data) hex.append(byteToHex(b))
        // CRC is byte-swapped: high byte first in transmission
        hex.append(byteToHex((crc shr 8).toByte()))
        hex.append(byteToHex((crc and 0xFF).toByte()))

        val frame = ByteArray(1 + hex.length + 1)
        frame[0] = STX
        for (i in hex.indices) frame[i + 1] = hex[i].code.toByte()
        frame[frame.size - 1] = ETX
        return frame
    }

    /**
     * Decode a response frame. Returns null if frame is malformed or CRC fails.
     */
    fun decodeFrame(frame: ByteArray): Response? {
        if (frame.size < 7) return null // STX + 2 opcode + 4 CRC + ETX minimum
        if (frame[0] != STX || frame[frame.size - 1] != ETX) return null

        // Extract hex string between STX and ETX
        val hexStr = String(frame, 1, frame.size - 2, Charsets.US_ASCII)
        if (hexStr.length < 6 || hexStr.length % 2 != 0) return null

        // Parse all hex bytes
        val bytes = hexToBytes(hexStr) ?: return null
        if (bytes.size < 3) return null // opcode(1) + CRC(2) minimum

        // Split: data bytes and CRC (last 2 bytes)
        val data = bytes.copyOfRange(0, bytes.size - 2)
        val receivedCrc = ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or
                (bytes[bytes.size - 1].toInt() and 0xFF)

        // Verify CRC
        val computedCrc = crc16(data)
        if (computedCrc != receivedCrc) return null

        return Response(
            opcode = data[0],
            payload = if (data.size > 1) data.copyOfRange(1, data.size) else byteArrayOf(),
        )
    }

    // --- Command builders ---

    /** Queue an uplink payload. Counter is a 16-bit message ID. */
    fun payloadWrite(counter: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Payload exceeds ${MAX_PAYLOAD_BYTES}B" }
        val buf = ByteArray(2 + payload.size)
        buf[0] = (counter and 0xFF).toByte()
        buf[1] = ((counter shr 8) and 0xFF).toByte()
        payload.copyInto(buf, 2)
        return encodeFrame(OpCode.PLD_W, buf)
    }

    /** Dequeue (remove) the oldest sent message. */
    fun payloadDequeue(): ByteArray = encodeFrame(OpCode.PLD_DEQUEUE)

    /** Clear all queued messages. */
    fun payloadClear(): ByteArray = encodeFrame(OpCode.PLD_CLEAR)

    /** Set device geolocation (lat/lon in microdegrees: degrees * 1_000_000). */
    fun geolocationWrite(latMicro: Int, lonMicro: Int): ByteArray {
        val buf = ByteArray(8)
        putInt32LE(buf, 0, latMicro)
        putInt32LE(buf, 4, lonMicro)
        return encodeFrame(OpCode.GEO_W, buf)
    }

    /** Read pending events. */
    fun eventRead(): ByteArray = encodeFrame(OpCode.EVT_R)

    /** Read satellite acknowledgment counter. */
    fun sakRead(): ByteArray = encodeFrame(OpCode.SAK_R)

    /** Clear satellite acknowledgment flag. */
    fun sakClear(): ByteArray = encodeFrame(OpCode.SAK_CL)

    /** Read incoming command (downlink). */
    fun commandRead(): ByteArray = encodeFrame(OpCode.CMD_R)

    /** Clear command available flag. */
    fun commandClear(): ByteArray = encodeFrame(OpCode.CMD_CL)

    /** Read module RTC (returns epoch seconds). */
    fun rtcRead(): ByteArray = encodeFrame(OpCode.RTC_R)

    /** Read device GUID. */
    fun guidRead(): ByteArray = encodeFrame(OpCode.GUID_R)

    /** Read serial number. */
    fun serialNumberRead(): ByteArray = encodeFrame(OpCode.SN_R)

    /** Read configuration. */
    fun configurationRead(): ByteArray = encodeFrame(OpCode.CFG_R)

    /** Write configuration flags. */
    fun configurationWrite(
        satAck: Boolean = true,
        geolocation: Boolean = true,
        ephemeris: Boolean = true,
        deepSleep: Boolean = false,
        satAckMask: Boolean = true,
        resetMask: Boolean = true,
        cmdMask: Boolean = true,
        busyMask: Boolean = false,
    ): ByteArray {
        val flags = (if (satAck) 1 else 0) or
                (if (geolocation) 2 else 0) or
                (if (ephemeris) 4 else 0) or
                (if (deepSleep) 8 else 0)
        val masks = (if (satAckMask) 1 else 0) or
                (if (resetMask) 2 else 0) or
                (if (cmdMask) 4 else 0) or
                (if (busyMask) 8 else 0)
        val buf = byteArrayOf(flags.toByte(), 0, masks.toByte(), 0)
        return encodeFrame(OpCode.CFG_W, buf)
    }

    /** Save configuration to flash. */
    fun configurationSave(): ByteArray = encodeFrame(OpCode.CFG_SAVE)

    /** Read next satellite pass prediction. */
    fun ephemerisRead(): ByteArray = encodeFrame(OpCode.EPH_R)

    /** Read module state (TLV response). */
    fun moduleStateRead(): ByteArray = encodeFrame(OpCode.MST_R)

    /** Read last satellite contact details (TLV response). */
    fun lastContactRead(): ByteArray = encodeFrame(OpCode.LCD_R)

    /** Read environment/signal data (TLV response). */
    fun environmentRead(): ByteArray = encodeFrame(OpCode.END_R)

    /** Clear reset notification. */
    fun resetClear(): ByteArray = encodeFrame(OpCode.RES_CL)

    /** Save module context. */
    fun contextSave(): ByteArray = encodeFrame(OpCode.CTX_SAVE)

    /** Read performance counters (TLV response). */
    fun perfCountersRead(): ByteArray = encodeFrame(OpCode.PERF_R)

    /** Clear performance counters. */
    fun perfCountersClear(): ByteArray = encodeFrame(OpCode.PERF_CL)

    // --- Response parsers ---

    /** Parse event flags from EVT_R response. */
    fun parseEventFlags(rsp: Response): Int {
        require(rsp.opcode == RspCode.EVT_R) { "Not an EVT_R response" }
        return if (rsp.payload.isNotEmpty()) rsp.payload[0].toInt() and 0xFF else 0
    }

    /** Parse SAK counter from SAK_R response. */
    fun parseSakCounter(rsp: Response): Int {
        require(rsp.opcode == RspCode.SAK_R) { "Not a SAK_R response" }
        return getUint16LE(rsp.payload, 0)
    }

    /** Parse RTC epoch from RTC_R response. */
    fun parseRtcEpoch(rsp: Response): Long {
        require(rsp.opcode == RspCode.RTC_R) { "Not an RTC_R response" }
        return getUint32LE(rsp.payload, 0)
    }

    /** Parse next pass epoch from EPH_R response. */
    fun parseNextPass(rsp: Response): Long {
        require(rsp.opcode == RspCode.EPH_R) { "Not an EPH_R response" }
        return getUint32LE(rsp.payload, 0)
    }

    /** Parse GUID string from GUID_R response. */
    fun parseGuid(rsp: Response): String {
        require(rsp.opcode == RspCode.GUID_R) { "Not a GUID_R response" }
        return String(rsp.payload, Charsets.US_ASCII).trim('\u0000')
    }

    /** Parse serial number from SN_R response. */
    fun parseSerialNumber(rsp: Response): String {
        require(rsp.opcode == RspCode.SN_R) { "Not a SN_R response" }
        return String(rsp.payload, Charsets.US_ASCII).trim('\u0000')
    }

    /** Parse payload write ACK counter from PLD_W response. */
    fun parsePayloadWriteCounter(rsp: Response): Int {
        require(rsp.opcode == RspCode.PLD_W) { "Not a PLD_W response" }
        return getUint16LE(rsp.payload, 0)
    }

    /** Parse downlink command from CMD_R response. */
    fun parseCommand(rsp: Response): Pair<Long, ByteArray> {
        require(rsp.opcode == RspCode.CMD_R) { "Not a CMD_R response" }
        val createdDate = getUint32LE(rsp.payload, 0)
        val data = if (rsp.payload.size > 4) rsp.payload.copyOfRange(4, rsp.payload.size) else byteArrayOf()
        return Pair(createdDate, data)
    }

    // --- CRC-16 ---

    /**
     * CRC-16 matching Astrocast C library:
     * Init: 0xFFFF, byte-swapped output.
     */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            var x = (crc ushr 8) xor (b.toInt() and 0xFF)
            x = x xor (x ushr 4)
            crc = ((crc shl 8) xor (x shl 12) xor (x shl 5) xor x) and 0xFFFF
        }
        // Byte-swap
        return ((crc shl 8) and 0xFF00) or ((crc ushr 8) and 0x00FF)
    }

    // --- Helpers ---

    private fun byteToHex(b: Byte): String {
        val hi = (b.toInt() ushr 4) and 0x0F
        val lo = b.toInt() and 0x0F
        return "${HEX_CHARS[hi]}${HEX_CHARS[lo]}"
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                val hi = hexVal(hex[i * 2])
                val lo = hexVal(hex[i * 2 + 1])
                if (hi < 0 || lo < 0) return null
                ((hi shl 4) or lo).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    private fun putInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun getUint16LE(buf: ByteArray, offset: Int): Int {
        if (buf.size < offset + 2) return 0
        return (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun getUint32LE(buf: ByteArray, offset: Int): Long {
        if (buf.size < offset + 4) return 0
        return (buf[offset].toLong() and 0xFF) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 3].toLong() and 0xFF) shl 24)
    }

    private val HEX_CHARS = "0123456789ABCDEF"
}
