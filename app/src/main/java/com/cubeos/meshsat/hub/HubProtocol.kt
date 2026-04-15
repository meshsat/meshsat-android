package com.cubeos.meshsat.hub

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Hub uplink protocol types — meshsat-uplink/v1 (Sparkplug B inspired, CoT-native).
 *
 * Mirrors the Go bridge protocol in meshsat/internal/hubreporter/protocol.go.
 * Uses org.json for serialization (same as existing MqttTransport).
 */
object HubProtocol {
    const val VERSION = "meshsat-uplink/v1"

    // CoT type constants (MIL-STD-2525 symbology)
    const val COT_BRIDGE = "a-f-G-U-C-I"    // Friendly Ground Unit — Infrastructure
    const val COT_MESH_NODE = "a-f-G-U-C"    // Friendly Ground Unit
    const val COT_SAT_MODEM = "a-f-G-E-S"    // Friendly Ground Equipment — Sensor
    const val COT_CELL_MODEM = "a-f-G-E-C"   // Friendly Ground Equipment — Comms
    const val COT_MOBILE = "a-f-G-U-C"       // Friendly Ground Unit (mobile)
    const val COT_EMERGENCY = "b-a"           // Alarm/Emergency

    // Device type identifiers
    const val DEVICE_MESHTASTIC = "meshtastic_node"
    const val DEVICE_IRIDIUM_SBD = "iridium_sbd"
    const val DEVICE_IRIDIUM_IMT = "iridium_imt"
    const val DEVICE_CELLULAR = "cellular"
    const val DEVICE_APRS = "aprs"

    /** ISO 8601 timestamp formatter. */
    private val isoFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun isoTimestamp(date: Date = Date()): String = isoFormat.format(date)

    /** Returns CoT type string for a given device type. */
    fun cotTypeForDevice(deviceType: String): String = when (deviceType) {
        DEVICE_MESHTASTIC -> COT_MESH_NODE
        DEVICE_IRIDIUM_SBD, DEVICE_IRIDIUM_IMT -> COT_SAT_MODEM
        DEVICE_CELLULAR -> COT_CELL_MODEM
        else -> COT_MESH_NODE
    }
}

// --- Topic builders ---

object HubTopics {
    fun bridgeBirth(bridgeID: String) = "meshsat/bridge/$bridgeID/birth"
    fun bridgeDeath(bridgeID: String) = "meshsat/bridge/$bridgeID/death"
    fun bridgeHealth(bridgeID: String) = "meshsat/bridge/$bridgeID/health"
    fun bridgeCmd(bridgeID: String) = "meshsat/bridge/$bridgeID/cmd"
    fun bridgeCmdResponse(bridgeID: String) = "meshsat/bridge/$bridgeID/cmd/response"
    fun deviceBirth(bridgeID: String, deviceID: String) = "meshsat/bridge/$bridgeID/device/$deviceID/birth"
    fun deviceDeath(bridgeID: String, deviceID: String) = "meshsat/bridge/$bridgeID/device/$deviceID/death"
    fun devicePosition(deviceID: String) = "meshsat/$deviceID/position"
    fun deviceTelemetry(deviceID: String) = "meshsat/$deviceID/telemetry"
    fun deviceSOS(deviceID: String) = "meshsat/$deviceID/sos"
}

// --- Shared types ---

data class HubLocation(
    val lat: Double,
    val lon: Double,
    val alt: Double = 0.0,
    val source: String = "gps",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        put("alt", alt)
        put("source", source)
    }
}

data class InterfaceInfo(
    val name: String,
    val type: String,
    val status: String,
    val port: String = "",
    val imei: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("type", type)
        put("status", status)
        if (port.isNotEmpty()) put("port", port)
        if (imei.isNotEmpty()) put("imei", imei)
    }
}

data class InterfaceHealth(
    val name: String,
    val status: String,
    val healthScore: Int = 0,
    val signalBars: Int = 0,
    val signalDBm: Int = 0,
    val nodesSeen: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("status", status)
        if (healthScore > 0) put("health_score", healthScore)
        if (signalBars > 0) put("signal_bars", signalBars)
        if (signalDBm != 0) put("signal_dbm", signalDBm)
        if (nodesSeen > 0) put("nodes_seen", nodesSeen)
    }
}

// --- Bridge lifecycle messages ---

data class BridgeBirth(
    val bridgeId: String,
    val version: String,
    val hostname: String,
    val mode: String = "android",
    val tenantId: String = "default",
    val location: HubLocation? = null,
    val interfaces: List<InterfaceInfo> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val cotCallsign: String = "",
    val uptimeSec: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", HubProtocol.VERSION)
        put("bridge_id", bridgeId)
        put("version", version)
        put("hostname", hostname)
        put("mode", mode)
        put("tenant_id", tenantId)
        location?.let { put("location", it.toJson()) }
        put("interfaces", JSONArray().apply { interfaces.forEach { put(it.toJson()) } })
        put("capabilities", JSONArray(capabilities))
        put("cot_type", HubProtocol.COT_MOBILE)
        put("cot_callsign", cotCallsign.ifEmpty { hostname })
        put("uptime_sec", uptimeSec)
        put("timestamp", HubProtocol.isoTimestamp())
    }
}

data class BridgeDeath(
    val bridgeId: String,
    val reason: String = "shutdown",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", HubProtocol.VERSION)
        put("bridge_id", bridgeId)
        put("reason", reason)
        put("timestamp", HubProtocol.isoTimestamp())
    }
}

data class BridgeHealth(
    val bridgeId: String,
    val uptimeSec: Long,
    val batteryPct: Double = 0.0,
    val memPct: Double = 0.0,
    val diskPct: Double = 0.0,
    val interfaces: List<InterfaceHealth> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", HubProtocol.VERSION)
        put("bridge_id", bridgeId)
        put("uptime_sec", uptimeSec)
        put("cpu_pct", 0.0) // Not easily available on Android
        put("mem_pct", memPct)
        put("disk_pct", diskPct)
        put("battery_pct", batteryPct) // Android-specific extension
        put("interfaces", JSONArray().apply { interfaces.forEach { put(it.toJson()) } })
        put("timestamp", HubProtocol.isoTimestamp())
    }
}

// --- Device lifecycle messages ---

data class DeviceBirth(
    val deviceId: String,
    val bridgeId: String,
    val type: String,
    val label: String,
    val hardware: String = "",
    val firmware: String = "",
    val imei: String = "",
    val position: HubLocation? = null,
    val cotCallsign: String = "",
    val capabilities: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", HubProtocol.VERSION)
        put("device_id", deviceId)
        put("bridge_id", bridgeId)
        put("type", type)
        put("label", label)
        if (hardware.isNotEmpty()) put("hardware", hardware)
        if (firmware.isNotEmpty()) put("firmware", firmware)
        if (imei.isNotEmpty()) put("imei", imei)
        position?.let { put("position", it.toJson()) }
        put("cot_type", HubProtocol.cotTypeForDevice(type))
        put("cot_callsign", cotCallsign.ifEmpty { label })
        put("capabilities", JSONArray(capabilities))
        put("timestamp", HubProtocol.isoTimestamp())
    }
}

data class DeviceDeath(
    val deviceId: String,
    val bridgeId: String,
    val reason: String = "offline",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", HubProtocol.VERSION)
        put("device_id", deviceId)
        put("bridge_id", bridgeId)
        put("reason", reason)
        put("timestamp", HubProtocol.isoTimestamp())
    }
}

// --- Device telemetry ---

data class DevicePosition(
    val lat: Double,
    val lon: Double,
    val alt: Double = 0.0,
    val speed: Double = 0.0,
    val course: Double = 0.0,
    val source: String = "gps",
    val bridgeId: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        if (alt != 0.0) put("alt", alt)
        if (speed != 0.0) put("speed", speed)
        if (course != 0.0) put("course", course)
        put("source", source)
        if (bridgeId.isNotEmpty()) put("bridge_id", bridgeId)
        put("timestamp", HubProtocol.isoTimestamp())
    }
}

data class DeviceTelemetry(
    val batteryLevel: Double = 0.0,
    val voltage: Double = 0.0,
    val temperature: Double = 0.0,
    val uptimeSec: Long = 0,
    val bridgeId: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (batteryLevel != 0.0) put("battery_level", batteryLevel)
        if (voltage != 0.0) put("voltage", voltage)
        if (temperature != 0.0) put("temperature", temperature)
        if (uptimeSec != 0L) put("uptime_sec", uptimeSec)
        if (bridgeId.isNotEmpty()) put("bridge_id", bridgeId)
        put("timestamp", HubProtocol.isoTimestamp())
    }
}

// --- Command channel ---

data class HubCommand(
    val cmd: String,
    val requestId: String,
    val targetDevice: String = "",
    val payload: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject): HubCommand = HubCommand(
            cmd = json.optString("cmd", ""),
            requestId = json.optString("request_id", ""),
            targetDevice = json.optString("target_device", ""),
            payload = json.optString("payload", ""),
        )
    }
}

data class CommandResponse(
    val requestId: String,
    val cmd: String,
    val status: String,
    val error: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", HubProtocol.VERSION)
        put("request_id", requestId)
        put("cmd", cmd)
        put("status", status)
        if (error.isNotEmpty()) put("error", error)
        put("timestamp", HubProtocol.isoTimestamp())
    }
}
