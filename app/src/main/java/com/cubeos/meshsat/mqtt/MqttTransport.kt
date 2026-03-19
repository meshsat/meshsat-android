package com.cubeos.meshsat.mqtt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * MQTT transport for Hub connectivity.
 * Publishes device position, SOS, and telemetry to the Hub MQTT namespace.
 * Subscribes to MT send and TAK inbound topics for reverse-path messages.
 *
 * Topic namespace: meshsat/{deviceId}/...
 */
class MqttTransport(
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "MqttTransport"
        private const val QOS_AT_LEAST_ONCE: Int = 1
        private const val QOS_EXACTLY_ONCE: Int = 2
    }

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private var client: MqttClient? = null
    private var deviceId: String = ""
    private var onMessage: ((topic: String, payload: String) -> Unit)? = null

    /**
     * Set the callback for inbound messages (MT send, TAK events).
     */
    fun setMessageCallback(cb: (topic: String, payload: String) -> Unit) {
        onMessage = cb
    }

    /**
     * Connect to the Hub MQTT broker.
     * @param brokerUrl e.g. "tcp://hub.example.com:1883" or "ssl://hub.example.com:8883"
     * @param deviceId e.g. IMEI or user-configured device identifier
     * @param username optional auth username
     * @param password optional auth password
     * @param certPin optional primary cert pin (base64-encoded SHA-256 SPKI hash)
     * @param certPinBackup optional backup cert pin for rotation
     */
    fun connect(
        brokerUrl: String,
        deviceId: String,
        username: String = "",
        password: String = "",
        certPin: String = "",
        certPinBackup: String = "",
    ) {
        if (brokerUrl.isBlank() || deviceId.isBlank()) {
            Log.w(TAG, "Cannot connect: broker URL or device ID is blank")
            return
        }
        this.deviceId = deviceId
        _state.value = State.Connecting

        scope.launch {
            try {
                val clientId = "meshsat-android-${deviceId.takeLast(8)}"
                val mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    if (username.isNotBlank()) {
                        userName = username
                        this.password = password.toCharArray()
                    }
                    // Certificate pinning for SSL connections
                    val pinBuilder = CertificatePinner.Builder()
                        .addPin(certPin)
                        .addPin(certPinBackup)
                    if (pinBuilder.hasPins() && brokerUrl.startsWith("ssl://")) {
                        val (sslFactory, _) = pinBuilder.build().createSSLSocketFactory()
                        socketFactory = sslFactory
                        Log.i(TAG, "Certificate pinning enabled for Hub MQTT")
                    }
                }

                mqttClient.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Connection lost: ${cause?.message}")
                        _state.value = State.Disconnected
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val payload = String(message.payload, StandardCharsets.UTF_8)
                        Log.d(TAG, "Received on $topic: ${payload.take(100)}")
                        onMessage?.invoke(topic, payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient.connect(opts)
                client = mqttClient
                _state.value = State.Connected
                Log.i(TAG, "Connected to $brokerUrl as $clientId")

                // Subscribe to inbound topics
                subscribe()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}", e)
                _state.value = State.Error
            }
        }
    }

    /**
     * Disconnect from the broker.
     */
    fun disconnect() {
        try {
            client?.disconnect(1000)
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        }
        client = null
        _state.value = State.Disconnected
    }

    /**
     * Publish a position update to the Hub.
     */
    fun publishPosition(lat: Double, lon: Double, alt: Double = 0.0, source: String = "gps") {
        val json = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("alt", alt)
            put("source", source)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        publish(topicPosition(), QOS_AT_LEAST_ONCE, retained = true, json.toString())
    }

    /**
     * Publish an SOS event to the Hub.
     */
    fun publishSOS(triggered: Boolean, lat: Double = 0.0, lon: Double = 0.0) {
        val json = JSONObject().apply {
            put("triggered", triggered)
            put("lat", lat)
            put("lon", lon)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        publish(topicSOS(), QOS_EXACTLY_ONCE, retained = false, json.toString())
    }

    /**
     * Publish telemetry data to the Hub.
     */
    fun publishTelemetry(battery: Double, temperature: Double = 0.0, humidity: Double = 0.0) {
        val json = JSONObject().apply {
            put("battery", battery)
            put("temperature", temperature)
            put("humidity", humidity)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        publish(topicTelemetry(), QOS_AT_LEAST_ONCE, retained = true, json.toString())
    }

    /**
     * Publish a decoded MO message to the Hub (for TAK/APRS-IS forwarding).
     */
    fun publishMODecoded(text: String, channel: String = "mesh") {
        val json = JSONObject().apply {
            put("text", text)
            put("channel", channel)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        publish(topicMODecoded(), QOS_AT_LEAST_ONCE, retained = false, json.toString())
    }

    /**
     * Publish device health status.
     */
    fun publishHealth(batteryPct: Int, uptime: Long) {
        val json = JSONObject().apply {
            put("battery", batteryPct)
            put("uptime", uptime)
            put("last_seen", System.currentTimeMillis() / 1000)
        }
        publish(topicHealth(), QOS_AT_LEAST_ONCE, retained = true, json.toString())
    }

    /**
     * Publish a raw text message to an arbitrary topic.
     */
    fun publishRaw(topic: String, qos: Int, retained: Boolean, payload: String) {
        publish(topic, qos, retained, payload)
    }

    val isConnected: Boolean
        get() = client?.isConnected == true

    // --- Private helpers ---

    private fun subscribe() {
        val c = client ?: return
        val topics = arrayOf(
            topicMTSend(),
            topicTAKInbound(),
            topicConfigUpdate(),
        )
        val qos = IntArray(topics.size) { QOS_AT_LEAST_ONCE }
        try {
            c.subscribe(topics, qos)
            Log.i(TAG, "Subscribed to ${topics.size} topics")
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe failed: ${e.message}", e)
        }
    }

    private fun publish(topic: String, qos: Int, retained: Boolean, payload: String) {
        val c = client
        if (c == null || !c.isConnected) {
            Log.d(TAG, "Not connected, dropping publish to $topic")
            return
        }
        scope.launch {
            try {
                val msg = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    this.qos = qos
                    isRetained = retained
                }
                c.publish(topic, msg)
            } catch (e: Exception) {
                Log.w(TAG, "Publish to $topic failed: ${e.message}")
            }
        }
    }

    // --- Topic helpers ---

    private fun topicPosition() = "meshsat/$deviceId/position"
    private fun topicSOS() = "meshsat/$deviceId/sos"
    private fun topicTelemetry() = "meshsat/$deviceId/telemetry"
    private fun topicMODecoded() = "meshsat/$deviceId/mo/decoded"
    private fun topicHealth() = "meshsat/$deviceId/status/health"
    private fun topicMTSend() = "meshsat/$deviceId/mt/send"
    private fun topicTAKInbound() = "meshsat/$deviceId/tak/cot/in"
    private fun topicConfigUpdate() = "meshsat/$deviceId/config/update"
}
