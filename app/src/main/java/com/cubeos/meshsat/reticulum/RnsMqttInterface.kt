package com.cubeos.meshsat.reticulum

import android.util.Base64
import android.util.Log
import com.cubeos.meshsat.mqtt.MqttTransport

/**
 * Reticulum interface over MQTT (Hub connectivity).
 *
 * Publishes Reticulum packets on meshsat/{deviceId}/reticulum/tx.
 * Receives packets from Hub via the existing MQTT message callback —
 * the Hub publishes relayed packets to meshsat/{deviceId}/reticulum/rx.
 *
 * MTU: Reticulum standard (500 bytes). Cost: free. Latency: <1s.
 *
 * [MESHSAT-220]
 */
class RnsMqttInterface(
    private val mqtt: MqttTransport,
    private val deviceId: () -> String,
    override val interfaceId: String = "mqtt_0",
) : RnsInterface {

    override val name: String = "MQTT Hub"
    override val mtu: Int = RnsConstants.MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 100

    override val isOnline: Boolean
        get() = mqtt.state.value == MqttTransport.State.Connected

    private var receiveCallback: RnsReceiveCallback? = null

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "MQTT interface offline"

        return try {
            val topic = "meshsat/${deviceId()}/reticulum/tx"
            val payload = Base64.encodeToString(packet, Base64.NO_WRAP)
            mqtt.publishRaw(topic, qos = 1, retained = false, payload = payload)
            Log.d(TAG, "RNS packet sent via MQTT: ${packet.size}B to $topic")
            null
        } catch (e: Exception) {
            Log.w(TAG, "MQTT send failed: ${e.message}")
            e.message ?: "MQTT send failed"
        }
    }

    /**
     * Process an incoming MQTT message that may be a Reticulum packet.
     *
     * Called from GatewayService's MQTT message callback when the topic
     * matches the Reticulum RX pattern. The Hub publishes base64-encoded
     * RNS packets to meshsat/{deviceId}/reticulum/rx.
     *
     * @param topic MQTT topic
     * @param payload MQTT payload (base64-encoded RNS packet)
     * @return true if this was a Reticulum packet
     */
    fun processIncomingMessage(topic: String, payload: String): Boolean {
        if (!topic.endsWith("/reticulum/rx")) return false

        return try {
            val packet = Base64.decode(payload, Base64.NO_WRAP)
            receiveCallback?.onReceive(interfaceId, packet)
            Log.d(TAG, "RNS packet received via MQTT: ${packet.size}B")
            true
        } catch (e: Exception) {
            Log.d(TAG, "MQTT RNS decode failed: ${e.message}")
            false
        }
    }

    override suspend fun start() {
        // MQTT subscription for reticulum/rx topic is handled by GatewayService
        // when it configures the MQTT connection. The message callback routes
        // matching topics to processIncomingMessage().
        Log.d(TAG, "RNS MQTT interface started for device ${deviceId()}")
    }

    override suspend fun stop() {
        Log.d(TAG, "RNS MQTT interface stopped")
    }

    companion object {
        private const val TAG = "RnsMqttInterface"

        /** Topic suffix for outbound Reticulum packets (Android → Hub). */
        const val TOPIC_TX_SUFFIX = "/reticulum/tx"

        /** Topic suffix for inbound Reticulum packets (Hub → Android). */
        const val TOPIC_RX_SUFFIX = "/reticulum/rx"
    }
}
