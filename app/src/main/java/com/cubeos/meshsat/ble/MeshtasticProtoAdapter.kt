package com.cubeos.meshsat.ble

import com.geeksville.mesh.AdminProtos
import com.geeksville.mesh.ConfigProtos
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.Portnums
import com.geeksville.mesh.TelemetryProtos

/**
 * Adapter layer between official Meshtastic generated protobuf classes and
 * our internal data types. Keeps the rest of the codebase decoupled from
 * the raw protobuf API.
 */
object MeshtasticProtoAdapter {

    // ═══════════════════════════════════════════════════════════════
    // FromRadio parsing — decode raw bytes into typed results
    // ═══════════════════════════════════════════════════════════════

    /** Parse raw FromRadio bytes and return the contained payload variant. */
    fun parseFromRadio(data: ByteArray): MeshProtos.FromRadio? {
        return try {
            MeshProtos.FromRadio.parseFrom(data)
        } catch (e: Exception) {
            null
        }
    }

    /** Extract a text message from FromRadio, or null if not a text message. */
    fun extractTextMessage(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshTextMessage? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.TEXT_MESSAGE_APP) return null

        return MeshtasticProtocol.MeshTextMessage(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            to = pkt.to.toLong() and 0xFFFFFFFFL,
            channel = pkt.channel,
            portnum = decoded.portnumValue,
            text = decoded.payload.toStringUtf8(),
            id = pkt.id.toLong() and 0xFFFFFFFFL,
        )
    }

    /** Extract a position update from FromRadio, or null if not a position packet. */
    fun extractPosition(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshPosition? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.POSITION_APP) return null

        val pos = try {
            MeshProtos.Position.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        // Skip zero positions (no GPS fix)
        if (pos.latitudeI == 0 && pos.longitudeI == 0) return null

        return MeshtasticProtocol.MeshPosition(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            latitude = pos.latitudeI.toDouble() / 1e7,
            longitude = pos.longitudeI.toDouble() / 1e7,
            altitude = pos.altitude,
            time = if (pos.time != 0) pos.time.toLong() * 1000 else System.currentTimeMillis(),
        )
    }

    /** Extract telemetry (device metrics) from FromRadio. */
    fun extractTelemetry(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshTelemetry? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.TELEMETRY_APP) return null

        val telemetry = try {
            TelemetryProtos.Telemetry.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        val from = pkt.from.toLong() and 0xFFFFFFFFL
        if (!telemetry.hasDeviceMetrics()) {
            return MeshtasticProtocol.MeshTelemetry(from = from)
        }

        val dm = telemetry.deviceMetrics
        return MeshtasticProtocol.MeshTelemetry(
            from = from,
            batteryLevel = dm.batteryLevel,
            voltage = dm.voltage,
        )
    }

    /** Extract environment telemetry from FromRadio. */
    fun extractEnvironmentTelemetry(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshEnvironmentTelemetry? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.TELEMETRY_APP) return null

        val telemetry = try {
            TelemetryProtos.Telemetry.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        if (!telemetry.hasEnvironmentMetrics()) return null

        val em = telemetry.environmentMetrics
        return MeshtasticProtocol.MeshEnvironmentTelemetry(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            temperature = em.temperature,
            relativeHumidity = em.relativeHumidity,
            barometricPressure = em.barometricPressure,
            gasResistance = em.gasResistance,
        )
    }

    /** Extract MyNodeInfo from FromRadio. */
    fun extractMyInfo(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MyNodeInfo? {
        if (!fromRadio.hasMyInfo()) return null
        val mi = fromRadio.myInfo
        return MeshtasticProtocol.MyNodeInfo(
            myNodeNum = mi.myNodeNum.toLong() and 0xFFFFFFFFL,
            firmwareVersion = "",  // firmware_version removed from MyNodeInfo in recent protos; comes via DeviceMetadata
            rebootCount = mi.rebootCount,
            minAppVersion = mi.minAppVersion,
        )
    }

    /** Extract NodeInfo from FromRadio. */
    fun extractNodeInfo(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshNodeInfo? {
        if (!fromRadio.hasNodeInfo()) return null
        val ni = fromRadio.nodeInfo
        val user = if (ni.hasUser()) ni.user else null
        return MeshtasticProtocol.MeshNodeInfo(
            nodeNum = ni.num.toLong() and 0xFFFFFFFFL,
            longName = user?.longName ?: "",
            shortName = user?.shortName ?: "",
            macaddr = user?.macaddr?.toByteArray()?.joinToString(":") { "%02x".format(it) } ?: "",
            hwModel = user?.hwModelValue ?: 0,
            batteryLevel = if (ni.hasDeviceMetrics()) ni.deviceMetrics.batteryLevel else -1,
            lastHeard = if (ni.lastHeard != 0) ni.lastHeard.toLong() * 1000 else 0,
        )
    }

    /** Extract DeviceMetadata from FromRadio (admin response). */
    fun extractDeviceMetadata(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshDeviceMetadata? {
        if (!fromRadio.hasMetadata()) return null
        val md = fromRadio.metadata
        return MeshtasticProtocol.MeshDeviceMetadata(
            firmwareVersion = md.firmwareVersion,
            canShutdown = md.canShutdown,
            hasWifi = md.hasWifi,
            hasBluetooth = md.hasBluetooth,
            hasEthernet = md.hasEthernet,
            hwModel = md.hwModelValue,
        )
    }

    /** Extract routing info (ACK/NAK) from FromRadio. */
    fun extractRouting(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshRouting? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.ROUTING_APP) return null

        val routing = try {
            MeshProtos.Routing.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        return MeshtasticProtocol.MeshRouting(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            requestId = decoded.requestId.toLong() and 0xFFFFFFFFL,
            errorReason = if (routing.hasErrorReason()) routing.errorReason.number else 0,
            errorName = if (routing.hasErrorReason()) routing.errorReason.name else "NONE",
        )
    }

    /** Extract waypoint from FromRadio. */
    fun extractWaypoint(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshWaypoint? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.WAYPOINT_APP) return null

        val wp = try {
            MeshProtos.Waypoint.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        return MeshtasticProtocol.MeshWaypoint(
            id = wp.id.toLong() and 0xFFFFFFFFL,
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            name = wp.name,
            description = wp.description,
            latitude = wp.latitudeI.toDouble() / 1e7,
            longitude = wp.longitudeI.toDouble() / 1e7,
            expire = wp.expire.toLong() and 0xFFFFFFFFL,
            icon = wp.icon,
        )
    }

    /** Extract neighbor info from FromRadio. */
    fun extractNeighborInfo(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshNeighborInfo? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.NEIGHBORINFO_APP) return null

        val ni = try {
            MeshProtos.NeighborInfo.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        return MeshtasticProtocol.MeshNeighborInfo(
            nodeId = ni.nodeId.toLong() and 0xFFFFFFFFL,
            neighbors = ni.neighborsList.map { n ->
                MeshtasticProtocol.MeshNeighbor(
                    nodeId = n.nodeId.toLong() and 0xFFFFFFFFL,
                    snr = n.snr,
                )
            },
        )
    }

    /** Extract traceroute from FromRadio. */
    fun extractTraceroute(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshTraceroute? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.TRACEROUTE_APP) return null

        val rd = try {
            MeshProtos.RouteDiscovery.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        return MeshtasticProtocol.MeshTraceroute(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            requestId = decoded.requestId.toLong() and 0xFFFFFFFFL,
            route = rd.routeList.map { it.toLong() and 0xFFFFFFFFL },
            snrTowards = rd.snrTowardsList,
            snrBack = rd.snrBackList,
        )
    }

    /** Extract store-and-forward message from FromRadio. */
    fun extractStoreForward(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshStoreForward? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.STORE_FORWARD_APP) return null

        val sf = try {
            AdminProtos.StoreAndForward.parseFrom(decoded.payload)
        } catch (e: Exception) {
            return null
        }

        return MeshtasticProtocol.MeshStoreForward(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            requestResponse = sf.rr.number,
            requestResponseName = sf.rr.name,
            text = if (sf.hasText()) sf.text.toStringUtf8() else null,
            messagesTotal = if (sf.hasStats()) sf.stats.messagesTotal else 0,
            messagesSaved = if (sf.hasStats()) sf.stats.messagesSaved else 0,
        )
    }

    /** Extract range test payload from FromRadio. */
    fun extractRangeTest(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshRangeTest? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.RANGE_TEST_APP) return null

        return MeshtasticProtocol.MeshRangeTest(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            payload = decoded.payload.toStringUtf8(),
            rxSnr = pkt.rxSnr,
            rxRssi = pkt.rxRssi,
        )
    }

    /** Extract detection sensor alert from FromRadio. */
    fun extractDetectionSensor(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshDetectionSensor? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.DETECTION_SENSOR_APP) return null

        return MeshtasticProtocol.MeshDetectionSensor(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            name = decoded.payload.toStringUtf8(),
        )
    }

    /** Extract paxcounter data from FromRadio. */
    fun extractPaxcounter(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshPaxcounter? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.PAXCOUNTER_APP) return null

        // Paxcounter payload is a simple varint-encoded message
        return MeshtasticProtocol.MeshPaxcounter(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
        )
    }

    /** Extract reply (emoji reaction) from FromRadio. */
    fun extractReply(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshReply? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        val decoded = pkt.decoded
        if (decoded.portnum != Portnums.PortNum.REPLY_APP) return null

        return MeshtasticProtocol.MeshReply(
            from = pkt.from.toLong() and 0xFFFFFFFFL,
            to = pkt.to.toLong() and 0xFFFFFFFFL,
            payload = decoded.payload.toStringUtf8(),
            emoji = decoded.emoji,
        )
    }

    /** Get the raw portnum from a FromRadio packet, or null if not a mesh packet. */
    fun getPortnum(fromRadio: MeshProtos.FromRadio): Portnums.PortNum? {
        if (!fromRadio.hasPacket()) return null
        val pkt = fromRadio.packet
        if (!pkt.hasDecoded()) return null
        return pkt.decoded.portnum
    }

    /** Get the channel config from FromRadio. */
    fun extractChannel(fromRadio: MeshProtos.FromRadio): MeshtasticProtocol.MeshChannel? {
        if (!fromRadio.hasChannel()) return null
        val ch = fromRadio.channel
        val settings = if (ch.hasSettings()) ch.settings else null
        return MeshtasticProtocol.MeshChannel(
            index = ch.index,
            name = settings?.name ?: "",
            role = ch.role.number,
            psk = settings?.psk?.toByteArray() ?: ByteArray(0),
            uplinkEnabled = settings?.uplinkEnabled ?: false,
            downlinkEnabled = settings?.downlinkEnabled ?: false,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ToRadio encoding — build outgoing protobuf messages
    // ═══════════════════════════════════════════════════════════════

    /** Encode a text message as a ToRadio protobuf. */
    fun encodeTextMessage(text: String, to: Long = 0xFFFFFFFFL, channel: Int = 0): ByteArray {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(com.google.protobuf.ByteString.copyFromUtf8(text))
            .build()

        val meshPacket = MeshProtos.MeshPacket.newBuilder()
            .setTo(to.toInt())
            .setChannel(channel)
            .setDecoded(data)
            .build()

        return MeshProtos.ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
            .toByteArray()
    }

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
    ): ByteArray {
        val wp = MeshProtos.Waypoint.newBuilder()
            .setName(name)
            .setDescription(description)
            .setLatitudeI(latitudeI)
            .setLongitudeI(longitudeI)
            .setExpire(expire.toInt())
            .setIcon(icon)
            .build()

        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.WAYPOINT_APP)
            .setPayload(wp.toByteString())
            .build()

        val meshPacket = MeshProtos.MeshPacket.newBuilder()
            .setTo(to.toInt())
            .setChannel(channel)
            .setDecoded(data)
            .build()

        return MeshProtos.ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
            .toByteArray()
    }

    /** Build a traceroute request ToRadio. */
    fun encodeTracerouteRequest(myNodeNum: Long, destNode: Long): ByteArray {
        val rd = MeshProtos.RouteDiscovery.newBuilder().build()

        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TRACEROUTE_APP)
            .setPayload(rd.toByteString())
            .setWantResponse(true)
            .build()

        val meshPacket = MeshProtos.MeshPacket.newBuilder()
            .setFrom(myNodeNum.toInt())
            .setTo(destNode.toInt())
            .setDecoded(data)
            .setHopLimit(7)
            .setWantAck(true)
            .build()

        return MeshProtos.ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
            .toByteArray()
    }

    /** Request config from radio (want_config_id). */
    fun encodeWantConfig(configId: Int = 0): ByteArray {
        return MeshProtos.ToRadio.newBuilder()
            .setWantConfigId(configId)
            .build()
            .toByteArray()
    }

    // ═══════════════════════════════════════════════════════════════
    // Admin message encoding — radio config + device admin commands
    // ═══════════════════════════════════════════════════════════════

    /** Build a ToRadio admin message requesting a config section. */
    fun buildAdminGetConfig(myNodeNum: Long, configType: Int): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setGetConfigRequest(AdminProtos.AdminMessage.ConfigType.forNumber(configType)
                ?: AdminProtos.AdminMessage.ConfigType.DEVICE_CONFIG)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message requesting a module config section. */
    fun buildAdminGetModuleConfig(myNodeNum: Long, moduleType: Int): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setGetModuleConfigRequest(AdminProtos.AdminMessage.ModuleConfigType.forNumber(moduleType)
                ?: AdminProtos.AdminMessage.ModuleConfigType.MQTT_CONFIG)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to set a config section. */
    fun buildAdminSetConfig(myNodeNum: Long, config: ConfigProtos.Config): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetConfig(config)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to set a module config section. */
    fun buildAdminSetModuleConfig(myNodeNum: Long, moduleConfig: com.geeksville.mesh.ModuleConfigProtos.ModuleConfig): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetModuleConfig(moduleConfig)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to set a channel. */
    fun buildAdminSetChannel(myNodeNum: Long, channel: com.geeksville.mesh.ChannelProtos.Channel): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetChannel(channel)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to get a channel by index. */
    fun buildAdminGetChannel(myNodeNum: Long, channelIndex: Int): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setGetChannelRequest(channelIndex)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to reboot the radio after delaySecs. */
    fun buildAdminReboot(myNodeNum: Long, delaySecs: Int): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setRebootSeconds(delaySecs)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to shutdown the radio after delaySecs. */
    fun buildAdminShutdown(myNodeNum: Long, delaySecs: Int): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setShutdownSeconds(delaySecs)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to factory reset the radio. */
    fun buildAdminFactoryReset(myNodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setFactoryResetDevice(1)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to reset the node database. */
    fun buildAdminNodeDbReset(myNodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setNodedbReset(1)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to factory reset config only. */
    fun buildAdminFactoryResetConfig(myNodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setFactoryResetConfig(1)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to request device metadata. */
    fun buildAdminGetDeviceMetadata(myNodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setGetDeviceMetadataRequest(true)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to set the radio's time. */
    fun buildAdminSetTime(myNodeNum: Long, unixSec: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetTimeOnly(unixSec.toInt())
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to set the device owner/user info. */
    fun buildAdminSetOwner(myNodeNum: Long, longName: String, shortName: String, isLicensed: Boolean = false): ByteArray {
        val user = MeshProtos.User.newBuilder()
            .setLongName(longName)
            .setShortName(shortName)
            .setIsLicensed(isLicensed)
            .build()
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetOwner(user)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to remove a node by number. */
    fun buildAdminRemoveNode(myNodeNum: Long, nodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setRemoveByNodenum(nodeNum.toInt())
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to set/unset a favorite node. */
    fun buildAdminSetFavoriteNode(myNodeNum: Long, nodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setSetFavoriteNode(nodeNum.toInt())
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to begin editing settings. */
    fun buildAdminBeginEditSettings(myNodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setBeginEditSettings(true)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /** Build a ToRadio admin message to commit edited settings. */
    fun buildAdminCommitEditSettings(myNodeNum: Long): ByteArray {
        val admin = AdminProtos.AdminMessage.newBuilder()
            .setCommitEditSettings(true)
            .build()
        return wrapAdminToRadio(myNodeNum, myNodeNum, admin)
    }

    /**
     * Encode a LoRa config as Config protobuf.
     */
    fun encodeLoRaConfig(
        region: Int,
        modemPreset: Int,
        txPower: Int,
        hopLimit: Int,
        txEnabled: Boolean,
    ): ConfigProtos.Config {
        val lora = ConfigProtos.Config.LoRaConfig.newBuilder()
            .setRegion(ConfigProtos.Config.LoRaConfig.RegionCode.forNumber(region)
                ?: ConfigProtos.Config.LoRaConfig.RegionCode.UNSET)
            .setModemPreset(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(modemPreset)
                ?: ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST)
            .setTxPower(txPower)
            .setHopLimit(hopLimit)
            .setTxEnabled(txEnabled)
            .build()

        return ConfigProtos.Config.newBuilder()
            .setLora(lora)
            .build()
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal: wrap admin payload into ToRadio
    // ═══════════════════════════════════════════════════════════════

    private fun wrapAdminToRadio(
        myNodeNum: Long,
        destNode: Long,
        admin: AdminProtos.AdminMessage,
    ): ByteArray {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ADMIN_APP)
            .setPayload(admin.toByteString())
            .setWantResponse(true)
            .build()

        val meshPacket = MeshProtos.MeshPacket.newBuilder()
            .setFrom(myNodeNum.toInt())
            .setTo(destNode.toInt())
            .setDecoded(data)
            .setHopLimit(3)
            .setWantAck(true)
            .build()

        return MeshProtos.ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
            .toByteArray()
    }
}
