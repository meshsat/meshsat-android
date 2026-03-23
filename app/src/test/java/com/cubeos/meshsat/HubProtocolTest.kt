package com.cubeos.meshsat

import com.cubeos.meshsat.hub.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Hub uplink protocol types (MESHSAT-292).
 *
 * Note: org.json.JSONObject is stubbed in Android unit tests (returns null/0),
 * so we test data class construction and topic builders only. JSON serialization
 * is validated by the Hub integration on real devices.
 */
class HubProtocolTest {

    @Test
    fun `protocol version matches Go bridge`() {
        assertEquals("meshsat-uplink/v1", HubProtocol.VERSION)
    }

    @Test
    fun `CoT constants match Go bridge`() {
        assertEquals("a-f-G-U-C-I", HubProtocol.COT_BRIDGE)
        assertEquals("a-f-G-U-C", HubProtocol.COT_MESH_NODE)
        assertEquals("a-f-G-E-S", HubProtocol.COT_SAT_MODEM)
        assertEquals("a-f-G-E-C", HubProtocol.COT_CELL_MODEM)
        assertEquals("a-f-G-U-C", HubProtocol.COT_MOBILE)
        assertEquals("b-a", HubProtocol.COT_EMERGENCY)
    }

    @Test
    fun `device type constants match Go bridge`() {
        assertEquals("meshtastic_node", HubProtocol.DEVICE_MESHTASTIC)
        assertEquals("iridium_sbd", HubProtocol.DEVICE_IRIDIUM_SBD)
        assertEquals("iridium_imt", HubProtocol.DEVICE_IRIDIUM_IMT)
        assertEquals("cellular", HubProtocol.DEVICE_CELLULAR)
        assertEquals("astrocast", HubProtocol.DEVICE_ASTROCAST)
        assertEquals("aprs", HubProtocol.DEVICE_APRS)
    }

    @Test
    fun `topic builders produce correct paths`() {
        assertEquals("meshsat/bridge/pi-01/birth", HubTopics.bridgeBirth("pi-01"))
        assertEquals("meshsat/bridge/pi-01/death", HubTopics.bridgeDeath("pi-01"))
        assertEquals("meshsat/bridge/pi-01/health", HubTopics.bridgeHealth("pi-01"))
        assertEquals("meshsat/bridge/pi-01/cmd", HubTopics.bridgeCmd("pi-01"))
        assertEquals("meshsat/bridge/pi-01/cmd/response", HubTopics.bridgeCmdResponse("pi-01"))
        assertEquals("meshsat/bridge/pi-01/device/mesh-abc/birth", HubTopics.deviceBirth("pi-01", "mesh-abc"))
        assertEquals("meshsat/bridge/pi-01/device/mesh-abc/death", HubTopics.deviceDeath("pi-01", "mesh-abc"))
        assertEquals("meshsat/dev-01/position", HubTopics.devicePosition("dev-01"))
        assertEquals("meshsat/dev-01/telemetry", HubTopics.deviceTelemetry("dev-01"))
        assertEquals("meshsat/dev-01/sos", HubTopics.deviceSOS("dev-01"))
    }

    @Test
    fun `cot type for device returns correct types`() {
        assertEquals(HubProtocol.COT_MESH_NODE, HubProtocol.cotTypeForDevice(HubProtocol.DEVICE_MESHTASTIC))
        assertEquals(HubProtocol.COT_SAT_MODEM, HubProtocol.cotTypeForDevice(HubProtocol.DEVICE_IRIDIUM_SBD))
        assertEquals(HubProtocol.COT_SAT_MODEM, HubProtocol.cotTypeForDevice(HubProtocol.DEVICE_IRIDIUM_IMT))
        assertEquals(HubProtocol.COT_SAT_MODEM, HubProtocol.cotTypeForDevice(HubProtocol.DEVICE_ASTROCAST))
        assertEquals(HubProtocol.COT_CELL_MODEM, HubProtocol.cotTypeForDevice(HubProtocol.DEVICE_CELLULAR))
        assertEquals(HubProtocol.COT_MESH_NODE, HubProtocol.cotTypeForDevice("unknown"))
    }

    @Test
    fun `bridge birth data class construction`() {
        val birth = BridgeBirth(
            bridgeId = "android-test",
            version = "1.3.4",
            hostname = "Pixel 7",
            mode = "android",
            interfaces = listOf(InterfaceInfo("ble_mesh_0", "meshtastic", "online")),
            capabilities = listOf("android", "gps", "ble_mesh"),
            cotCallsign = "ALPHA-1",
            uptimeSec = 3600,
        )
        assertEquals("android-test", birth.bridgeId)
        assertEquals("1.3.4", birth.version)
        assertEquals("Pixel 7", birth.hostname)
        assertEquals("android", birth.mode)
        assertEquals("default", birth.tenantId)
        assertEquals("ALPHA-1", birth.cotCallsign)
        assertEquals(3600L, birth.uptimeSec)
        assertEquals(1, birth.interfaces.size)
        assertEquals(3, birth.capabilities.size)
    }

    @Test
    fun `bridge death data class construction`() {
        val death = BridgeDeath(bridgeId = "android-test", reason = "shutdown")
        assertEquals("android-test", death.bridgeId)
        assertEquals("shutdown", death.reason)
    }

    @Test
    fun `bridge health data class construction`() {
        val health = BridgeHealth(
            bridgeId = "android-test",
            uptimeSec = 3600,
            batteryPct = 85.0,
            memPct = 42.5,
            diskPct = 60.0,
            interfaces = listOf(InterfaceHealth("ble_mesh_0", "online", signalBars = 3)),
        )
        assertEquals("android-test", health.bridgeId)
        assertEquals(3600L, health.uptimeSec)
        assertEquals(85.0, health.batteryPct, 0.01)
        assertEquals(42.5, health.memPct, 0.01)
        assertEquals(60.0, health.diskPct, 0.01)
        assertEquals(1, health.interfaces.size)
    }

    @Test
    fun `device birth data class construction`() {
        val birth = DeviceBirth(
            deviceId = "!abcd1234",
            bridgeId = "android-test",
            type = HubProtocol.DEVICE_MESHTASTIC,
            label = "Heltec V3",
            hardware = "heltec-v3",
            firmware = "2.3.0",
            capabilities = listOf("text", "position"),
            cotCallsign = "MESH-01",
        )
        assertEquals("!abcd1234", birth.deviceId)
        assertEquals("android-test", birth.bridgeId)
        assertEquals("meshtastic_node", birth.type)
        assertEquals("Heltec V3", birth.label)
        assertEquals("heltec-v3", birth.hardware)
        assertEquals("2.3.0", birth.firmware)
        assertEquals("MESH-01", birth.cotCallsign)
        assertEquals(2, birth.capabilities.size)
    }

    @Test
    fun `device position data class construction`() {
        val pos = DevicePosition(
            lat = 37.7749,
            lon = -122.4194,
            alt = 10.0,
            speed = 1.5,
            course = 270.0,
            source = "gps",
            bridgeId = "android-test",
        )
        assertEquals(37.7749, pos.lat, 0.0001)
        assertEquals(-122.4194, pos.lon, 0.0001)
        assertEquals(10.0, pos.alt, 0.01)
        assertEquals(1.5, pos.speed, 0.01)
        assertEquals(270.0, pos.course, 0.01)
        assertEquals("gps", pos.source)
        assertEquals("android-test", pos.bridgeId)
    }

    @Test
    fun `device telemetry data class construction`() {
        val telem = DeviceTelemetry(
            batteryLevel = 95.0,
            voltage = 4.2,
            temperature = 25.0,
            uptimeSec = 7200,
            bridgeId = "android-test",
        )
        assertEquals(95.0, telem.batteryLevel, 0.01)
        assertEquals(4.2, telem.voltage, 0.01)
        assertEquals(25.0, telem.temperature, 0.01)
        assertEquals(7200L, telem.uptimeSec)
        assertEquals("android-test", telem.bridgeId)
    }

    @Test
    fun `command response data class construction`() {
        val resp = CommandResponse(
            requestId = "req-123",
            cmd = "ping",
            status = "ok",
        )
        assertEquals("req-123", resp.requestId)
        assertEquals("ping", resp.cmd)
        assertEquals("ok", resp.status)
        assertTrue(resp.error.isEmpty())
    }

    @Test
    fun `command response with error`() {
        val resp = CommandResponse(
            requestId = "req-456",
            cmd = "unknown",
            status = "error",
            error = "unsupported command",
        )
        assertEquals("error", resp.status)
        assertEquals("unsupported command", resp.error)
    }

    @Test
    fun `hub location data class construction`() {
        val loc = HubLocation(lat = 51.5074, lon = -0.1278, alt = 11.0, source = "gps")
        assertEquals(51.5074, loc.lat, 0.0001)
        assertEquals(-0.1278, loc.lon, 0.0001)
        assertEquals(11.0, loc.alt, 0.01)
        assertEquals("gps", loc.source)
    }

    @Test
    fun `interface info data class construction`() {
        val info = InterfaceInfo("ble_mesh_0", "meshtastic", "online")
        assertEquals("ble_mesh_0", info.name)
        assertEquals("meshtastic", info.type)
        assertEquals("online", info.status)
        assertTrue(info.port.isEmpty())
        assertTrue(info.imei.isEmpty())
    }

    @Test
    fun `interface info with optional fields`() {
        val info = InterfaceInfo("iridium_0", "iridium_sbd", "online", port = "/dev/ttyUSB0", imei = "300234010000001")
        assertEquals("/dev/ttyUSB0", info.port)
        assertEquals("300234010000001", info.imei)
    }

    @Test
    fun `interface health data class construction`() {
        val health = InterfaceHealth(
            name = "iridium_0",
            status = "online",
            healthScore = 85,
            signalBars = 4,
            signalDBm = -75,
            nodesSeen = 3,
        )
        assertEquals("iridium_0", health.name)
        assertEquals("online", health.status)
        assertEquals(85, health.healthScore)
        assertEquals(4, health.signalBars)
        assertEquals(-75, health.signalDBm)
        assertEquals(3, health.nodesSeen)
    }

    @Test
    fun `hub reporter config defaults`() {
        val config = HubReporterConfig(
            hubUrl = "tcp://hub.meshsat.net:1883",
            bridgeId = "test-01",
        )
        assertEquals("tcp://hub.meshsat.net:1883", config.hubUrl)
        assertEquals("test-01", config.bridgeId)
        assertEquals("", config.callsign)
        assertEquals("", config.username)
        assertEquals("", config.password)
        assertEquals(30, config.healthIntervalSec)
        assertTrue(config.enabled)
    }
}
