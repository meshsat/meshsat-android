package com.cubeos.meshsat.hub

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.cubeos.meshsat.BuildConfig
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.mqtt.CertificatePinner
import com.cubeos.meshsat.service.GatewayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * HubReporter implements the meshsat-uplink/v1 protocol for Android.
 *
 * Publishes bridge birth/death/health lifecycle events and device telemetry
 * to the Hub MQTT broker, so the Android app appears as a mobile field node
 * in the Hub fleet dashboard and TAK map.
 *
 * This is the Kotlin parity implementation of the Go bridge's
 * internal/hubreporter/reporter.go.
 */
class HubReporter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: HubReporterConfig,
) {
    companion object {
        private const val TAG = "HubReporter"
        private const val QOS_FIRE_AND_FORGET = 0
        private const val QOS_AT_LEAST_ONCE = 1
    }

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private var client: MqttClient? = null
    private var healthJob: Job? = null
    private val startTime = SystemClock.elapsedRealtime()

    // Track published device births for death on shutdown
    private val activeDevices = mutableSetOf<String>()

    private var onCommand: ((HubCommand) -> Unit)? = null

    /** Set callback for inbound commands from the Hub. */
    fun setCommandCallback(cb: (HubCommand) -> Unit) {
        onCommand = cb
    }

    /** Connect to Hub, publish birth, start health loop. */
    fun start() {
        if (config.hubUrl.isBlank() || config.bridgeId.isBlank()) {
            Log.w(TAG, "Cannot start: Hub URL or bridge ID not configured")
            return
        }
        _state.value = State.Connecting

        scope.launch {
            try {
                val clientId = "meshsat-android-${config.bridgeId.takeLast(12)}"
                val mqttClient = MqttClient(config.hubUrl, clientId, MemoryPersistence())

                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    maxInflight = 20

                    if (config.username.isNotBlank()) {
                        userName = config.username
                        password = config.password.toCharArray()
                    }

                    // Set LWT (Last Will and Testament) — death message on ungraceful disconnect
                    val lwt = BridgeDeath(bridgeId = config.bridgeId, reason = "lwt")
                    setWill(
                        HubTopics.bridgeDeath(config.bridgeId),
                        lwt.toJson().toString().toByteArray(StandardCharsets.UTF_8),
                        QOS_AT_LEAST_ONCE,
                        false,
                    )

                    // Certificate pinning for SSL connections
                    val pinBuilder = CertificatePinner.Builder()
                        .addPin(config.certPin)
                        .addPin(config.certPinBackup)
                    if (pinBuilder.hasPins() && config.hubUrl.startsWith("ssl://")) {
                        val (sslFactory, _) = pinBuilder.build().createSSLSocketFactory()
                        socketFactory = sslFactory
                        Log.i(TAG, "Certificate pinning enabled for Hub MQTT")
                    }
                }

                mqttClient.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Hub connection lost: ${cause?.message}")
                        _state.value = State.Disconnected
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val payload = String(message.payload, StandardCharsets.UTF_8)
                        Log.d(TAG, "Hub cmd on $topic: ${payload.take(200)}")
                        handleInbound(topic, payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient.connect(opts)
                client = mqttClient
                _state.value = State.Connected
                Log.i(TAG, "Connected to Hub at ${config.hubUrl}")

                // Subscribe to command topic
                mqttClient.subscribe(
                    HubTopics.bridgeCmd(config.bridgeId),
                    QOS_AT_LEAST_ONCE,
                )

                // Publish birth certificate
                publishBirth()

                // Start periodic health reporting
                startHealthLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Hub connect failed: ${e.message}", e)
                _state.value = State.Error
            }
        }
    }

    /** Publish death, disconnect from Hub. */
    fun stop() {
        healthJob?.cancel()
        healthJob = null

        val c = client
        if (c != null && c.isConnected) {
            try {
                // Publish death for all active devices
                for (deviceId in activeDevices.toSet()) {
                    publishDeviceDeath(deviceId, "bridge_shutdown")
                }
                activeDevices.clear()

                // Publish bridge death
                val death = BridgeDeath(bridgeId = config.bridgeId, reason = "shutdown")
                publish(
                    HubTopics.bridgeDeath(config.bridgeId),
                    QOS_AT_LEAST_ONCE,
                    retained = false,
                    death.toJson().toString(),
                )

                c.disconnect(2000)
            } catch (e: Exception) {
                Log.w(TAG, "Hub disconnect error: ${e.message}")
            }
        }
        client = null
        _state.value = State.Disconnected
    }

    val isConnected: Boolean
        get() = client?.isConnected == true

    // --- Device lifecycle ---

    /** Publish a device birth certificate. */
    fun publishDeviceBirth(birth: DeviceBirth) {
        activeDevices.add(birth.deviceId)
        publish(
            HubTopics.deviceBirth(config.bridgeId, birth.deviceId),
            QOS_AT_LEAST_ONCE,
            retained = false,
            birth.toJson().toString(),
        )
    }

    /** Publish a device death notice. */
    fun publishDeviceDeath(deviceId: String, reason: String = "offline") {
        activeDevices.remove(deviceId)
        val death = DeviceDeath(
            deviceId = deviceId,
            bridgeId = config.bridgeId,
            reason = reason,
        )
        publish(
            HubTopics.deviceDeath(config.bridgeId, deviceId),
            QOS_AT_LEAST_ONCE,
            retained = false,
            death.toJson().toString(),
        )
    }

    // --- Device telemetry ---

    /** Publish device position to meshsat/{deviceId}/position. */
    fun publishDevicePosition(deviceId: String, position: DevicePosition) {
        publish(
            HubTopics.devicePosition(deviceId),
            QOS_AT_LEAST_ONCE,
            retained = true,
            position.toJson().toString(),
        )
    }

    /** Publish device telemetry to meshsat/{deviceId}/telemetry. */
    fun publishDeviceTelemetry(deviceId: String, telemetry: DeviceTelemetry) {
        publish(
            HubTopics.deviceTelemetry(deviceId),
            QOS_AT_LEAST_ONCE,
            retained = true,
            telemetry.toJson().toString(),
        )
    }

    /** Respond to a Hub command. */
    fun publishCommandResponse(response: CommandResponse) {
        publish(
            HubTopics.bridgeCmdResponse(config.bridgeId),
            QOS_AT_LEAST_ONCE,
            retained = false,
            response.toJson().toString(),
        )
    }

    // --- Internal ---

    private fun publishBirth() {
        val birth = buildBirthCertificate()
        publish(
            HubTopics.bridgeBirth(config.bridgeId),
            QOS_AT_LEAST_ONCE,
            retained = true,
            birth.toJson().toString(),
        )
        Log.i(TAG, "Published bridge birth: ${config.bridgeId}")
    }

    private fun buildBirthCertificate(): BridgeBirth {
        val ifaces = collectInterfaces()
        val caps = collectCapabilities()
        val loc = collectLocation()
        val uptime = (SystemClock.elapsedRealtime() - startTime) / 1000

        return BridgeBirth(
            bridgeId = config.bridgeId,
            version = BuildConfig.VERSION_NAME,
            hostname = Build.MODEL,
            mode = "android",
            tenantId = "default",
            location = loc,
            interfaces = ifaces,
            capabilities = caps,
            cotCallsign = config.callsign.ifEmpty { Build.MODEL },
            uptimeSec = uptime,
        )
    }

    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (true) {
                delay(config.healthIntervalSec * 1000L)
                if (!isConnected) continue
                try {
                    publishHealth()
                } catch (e: Exception) {
                    Log.w(TAG, "Health publish failed: ${e.message}")
                }
            }
        }
    }

    private fun publishHealth() {
        val uptime = (SystemClock.elapsedRealtime() - startTime) / 1000
        val battery = getBatteryLevel()
        val mem = getMemoryUsage()
        val disk = getDiskUsage()
        val ifaceHealthList = collectInterfaceHealth()

        val health = BridgeHealth(
            bridgeId = config.bridgeId,
            uptimeSec = uptime,
            batteryPct = battery,
            memPct = mem,
            diskPct = disk,
            interfaces = ifaceHealthList,
        )
        publish(
            HubTopics.bridgeHealth(config.bridgeId),
            QOS_FIRE_AND_FORGET,
            retained = false,
            health.toJson().toString(),
        )
    }

    private fun handleInbound(topic: String, payload: String) {
        if (!topic.endsWith("/cmd")) return
        try {
            val json = JSONObject(payload)
            val cmd = HubCommand.fromJson(json)
            Log.i(TAG, "Hub command: ${cmd.cmd} (${cmd.requestId})")

            // Handle ping internally
            if (cmd.cmd == "ping") {
                publishCommandResponse(CommandResponse(
                    requestId = cmd.requestId,
                    cmd = "ping",
                    status = "ok",
                ))
                return
            }

            // Forward to external handler
            onCommand?.invoke(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Hub command: ${e.message}")
        }
    }

    // --- System metrics ---

    private fun getBatteryLevel(): Double {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0) (level.toDouble() / scale * 100) else 0.0
    }

    private fun getMemoryUsage(): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0.0
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return if (memInfo.totalMem > 0) {
            ((memInfo.totalMem - memInfo.availMem).toDouble() / memInfo.totalMem * 100)
        } else 0.0
    }

    private fun getDiskUsage(): Double {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            if (total > 0) ((total - free).toDouble() / total * 100) else 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    private fun collectLocation(): HubLocation? {
        val loc = GatewayService.phoneLocation.value ?: return null
        return HubLocation(
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else 0.0,
            source = "gps",
        )
    }

    private fun collectInterfaces(): List<InterfaceInfo> {
        val ifaces = mutableListOf<InterfaceInfo>()

        // BLE mesh
        GatewayService.meshtasticBle?.let { ble ->
            val status = when (ble.state.value) {
                MeshtasticBle.State.Connected -> "online"
                MeshtasticBle.State.Connecting -> "binding"
                else -> "offline"
            }
            ifaces.add(InterfaceInfo(
                name = "ble_mesh_0",
                type = "meshtastic",
                status = status,
            ))
        }

        // Iridium 9603 SPP
        GatewayService.iridiumSpp?.let { spp ->
            val status = when (spp.state.value) {
                IridiumSpp.State.Connected -> "online"
                IridiumSpp.State.Connecting -> "binding"
                else -> "offline"
            }
            ifaces.add(InterfaceInfo(
                name = "iridium_spp_0",
                type = "iridium_sbd",
                status = status,
            ))
        }

        // Iridium 9704 SPP
        GatewayService.iridium9704Spp?.let { spp ->
            val status = when (spp.state.value) {
                com.cubeos.meshsat.bt.Iridium9704Spp.State.Connected -> "online"
                com.cubeos.meshsat.bt.Iridium9704Spp.State.Connecting -> "binding"
                else -> "offline"
            }
            ifaces.add(InterfaceInfo(
                name = "iridium_imt_0",
                type = "iridium_imt",
                status = status,
            ))
        }

        // Astrocast SPP
        GatewayService.astrocastSpp?.let { spp ->
            val status = when (spp.state.value) {
                com.cubeos.meshsat.astrocast.AstrocastSpp.State.Connected -> "online"
                com.cubeos.meshsat.astrocast.AstrocastSpp.State.Connecting -> "binding"
                else -> "offline"
            }
            ifaces.add(InterfaceInfo(
                name = "astrocast_0",
                type = "astrocast",
                status = status,
            ))
        }

        // MQTT (existing device transport)
        GatewayService.mqttTransport?.let { mqtt ->
            val status = when (mqtt.state.value) {
                com.cubeos.meshsat.mqtt.MqttTransport.State.Connected -> "online"
                else -> "offline"
            }
            ifaces.add(InterfaceInfo(
                name = "mqtt_0",
                type = "mqtt",
                status = status,
            ))
        }

        return ifaces
    }

    private fun collectCapabilities(): List<String> {
        val caps = mutableListOf("android", "gps", "battery")

        GatewayService.meshtasticBle?.let { caps.add("ble_mesh") }
        GatewayService.iridiumSpp?.let { caps.add("iridium_sbd") }
        GatewayService.iridium9704Spp?.let { caps.add("iridium_imt") }
        GatewayService.astrocastSpp?.let { caps.add("astrocast") }
        GatewayService.mqttTransport?.let { caps.add("mqtt") }
        GatewayService.kissClient?.let { caps.add("aprs_kiss") }
        GatewayService.aprsIsClient?.let { caps.add("aprs_is") }
        GatewayService.rnsTcpInterface?.let { caps.add("reticulum_tcp") }

        return caps
    }

    private fun collectInterfaceHealth(): List<InterfaceHealth> {
        val health = mutableListOf<InterfaceHealth>()

        GatewayService.meshtasticBle?.let { ble ->
            val status = if (ble.state.value == MeshtasticBle.State.Connected) "online" else "offline"
            health.add(InterfaceHealth(name = "ble_mesh_0", status = status))
        }

        GatewayService.iridiumSpp?.let { spp ->
            val status = if (spp.state.value == IridiumSpp.State.Connected) "online" else "offline"
            val bars = spp.signal.value
            health.add(InterfaceHealth(
                name = "iridium_spp_0",
                status = status,
                signalBars = bars,
            ))
        }

        GatewayService.iridium9704Spp?.let { spp ->
            val status = if (spp.state.value == com.cubeos.meshsat.bt.Iridium9704Spp.State.Connected) "online" else "offline"
            val bars = spp.signal.value
            health.add(InterfaceHealth(
                name = "iridium_imt_0",
                status = status,
                signalBars = bars,
            ))
        }

        GatewayService.astrocastSpp?.let { spp ->
            val status = if (spp.state.value == com.cubeos.meshsat.astrocast.AstrocastSpp.State.Connected) "online" else "offline"
            health.add(InterfaceHealth(name = "astrocast_0", status = status))
        }

        return health
    }

    private fun publish(topic: String, qos: Int, retained: Boolean, payload: String) {
        val c = client
        if (c == null || !c.isConnected) {
            Log.d(TAG, "Hub not connected, dropping publish to $topic")
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
                Log.w(TAG, "Hub publish to $topic failed: ${e.message}")
            }
        }
    }
}

/**
 * Configuration for HubReporter.
 */
data class HubReporterConfig(
    val hubUrl: String,
    val bridgeId: String,
    val callsign: String = "",
    val username: String = "",
    val password: String = "",
    val certPin: String = "",
    val certPinBackup: String = "",
    val healthIntervalSec: Int = 30,
    val enabled: Boolean = true,
)
