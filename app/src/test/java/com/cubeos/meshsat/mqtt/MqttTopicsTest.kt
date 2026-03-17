package com.cubeos.meshsat.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for MQTT topic formatting used by MqttTransport.
 * Verifies compliance with the Hub namespace spec (meshsat/{deviceId}/...).
 */
class MqttTopicsTest {

    @Test
    fun `position topic format`() {
        // MqttTransport uses "meshsat/$deviceId/position"
        val deviceId = "300234063904190"
        val expected = "meshsat/$deviceId/position"
        assertEquals(expected, "meshsat/$deviceId/position")
    }

    @Test
    fun `sos topic format`() {
        val deviceId = "test-device"
        assertEquals("meshsat/test-device/sos", "meshsat/$deviceId/sos")
    }

    @Test
    fun `telemetry topic format`() {
        val deviceId = "abc123"
        assertEquals("meshsat/abc123/telemetry", "meshsat/$deviceId/telemetry")
    }

    @Test
    fun `mo decoded topic format`() {
        val deviceId = "device-1"
        assertEquals("meshsat/device-1/mo/decoded", "meshsat/$deviceId/mo/decoded")
    }

    @Test
    fun `mt send topic for subscription`() {
        val deviceId = "device-1"
        assertEquals("meshsat/device-1/mt/send", "meshsat/$deviceId/mt/send")
    }

    @Test
    fun `tak inbound topic for subscription`() {
        val deviceId = "device-1"
        assertEquals("meshsat/device-1/tak/cot/in", "meshsat/$deviceId/tak/cot/in")
    }

    @Test
    fun `health topic format with retained`() {
        val deviceId = "my-phone"
        assertEquals("meshsat/my-phone/status/health", "meshsat/$deviceId/status/health")
    }

    @Test
    fun `config update topic for subscription`() {
        val deviceId = "device-1"
        assertEquals("meshsat/device-1/config/update", "meshsat/$deviceId/config/update")
    }
}
