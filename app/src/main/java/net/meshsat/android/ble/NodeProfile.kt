package net.meshsat.android.ble

import com.geeksville.mesh.AdminProtos
import com.geeksville.mesh.ChannelProtos
import com.geeksville.mesh.ConfigProtos.Config
import com.geeksville.mesh.MeshProtos
import com.google.protobuf.ByteString
import java.security.MessageDigest

/**
 * A whole node configuration, applied in one go (MESHSAT-1285).
 *
 * The settings screens cover what a person changes in the field: a name, a region, a channel.
 * Making two nodes the same for a stand, or stating what a node is really set to, needs the rest
 * as well - its role, how often it announces itself, whether it listens to MQTT, its power
 * settings - and needs it without a second tool on a second machine.
 *
 * Every field is optional and a field left out is left alone on the node. Nothing here guesses:
 * a role or region the app does not know is refused, because the alternative is a radio quietly
 * set to something nobody asked for.
 */
data class NodeProfile(
    val longName: String? = null,
    val shortName: String? = null,
    val role: String? = null,
    val nodeInfoBroadcastSecs: Int? = null,
    val region: String? = null,
    val preset: String? = null,
    val txPower: Int? = null,
    val txEnabled: Boolean? = null,
    val hopLimit: Int? = null,
    val rxBoostedGain: Boolean? = null,
    val ignoreMqtt: Boolean? = null,
    val channelName: String? = null,
    val channelPsk: ByteArray? = null,
    val channelUplink: Boolean? = null,
    val channelDownlink: Boolean? = null,
    /** 0 stops the node sharing a position on the channel at all; 32 is full precision. */
    val channelPositionPrecision: Int? = null,
    val gpsMode: String? = null,
    val positionBroadcastSecs: Int? = null,
    val positionSmart: Boolean? = null,
    val powerSaving: Boolean? = null,
    /** Seconds before super deep sleep; [SDS_NEVER] is the firmware's "never". */
    val sdsSecs: Long? = null,
    val bluetoothEnabled: Boolean? = null,
    val bluetoothFixedPin: Int? = null,
    val ntpServer: String? = null,
)

/** What the node says it is set to now. A profile is written over these, never over blanks. */
data class NodeSections(
    val device: Config.DeviceConfig?,
    val lora: Config.LoRaConfig?,
    val position: Config.PositionConfig?,
    val power: Config.PowerConfig?,
    val bluetooth: Config.BluetoothConfig?,
    val network: Config.NetworkConfig?,
    val primaryChannel: ChannelProtos.Channel?,
)

object NodeProfiles {

    const val SDS_NEVER = 0xFFFFFFFFL

    sealed class Plan {
        /** [messages] go to the node in order; [sections] names what they touch, for the log. */
        data class Ready(val messages: List<AdminProtos.AdminMessage>, val sections: List<String>) : Plan()
        data class Refused(val reason: String) : Plan()
    }

    /**
     * The admin messages that turn [now] into [profile], wrapped in one begin/commit edit so the
     * node saves once and restarts once. A section the profile does not mention is not sent. A
     * section the node has not reported yet cannot be written: sending a fresh one would reset
     * every field in it that the profile leaves out.
     */
    fun plan(now: NodeSections, profile: NodeProfile): Plan {
        val out = mutableListOf<AdminProtos.AdminMessage>()
        val touched = mutableListOf<String>()
        fun config(name: String, build: Config.Builder.() -> Unit) {
            out += AdminProtos.AdminMessage.newBuilder()
                .setSetConfig(Config.newBuilder().apply(build).build())
                .build()
            touched += name
        }
        fun missing(name: String) = Plan.Refused("The node has not reported its $name settings yet. Read the node first.")

        if (profile.longName != null || profile.shortName != null) {
            val long = profile.longName ?: return Plan.Refused("A name needs both the long and the short name.")
            val short = profile.shortName ?: return Plan.Refused("A name needs both the long and the short name.")
            if (short.length > 4) return Plan.Refused("The short name is at most 4 characters.")
            out += AdminProtos.AdminMessage.newBuilder()
                .setSetOwner(
                    MeshProtos.User.newBuilder().setLongName(long).setShortName(short),
                )
                .build()
            touched += "name"
        }

        if (profile.role != null || profile.nodeInfoBroadcastSecs != null) {
            val b = (now.device ?: return missing("device")).toBuilder()
            profile.role?.let { name ->
                val role = Config.DeviceConfig.Role.values().firstOrNull { it.name == name }
                    ?: return Plan.Refused("Unknown role: $name")
                b.setRole(role)
            }
            profile.nodeInfoBroadcastSecs?.let { b.setNodeInfoBroadcastSecs(it) }
            config("device") { setDevice(b) }
        }

        val loraAsked = listOf(
            profile.region, profile.preset, profile.txPower, profile.txEnabled,
            profile.hopLimit, profile.rxBoostedGain, profile.ignoreMqtt,
        ).any { it != null }
        if (loraAsked) {
            val b = (now.lora ?: return missing("radio")).toBuilder()
            profile.region?.let { name ->
                val region = Config.LoRaConfig.RegionCode.values().firstOrNull { it.name == name }
                    ?: return Plan.Refused("Unknown region: $name")
                b.setRegion(region)
            }
            profile.preset?.let { name ->
                val preset = Config.LoRaConfig.ModemPreset.values().firstOrNull { it.name == name }
                    ?: return Plan.Refused("Unknown preset: $name")
                b.setUsePreset(true).setModemPreset(preset)
            }
            profile.txPower?.let { b.setTxPower(it) }
            profile.txEnabled?.let { b.setTxEnabled(it) }
            profile.hopLimit?.let {
                if (it !in 1..7) return Plan.Refused("Hops are 1 to 7.")
                b.setHopLimit(it)
            }
            profile.rxBoostedGain?.let { b.setSx126XRxBoostedGain(it) }
            profile.ignoreMqtt?.let { b.setIgnoreMqtt(it) }
            config("radio") { setLora(b) }
        }

        if (profile.gpsMode != null || profile.positionBroadcastSecs != null || profile.positionSmart != null) {
            val b = (now.position ?: return missing("position")).toBuilder()
            profile.gpsMode?.let { name ->
                val mode = Config.PositionConfig.GpsMode.values().firstOrNull { it.name == name }
                    ?: return Plan.Refused("Unknown GPS mode: $name")
                b.setGpsMode(mode)
            }
            profile.positionBroadcastSecs?.let { b.setPositionBroadcastSecs(it) }
            profile.positionSmart?.let { b.setPositionBroadcastSmartEnabled(it) }
            config("position") { setPosition(b) }
        }

        if (profile.powerSaving != null || profile.sdsSecs != null) {
            val b = (now.power ?: return missing("power")).toBuilder()
            profile.powerSaving?.let { b.setIsPowerSaving(it) }
            profile.sdsSecs?.let { b.setSdsSecs(it.toInt()) }
            config("power") { setPower(b) }
        }

        if (profile.bluetoothEnabled != null || profile.bluetoothFixedPin != null) {
            val b = (now.bluetooth ?: return missing("Bluetooth")).toBuilder()
            profile.bluetoothEnabled?.let { b.setEnabled(it) }
            profile.bluetoothFixedPin?.let {
                if (it !in 100_000..999_999) return Plan.Refused("A fixed PIN is six digits.")
                b.setMode(Config.BluetoothConfig.PairingMode.FIXED_PIN).setFixedPin(it)
            }
            config("Bluetooth") { setBluetooth(b) }
        }

        if (profile.ntpServer != null) {
            val b = (now.network ?: return missing("network")).toBuilder()
            b.setNtpServer(profile.ntpServer)
            config("network") { setNetwork(b) }
        }

        val channelAsked = listOf(
            profile.channelName, profile.channelPsk, profile.channelUplink,
            profile.channelDownlink, profile.channelPositionPrecision,
        ).any { it != null }
        if (channelAsked) {
            val channel = now.primaryChannel ?: return missing("channel")
            val s = channel.settings.toBuilder()
            profile.channelName?.let {
                if (it.toByteArray().size > 11) return Plan.Refused("A channel name is at most 11 bytes.")
                s.setName(it)
            }
            profile.channelPsk?.let {
                if (it.size !in setOf(0, 1, 16, 32)) return Plan.Refused("A channel key is 16 or 32 bytes.")
                s.setPsk(ByteString.copyFrom(it))
            }
            profile.channelUplink?.let { s.setUplinkEnabled(it) }
            profile.channelDownlink?.let { s.setDownlinkEnabled(it) }
            profile.channelPositionPrecision?.let {
                if (it !in 0..32) return Plan.Refused("Position precision is 0 to 32.")
                s.setModuleSettings(s.moduleSettings.toBuilder().setPositionPrecision(it))
            }
            out += AdminProtos.AdminMessage.newBuilder()
                .setSetChannel(
                    channel.toBuilder()
                        .setIndex(0)
                        .setRole(ChannelProtos.Channel.Role.PRIMARY)
                        .setSettings(s),
                )
                .build()
            touched += "channel"
        }

        if (out.isEmpty()) return Plan.Refused("The profile changes nothing.")
        val begin = AdminProtos.AdminMessage.newBuilder().setBeginEditSettings(true).build()
        val commit = AdminProtos.AdminMessage.newBuilder().setCommitEditSettings(true).build()
        return Plan.Ready(listOf(begin) + out + commit, touched)
    }

    /**
     * A key as it may be shown: the first eight bytes of its SHA-256, enough to tell two keys
     * apart and to check one against a note, useless for reading the channel.
     */
    fun keyFingerprint(psk: ByteArray): String = when {
        psk.isEmpty() -> "none"
        psk.size == 1 -> "default (not private)"
        else -> MessageDigest.getInstance("SHA-256").digest(psk).take(8)
            .joinToString("") { "%02x".format(it) }
            .chunked(4).joinToString(" ") + " (${psk.size * 8}-bit)"
    }
}
