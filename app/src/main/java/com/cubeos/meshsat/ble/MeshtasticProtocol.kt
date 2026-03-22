package com.cubeos.meshsat.ble

import com.geeksville.mesh.ConfigProtos
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.ModuleConfigProtos
import com.geeksville.mesh.Portnums

/**
 * Meshtastic protocol facade — delegates to official generated protobuf
 * bindings via [MeshtasticProtoAdapter].
 *
 * All public data classes and method signatures are preserved for backward
 * compatibility. Internal hand-rolled protobuf parsing has been replaced
 * with official generated classes (MESHSAT-241).
 */
object MeshtasticProtocol {

    // ═══════════════════════════════════════════════════════════════
    // Port numbers — mirrored from PortNum enum for convenience
    // ═══════════════════════════════════════════════════════════════

    const val PORTNUM_TEXT_MESSAGE = 1
    const val PORTNUM_REMOTE_HARDWARE = 2
    const val PORTNUM_POSITION = 3
    const val PORTNUM_NODEINFO = 4
    const val PORTNUM_ROUTING = 5
    const val PORTNUM_ADMIN_APP = 6
    const val PORTNUM_TEXT_COMPRESSED = 7
    const val PORTNUM_WAYPOINT = 8
    const val PORTNUM_AUDIO = 9
    const val PORTNUM_DETECTION_SENSOR = 10
    const val PORTNUM_REPLY = 32
    const val PORTNUM_PAXCOUNTER = 34
    const val PORTNUM_SERIAL = 64
    const val PORTNUM_STORE_FORWARD = 65
    const val PORTNUM_RANGE_TEST = 66
    const val PORTNUM_TELEMETRY = 67
    const val PORTNUM_TRACEROUTE = 70
    const val PORTNUM_NEIGHBORINFO = 71
    const val PORTNUM_PRIVATE_APP = 256

    // ═══════════════════════════════════════════════════════════════
    // Data classes — existing (backward-compatible) + new portnums
    // ═══════════════════════════════════════════════════════════════

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
        val latitude: Double,
        val longitude: Double,
        val altitude: Int,
        val time: Long,
    )

    /** Telemetry from mesh (device metrics — battery level). */
    data class MeshTelemetry(
        val from: Long,
        val batteryLevel: Int = -1,
        val voltage: Float = 0f,
    )

    /** Environment telemetry from mesh (temperature, humidity, pressure). */
    data class MeshEnvironmentTelemetry(
        val from: Long,
        val temperature: Float = 0f,
        val relativeHumidity: Float = 0f,
        val barometricPressure: Float = 0f,
        val gasResistance: Float = 0f,
    )

    /** Node info from mesh. */
    data class MeshNodeInfo(
        val nodeNum: Long,
        val longName: String = "",
        val shortName: String = "",
        val macaddr: String = "",
        val hwModel: Int = 0,
        val batteryLevel: Int = -1,
        val lastHeard: Long = 0,
    )

    /** My node info (this radio's device info). */
    data class MyNodeInfo(
        val myNodeNum: Long = 0,
        val firmwareVersion: String = "",
        val rebootCount: Int = 0,
        val minAppVersion: Int = 0,
    )

    /** Device metadata (firmware version, capabilities). */
    data class MeshDeviceMetadata(
        val firmwareVersion: String = "",
        val canShutdown: Boolean = false,
        val hasWifi: Boolean = false,
        val hasBluetooth: Boolean = false,
        val hasEthernet: Boolean = false,
        val hwModel: Int = 0,
    )

    /** Routing info — ACK/NAK status for sent messages. */
    data class MeshRouting(
        val from: Long,
        val requestId: Long,
        val errorReason: Int,
        val errorName: String,
    ) {
        val isAck: Boolean get() = errorReason == 0
        val isNak: Boolean get() = errorReason != 0
    }

    /** Waypoint shared on the mesh. */
    data class MeshWaypoint(
        val id: Long,
        val from: Long,
        val name: String,
        val description: String,
        val latitude: Double,
        val longitude: Double,
        val expire: Long = 0,
        val icon: Int = 0,
    )

    /** Neighbor info — mesh topology data. */
    data class MeshNeighborInfo(
        val nodeId: Long,
        val neighbors: List<MeshNeighbor>,
    )

    data class MeshNeighbor(
        val nodeId: Long,
        val snr: Float,
    )

    /** Traceroute result. */
    data class MeshTraceroute(
        val from: Long,
        val requestId: Long,
        val route: List<Long>,
        val snrTowards: List<Int>,
        val snrBack: List<Int>,
    )

    /** Store-and-forward message from relay node. */
    data class MeshStoreForward(
        val from: Long,
        val requestResponse: Int,
        val requestResponseName: String,
        val text: String? = null,
        val messagesTotal: Int = 0,
        val messagesSaved: Int = 0,
    )

    /** Range test data. */
    data class MeshRangeTest(
        val from: Long,
        val payload: String,
        val rxSnr: Float = 0f,
        val rxRssi: Int = 0,
    )

    /** Detection sensor alert. */
    data class MeshDetectionSensor(
        val from: Long,
        val name: String,
    )

    /** Paxcounter data. */
    data class MeshPaxcounter(
        val from: Long,
    )

    /** Reply/emoji reaction. */
    data class MeshReply(
        val from: Long,
        val to: Long,
        val payload: String,
        val emoji: Int = 0,
    )

    /** Channel configuration from radio. */
    data class MeshChannel(
        val index: Int,
        val name: String,
        val role: Int,
        val psk: ByteArray,
        val uplinkEnabled: Boolean = false,
        val downlinkEnabled: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MeshChannel) return false
            return index == other.index && name == other.name && role == other.role
        }
        override fun hashCode(): Int = index * 31 + name.hashCode()
    }

    // ═══════════════════════════════════════════════════════════════
    // Unified FromRadio parser — single parse, dispatch by type
    // ═══════════════════════════════════════════════════════════════

    /**
     * Result of parsing a FromRadio message. Exactly one field is non-null.
     * Use this for efficient single-parse dispatch instead of calling each
     * parse method separately.
     */
    data class FromRadioResult(
        val textMessage: MeshTextMessage? = null,
        val position: MeshPosition? = null,
        val telemetry: MeshTelemetry? = null,
        val environmentTelemetry: MeshEnvironmentTelemetry? = null,
        val myInfo: MyNodeInfo? = null,
        val nodeInfo: MeshNodeInfo? = null,
        val routing: MeshRouting? = null,
        val waypoint: MeshWaypoint? = null,
        val neighborInfo: MeshNeighborInfo? = null,
        val traceroute: MeshTraceroute? = null,
        val storeForward: MeshStoreForward? = null,
        val rangeTest: MeshRangeTest? = null,
        val detectionSensor: MeshDetectionSensor? = null,
        val paxcounter: MeshPaxcounter? = null,
        val reply: MeshReply? = null,
        val channel: MeshChannel? = null,
        val deviceMetadata: MeshDeviceMetadata? = null,
        val configCompleteId: Int? = null,
        val config: ConfigProtos.Config? = null,
    )

    /**
     * Parse raw FromRadio bytes into a [FromRadioResult].
     * Parses only once and dispatches by portnum/variant.
     */
    fun parseFromRadioFull(data: ByteArray): FromRadioResult? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null

        // Non-packet variants
        MeshtasticProtoAdapter.extractMyInfo(fromRadio)?.let {
            return FromRadioResult(myInfo = it)
        }
        MeshtasticProtoAdapter.extractNodeInfo(fromRadio)?.let {
            return FromRadioResult(nodeInfo = it)
        }
        MeshtasticProtoAdapter.extractChannel(fromRadio)?.let {
            return FromRadioResult(channel = it)
        }
        MeshtasticProtoAdapter.extractDeviceMetadata(fromRadio)?.let {
            return FromRadioResult(deviceMetadata = it)
        }
        if (fromRadio.hasConfig()) {
            return FromRadioResult(config = fromRadio.config)
        }
        if (fromRadio.hasConfigCompleteId()) {
            return FromRadioResult(configCompleteId = fromRadio.configCompleteId)
        }

        // Packet variants — dispatch by portnum
        val portnum = MeshtasticProtoAdapter.getPortnum(fromRadio) ?: return FromRadioResult()
        return when (portnum) {
            Portnums.PortNum.TEXT_MESSAGE_APP ->
                FromRadioResult(textMessage = MeshtasticProtoAdapter.extractTextMessage(fromRadio))
            Portnums.PortNum.POSITION_APP ->
                FromRadioResult(position = MeshtasticProtoAdapter.extractPosition(fromRadio))
            Portnums.PortNum.TELEMETRY_APP -> {
                val env = MeshtasticProtoAdapter.extractEnvironmentTelemetry(fromRadio)
                if (env != null) FromRadioResult(environmentTelemetry = env)
                else FromRadioResult(telemetry = MeshtasticProtoAdapter.extractTelemetry(fromRadio))
            }
            Portnums.PortNum.ROUTING_APP ->
                FromRadioResult(routing = MeshtasticProtoAdapter.extractRouting(fromRadio))
            Portnums.PortNum.WAYPOINT_APP ->
                FromRadioResult(waypoint = MeshtasticProtoAdapter.extractWaypoint(fromRadio))
            Portnums.PortNum.NEIGHBORINFO_APP ->
                FromRadioResult(neighborInfo = MeshtasticProtoAdapter.extractNeighborInfo(fromRadio))
            Portnums.PortNum.TRACEROUTE_APP ->
                FromRadioResult(traceroute = MeshtasticProtoAdapter.extractTraceroute(fromRadio))
            Portnums.PortNum.STORE_FORWARD_APP ->
                FromRadioResult(storeForward = MeshtasticProtoAdapter.extractStoreForward(fromRadio))
            Portnums.PortNum.RANGE_TEST_APP ->
                FromRadioResult(rangeTest = MeshtasticProtoAdapter.extractRangeTest(fromRadio))
            Portnums.PortNum.DETECTION_SENSOR_APP ->
                FromRadioResult(detectionSensor = MeshtasticProtoAdapter.extractDetectionSensor(fromRadio))
            Portnums.PortNum.PAXCOUNTER_APP ->
                FromRadioResult(paxcounter = MeshtasticProtoAdapter.extractPaxcounter(fromRadio))
            Portnums.PortNum.REPLY_APP ->
                FromRadioResult(reply = MeshtasticProtoAdapter.extractReply(fromRadio))
            Portnums.PortNum.NODEINFO_APP -> {
                // NodeInfo can also come as a mesh packet (not just FromRadio.node_info)
                FromRadioResult(nodeInfo = MeshtasticProtoAdapter.extractNodeInfo(fromRadio))
            }
            else -> FromRadioResult() // Unhandled portnum
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Legacy parse methods — delegates to adapter for compatibility
    // ═══════════════════════════════════════════════════════════════

    /** Parse a fromRadio protobuf, extracting text messages. Returns null if not a text message. */
    fun parseFromRadio(data: ByteArray): MeshTextMessage? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractTextMessage(fromRadio)
    }

    /** Parse a fromRadio protobuf, extracting position. Returns null if not a position packet. */
    fun parsePositionFromRadio(data: ByteArray): MeshPosition? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractPosition(fromRadio)
    }

    /** Parse my_info from FromRadio. */
    fun parseMyInfo(data: ByteArray): MyNodeInfo? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractMyInfo(fromRadio)
    }

    /** Parse node_info from FromRadio. */
    fun parseNodeInfo(data: ByteArray): MeshNodeInfo? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractNodeInfo(fromRadio)
    }

    /** Parse NodeInfo from FromRadio. */
    fun parseNodeInfoFromRadio(data: ByteArray): MeshNodeInfo? = parseNodeInfo(data)

    /** Parse telemetry (battery level) from FromRadio. */
    fun parseTelemetryFromRadio(data: ByteArray): MeshTelemetry? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractTelemetry(fromRadio)
    }

    /** Parse routing (ACK/NAK) from FromRadio. */
    fun parseRoutingFromRadio(data: ByteArray): MeshRouting? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractRouting(fromRadio)
    }

    /** Parse waypoint from FromRadio. */
    fun parseWaypointFromRadio(data: ByteArray): MeshWaypoint? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractWaypoint(fromRadio)
    }

    /** Parse neighbor info from FromRadio. */
    fun parseNeighborInfoFromRadio(data: ByteArray): MeshNeighborInfo? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractNeighborInfo(fromRadio)
    }

    /** Parse traceroute from FromRadio. */
    fun parseTracerouteFromRadio(data: ByteArray): MeshTraceroute? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractTraceroute(fromRadio)
    }

    /** Parse store-and-forward from FromRadio. */
    fun parseStoreForwardFromRadio(data: ByteArray): MeshStoreForward? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractStoreForward(fromRadio)
    }

    /** Parse environment telemetry from FromRadio. */
    fun parseEnvironmentTelemetryFromRadio(data: ByteArray): MeshEnvironmentTelemetry? {
        val fromRadio = MeshtasticProtoAdapter.parseFromRadio(data) ?: return null
        return MeshtasticProtoAdapter.extractEnvironmentTelemetry(fromRadio)
    }

    // ═══════════════════════════════════════════════════════════════
    // Encoding — delegates to adapter
    // ═══════════════════════════════════════════════════════════════

    /** Encode a text message as a ToRadio protobuf. */
    fun encodeTextMessage(text: String, to: Long = 0xFFFFFFFFL, channel: Int = 0): ByteArray =
        MeshtasticProtoAdapter.encodeTextMessage(text, to, channel)

    /** Format a node number as hex string (e.g., !27ca8f1c). */
    fun formatNodeId(num: Long): String = "!%08x".format(num)

    // ═══════════════════════════════════════════════════════════════
    // Admin message encoding — delegates to adapter
    // ═══════════════════════════════════════════════════════════════

    // Config section enum values (backward-compatible constants)
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

    /** Build a ToRadio admin message requesting a config section. */
    fun buildAdminGetConfig(myNodeNum: Long, configType: Int): ByteArray =
        MeshtasticProtoAdapter.buildAdminGetConfig(myNodeNum, configType)

    /** Build a ToRadio admin message requesting a module config section. */
    fun buildAdminGetModuleConfig(myNodeNum: Long, moduleType: Int): ByteArray =
        MeshtasticProtoAdapter.buildAdminGetModuleConfig(myNodeNum, moduleType)

    /** Build a ToRadio admin message to set a config section (raw bytes — legacy). */
    fun buildAdminSetConfig(myNodeNum: Long, configData: ByteArray): ByteArray {
        val config = ConfigProtos.Config.parseFrom(configData)
        return MeshtasticProtoAdapter.buildAdminSetConfig(myNodeNum, config)
    }

    /** Build a ToRadio admin message to set a module config section (raw bytes — legacy). */
    fun buildAdminSetModuleConfig(myNodeNum: Long, configData: ByteArray): ByteArray {
        val moduleConfig = ModuleConfigProtos.ModuleConfig.parseFrom(configData)
        return MeshtasticProtoAdapter.buildAdminSetModuleConfig(myNodeNum, moduleConfig)
    }

    /** Build a ToRadio admin message to reboot the radio after delaySecs. */
    fun buildAdminReboot(myNodeNum: Long, delaySecs: Int): ByteArray =
        MeshtasticProtoAdapter.buildAdminReboot(myNodeNum, delaySecs)

    /** Build a ToRadio admin message to shutdown the radio after delaySecs. */
    fun buildAdminShutdown(myNodeNum: Long, delaySecs: Int): ByteArray =
        MeshtasticProtoAdapter.buildAdminShutdown(myNodeNum, delaySecs)

    /** Build a ToRadio admin message to factory reset the radio. */
    fun buildAdminFactoryReset(myNodeNum: Long): ByteArray =
        MeshtasticProtoAdapter.buildAdminFactoryReset(myNodeNum)

    /** Build a ToRadio admin message to reset the node database. */
    fun buildAdminNodeDbReset(myNodeNum: Long): ByteArray =
        MeshtasticProtoAdapter.buildAdminNodeDbReset(myNodeNum)

    /** Build a ToRadio admin message to request device metadata. */
    fun buildAdminGetDeviceMetadata(myNodeNum: Long): ByteArray =
        MeshtasticProtoAdapter.buildAdminGetDeviceMetadata(myNodeNum)

    /** Build a ToRadio admin message to set the radio's time. */
    fun buildAdminSetTime(myNodeNum: Long, unixSec: Long): ByteArray =
        MeshtasticProtoAdapter.buildAdminSetTime(myNodeNum, unixSec)

    /** Build a ToRadio admin message to set the device owner/user info. */
    fun buildAdminSetOwner(myNodeNum: Long, longName: String, shortName: String, isLicensed: Boolean = false): ByteArray =
        MeshtasticProtoAdapter.buildAdminSetOwner(myNodeNum, longName, shortName, isLicensed)

    /** Build a ToRadio admin message to set a channel. */
    fun buildAdminSetChannel(myNodeNum: Long, channel: com.geeksville.mesh.ChannelProtos.Channel): ByteArray =
        MeshtasticProtoAdapter.buildAdminSetChannel(myNodeNum, channel)

    /** Build a ToRadio admin message to get a channel by index. */
    fun buildAdminGetChannel(myNodeNum: Long, channelIndex: Int): ByteArray =
        MeshtasticProtoAdapter.buildAdminGetChannel(myNodeNum, channelIndex)

    /** Build a ToRadio admin message to remove a node by number. */
    fun buildAdminRemoveNode(myNodeNum: Long, nodeNum: Long): ByteArray =
        MeshtasticProtoAdapter.buildAdminRemoveNode(myNodeNum, nodeNum)

    /** Build a ToRadio admin message to begin editing settings. */
    fun buildAdminBeginEditSettings(myNodeNum: Long): ByteArray =
        MeshtasticProtoAdapter.buildAdminBeginEditSettings(myNodeNum)

    /** Build a ToRadio admin message to commit edited settings. */
    fun buildAdminCommitEditSettings(myNodeNum: Long): ByteArray =
        MeshtasticProtoAdapter.buildAdminCommitEditSettings(myNodeNum)

    /** Encode a LoRa config as Config protobuf bytes (backward-compatible). */
    fun encodeLoRaConfig(
        region: Int,
        modemPreset: Int,
        txPower: Int,
        hopLimit: Int,
        txEnabled: Boolean,
    ): ByteArray = MeshtasticProtoAdapter.encodeLoRaConfig(region, modemPreset, txPower, hopLimit, txEnabled).toByteArray()

    /** Encode a waypoint as a ToRadio protobuf. */
    fun encodeWaypoint(
        name: String,
        description: String,
        latitudeI: Int,
        longitudeI: Int,
        expire: Long = 0,
        icon: Int = 0,
        to: Long = 0xFFFFFFFFL,
        channel: Int = 0,
    ): ByteArray = MeshtasticProtoAdapter.encodeWaypoint(name, description, latitudeI, longitudeI, expire, icon, to, channel)

    /** Encode a traceroute request as a ToRadio protobuf. */
    fun encodeTracerouteRequest(myNodeNum: Long, destNode: Long): ByteArray =
        MeshtasticProtoAdapter.encodeTracerouteRequest(myNodeNum, destNode)

    /** Request config from radio. */
    fun encodeWantConfig(configId: Int = 0): ByteArray =
        MeshtasticProtoAdapter.encodeWantConfig(configId)
}
