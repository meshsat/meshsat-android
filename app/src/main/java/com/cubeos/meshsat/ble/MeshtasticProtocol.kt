package com.cubeos.meshsat.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight Meshtastic protobuf decoder/encoder.
 *
 * Handles the subset of Meshtastic protobufs needed for text messaging
 * and position tracking:
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

    /** A decoded mesh position update. */
    data class MeshPosition(
        val from: Long,
        val latitude: Double,   // degrees
        val longitude: Double,  // degrees
        val altitude: Int,      // meters
        val time: Long,         // epoch ms
    )

    /** Telemetry from mesh (battery level). */
    data class MeshTelemetry(
        val from: Long,
        val batteryLevel: Int = -1,  // 0-100 or -1 = unknown
        val voltage: Float = 0f,
    )

    /** Node info from mesh. */
    data class MeshNodeInfo(
        val nodeNum: Long,
        val longName: String = "",
        val shortName: String = "",
        val macaddr: String = "",
        val hwModel: Int = 0,
        val batteryLevel: Int = -1,  // 0-100 or -1 = unknown
        val lastHeard: Long = 0,     // epoch millis when last packet received
    )

    /** My node info (this radio's device info). */
    data class MyNodeInfo(
        val myNodeNum: Long = 0,
        val firmwareVersion: String = "",
        val rebootCount: Int = 0,
        val minAppVersion: Int = 0,
    )

    /** Parse a fromRadio protobuf response, extracting text messages. Returns null if not a text message. */
    fun parseFromRadio(data: ByteArray): MeshTextMessage? {
        // FromRadio: field 2 (LEN) = MeshPacket
        val meshPacketBytes = extractField(data, fieldNumber = 2, wireType = 2) ?: return null
        return parseMeshPacket(meshPacketBytes)
    }

    /** Parse a fromRadio protobuf response, extracting position. Returns null if not a position packet. */
    fun parsePositionFromRadio(data: ByteArray): MeshPosition? {
        val meshPacketBytes = extractField(data, fieldNumber = 2, wireType = 2) ?: return null
        return parsePositionPacket(meshPacketBytes)
    }

    /** Parse my_info from FromRadio (field 3). */
    fun parseMyInfo(data: ByteArray): MyNodeInfo? {
        val myInfoBytes = extractField(data, fieldNumber = 3, wireType = 2) ?: return null
        val nodeNum = extractVarint(myInfoBytes, fieldNumber = 1) ?: 0L
        val fwVersion = extractField(myInfoBytes, fieldNumber = 6, wireType = 2)
            ?.let { String(it, Charsets.UTF_8) } ?: ""
        val rebootCount = extractVarint(myInfoBytes, fieldNumber = 8)?.toInt() ?: 0
        val minApp = extractVarint(myInfoBytes, fieldNumber = 11)?.toInt() ?: 0
        return MyNodeInfo(nodeNum, fwVersion, rebootCount, minApp)
    }

    /** Parse node_info from FromRadio (field 4). */
    fun parseNodeInfo(data: ByteArray): MeshNodeInfo? {
        val nodeInfoBytes = extractField(data, fieldNumber = 4, wireType = 2) ?: return null
        val nodeNum = extractVarint(nodeInfoBytes, fieldNumber = 1) ?: return null
        // field 2 = User sub-message
        val userBytes = extractField(nodeInfoBytes, fieldNumber = 2, wireType = 2)
        val longName = userBytes?.let { extractField(it, fieldNumber = 2, wireType = 2) }
            ?.let { String(it, Charsets.UTF_8) } ?: ""
        val shortName = userBytes?.let { extractField(it, fieldNumber = 3, wireType = 2) }
            ?.let { String(it, Charsets.UTF_8) } ?: ""
        val hwModel = userBytes?.let { extractVarint(it, fieldNumber = 5)?.toInt() } ?: 0
        return MeshNodeInfo(nodeNum, longName, shortName, hwModel = hwModel)
    }

    /** Parse NodeInfo with position from FromRadio. */
    fun parseNodeInfoFromRadio(data: ByteArray): MeshNodeInfo? {
        return parseNodeInfo(data)
    }

    /** Parse a MeshPacket protobuf for text messages. */
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

    /** Parse a MeshPacket protobuf for position data. */
    private fun parsePositionPacket(data: ByteArray): MeshPosition? {
        val from = extractVarint(data, fieldNumber = 1) ?: 0L

        val decodedBytes = extractField(data, fieldNumber = 4, wireType = 2) ?: return null
        val portnum = extractVarint(decodedBytes, fieldNumber = 1)?.toInt() ?: return null
        val payload = extractField(decodedBytes, fieldNumber = 2, wireType = 2) ?: return null

        if (portnum != PORTNUM_POSITION) return null

        // Position protobuf:
        // field 1: sfixed32 latitude_i (wire type 5 = 32-bit, ×1e-7 degrees)
        // field 2: sfixed32 longitude_i (wire type 5 = 32-bit, ×1e-7 degrees)
        // field 3: int32 altitude (wire type 0 = varint)
        val latI = extractFixed32(payload, fieldNumber = 1) ?: return null
        val lonI = extractFixed32(payload, fieldNumber = 2) ?: return null
        val alt = extractVarint(payload, fieldNumber = 3)?.toInt() ?: 0

        // Skip zero positions (node has no GPS fix)
        if (latI == 0 && lonI == 0) return null

        return MeshPosition(
            from = from,
            latitude = latI.toDouble() / 1e7,
            longitude = lonI.toDouble() / 1e7,
            altitude = alt,
            time = System.currentTimeMillis(),
        )
    }

    /**
     * Parse a fromRadio protobuf response for telemetry (battery level).
     * Telemetry: portnum=67, payload contains Telemetry message.
     * Telemetry field 2 = DeviceMetrics, DeviceMetrics field 1 = battery_level (uint32).
     */
    fun parseTelemetryFromRadio(data: ByteArray): MeshTelemetry? {
        val meshPacketBytes = extractField(data, fieldNumber = 2, wireType = 2) ?: return null
        return parseTelemetryPacket(meshPacketBytes)
    }

    private fun parseTelemetryPacket(data: ByteArray): MeshTelemetry? {
        val from = extractVarint(data, fieldNumber = 1) ?: 0L

        val decodedBytes = extractField(data, fieldNumber = 4, wireType = 2) ?: return null
        val portnum = extractVarint(decodedBytes, fieldNumber = 1)?.toInt() ?: return null
        val payload = extractField(decodedBytes, fieldNumber = 2, wireType = 2) ?: return null

        if (portnum != PORTNUM_TELEMETRY) return null

        // Telemetry proto: field 2 (LEN) = device_metrics submessage
        val deviceMetrics = extractField(payload, fieldNumber = 2, wireType = 2)
            ?: return MeshTelemetry(from = from)

        // DeviceMetrics: field 1 = battery_level (uint32), field 2 = voltage (float, fixed32)
        val batteryLevel = extractVarint(deviceMetrics, fieldNumber = 1)?.toInt() ?: -1
        val voltageBits = extractFixed32(deviceMetrics, fieldNumber = 2)
        val voltage = if (voltageBits != null) Float.fromBits(voltageBits) else 0f

        return MeshTelemetry(from = from, batteryLevel = batteryLevel, voltage = voltage)
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

    // ═══════════════════════════════════════════════════════════════════
    // Admin message encoding — radio config + device admin commands
    // ═══════════════════════════════════════════════════════════════════

    const val PORTNUM_ADMIN_APP = 6

    // Admin message field numbers
    private const val ADMIN_GET_CONFIG_REQUEST = 5
    private const val ADMIN_GET_MODULE_CONFIG_REQ = 7
    private const val ADMIN_SET_CONFIG = 34
    private const val ADMIN_SET_MODULE_CONFIG = 35
    private const val ADMIN_FACTORY_RESET = 94
    private const val ADMIN_REBOOT_SECONDS = 97
    private const val ADMIN_SET_TIME_UNIX_SEC = 99

    // Config section enum values
    const val CONFIG_DEVICE = 0
    const val CONFIG_POSITION = 1
    const val CONFIG_POWER = 2
    const val CONFIG_NETWORK = 3
    const val CONFIG_DISPLAY = 4
    const val CONFIG_LORA = 5
    const val CONFIG_BLUETOOTH = 6
    const val CONFIG_SECURITY = 7

    // ModuleConfig section enum values
    const val MODULE_MQTT = 0
    const val MODULE_SERIAL = 1
    const val MODULE_EXT_NOTIFICATION = 2
    const val MODULE_STORE_FORWARD = 3
    const val MODULE_RANGE_TEST = 4
    const val MODULE_TELEMETRY = 5
    const val MODULE_CANNED_MESSAGE = 6

    /** LoRa region codes (Meshtastic RegionCode enum). */
    enum class LoRaRegion(val code: Int, val label: String) {
        Unset(0, "Unset"),
        US(1, "US"),
        EU_433(2, "EU 433"),
        EU_868(3, "EU 868"),
        CN(4, "CN"),
        JP(5, "JP"),
        ANZ(6, "ANZ"),
        KR(7, "KR"),
        TW(8, "TW"),
        RU(9, "RU"),
        IN(10, "IN"),
        NZ_865(11, "NZ 865"),
        TH(12, "TH"),
        LORA_24(13, "2.4 GHz"),
        UA_433(14, "UA 433"),
        UA_868(15, "UA 868"),
        MY_433(16, "MY 433"),
        MY_919(17, "MY 919"),
        SG_923(18, "SG 923"),
        ;
        companion object {
            fun fromCode(code: Int): LoRaRegion = entries.find { it.code == code } ?: Unset
        }
    }

    /** Modem preset codes (Meshtastic ModemPreset enum). */
    enum class ModemPreset(val code: Int, val label: String) {
        LongFast(0, "Long Fast"),
        LongSlow(1, "Long Slow"),
        VLongSlow(2, "Very Long Slow"),
        MedSlow(3, "Medium Slow"),
        MedFast(4, "Medium Fast"),
        ShortSlow(5, "Short Slow"),
        ShortFast(6, "Short Fast"),
        LongMod(7, "Long Moderate"),
        ShortTurbo(8, "Short Turbo"),
        ;
        companion object {
            fun fromCode(code: Int): ModemPreset = entries.find { it.code == code } ?: LongFast
        }
    }

    /**
     * Build a ToRadio admin message requesting a config section.
     * configType: CONFIG_DEVICE..CONFIG_SECURITY
     */
    fun buildAdminGetConfig(myNodeNum: Long, configType: Int): ByteArray {
        val admin = encodeVarintField(ADMIN_GET_CONFIG_REQUEST, configType.toLong())
        return buildAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /**
     * Build a ToRadio admin message requesting a module config section.
     * moduleType: MODULE_MQTT..MODULE_CANNED_MESSAGE
     */
    fun buildAdminGetModuleConfig(myNodeNum: Long, moduleType: Int): ByteArray {
        val admin = encodeVarintField(ADMIN_GET_MODULE_CONFIG_REQ, moduleType.toLong())
        return buildAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /**
     * Build a ToRadio admin message to set a config section.
     * configData is the raw protobuf of the Config message.
     */
    fun buildAdminSetConfig(myNodeNum: Long, configData: ByteArray): ByteArray {
        val admin = encodeBytesField(ADMIN_SET_CONFIG, configData)
        return buildAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /**
     * Build a ToRadio admin message to set a module config section.
     * configData is the raw protobuf of the ModuleConfig message.
     */
    fun buildAdminSetModuleConfig(myNodeNum: Long, configData: ByteArray): ByteArray {
        val admin = encodeBytesField(ADMIN_SET_MODULE_CONFIG, configData)
        return buildAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to reboot the radio after delaySecs. */
    fun buildAdminReboot(myNodeNum: Long, delaySecs: Int): ByteArray {
        val admin = encodeVarintField(ADMIN_REBOOT_SECONDS, delaySecs.toLong())
        return buildAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to factory reset the radio. */
    fun buildAdminFactoryReset(myNodeNum: Long): ByteArray {
        val admin = encodeVarintField(ADMIN_FACTORY_RESET, 1L)
        return buildAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to set the radio's time. */
    fun buildAdminSetTime(myNodeNum: Long, unixSec: Long): ByteArray {
        // field 99, wire type 5 (fixed32)
        val tag = (ADMIN_SET_TIME_UNIX_SEC shl 3) or 5
        val admin = encodeVarint(tag.toLong()) + encodeFixed32(unixSec.toInt())
        return buildAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /**
     * Encode a LoRa config as Config protobuf (field 6 = lora sub-message).
     * Config { lora: LoraConfig { region, modem_preset, tx_power, hop_limit, tx_enabled } }
     */
    fun encodeLoRaConfig(
        region: Int,
        modemPreset: Int,
        txPower: Int,
        hopLimit: Int,
        txEnabled: Boolean,
    ): ByteArray {
        // LoraConfig sub-message fields (per Meshtastic Config.LoRaConfig protobuf):
        // field 2: modem_preset (varint)
        // field 4: region (varint)
        // field 5: hop_limit (varint)
        // field 6: tx_enabled (varint bool)
        // field 7: tx_power (varint)
        var lora = ByteArray(0)
        if (modemPreset != 0) lora += encodeVarintField(2, modemPreset.toLong())
        if (region != 0) lora += encodeVarintField(4, region.toLong())
        if (hopLimit > 0) lora += encodeVarintField(5, hopLimit.toLong())
        lora += encodeVarintField(6, if (txEnabled) 1L else 0L)
        if (txPower > 0) lora += encodeVarintField(7, txPower.toLong())

        // Config: field 6 = lora (length-delimited)
        return encodeBytesField(6, lora)
    }

    // --- Internal: admin ToRadio wrapper ---

    /**
     * Wrap an admin payload in Data(portnum=ADMIN_APP, want_response=true)
     * → MeshPacket(from, to, decoded, hop_limit=3, want_ack=true) → ToRadio.
     */
    private fun buildAdminToRadio(myNodeNum: Long, destNode: Long, adminPayload: ByteArray): ByteArray {
        // Data: portnum=6 (admin), payload, want_response=true
        val data = encodeVarintField(1, PORTNUM_ADMIN_APP.toLong()) +
            encodeBytesField(2, adminPayload) +
            encodeVarintField(3, 1L) // want_response

        // MeshPacket: from (fixed32 field 1), to (fixed32 field 2), decoded (field 4), hop_limit (field 9), want_ack (field 10)
        val pkt = encodeFixed32Field(1, myNodeNum.toInt()) +
            encodeFixed32Field(2, destNode.toInt()) +
            encodeBytesField(4, data) +
            encodeVarintField(9, 3L) +  // hop_limit
            encodeVarintField(10, 1L)   // want_ack

        // ToRadio: field 1 = MeshPacket
        return encodeBytesField(1, pkt)
    }

    private fun encodeFixed32(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    }

    private fun encodeFixed32Field(fieldNumber: Int, value: Int): ByteArray {
        val tag = (fieldNumber shl 3) or 5 // wire type 5 = 32-bit
        return encodeVarint(tag.toLong()) + encodeFixed32(value)
    }

    // --- Protobuf primitives ---

    private fun extractVarint(data: ByteArray, fieldNumber: Int): Long? {
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

    /** Extract a fixed32/sfixed32 field (wire type 5). Returns signed int. */
    private fun extractFixed32(data: ByteArray, fieldNumber: Int): Int? {
        var i = 0
        while (i < data.size) {
            val (currentTag, tagLen) = readVarint(data, i)
            i += tagLen
            val wireType = (currentTag and 0x07).toInt()
            val field = (currentTag shr 3).toInt()

            if (field == fieldNumber && wireType == 5) {
                if (i + 4 > data.size) return null
                return ByteBuffer.wrap(data, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }

            i = skipField(data, i, wireType) ?: return null
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
