package com.cubeos.meshsat.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cubeos.meshsat.MainActivity
import com.cubeos.meshsat.MeshSatApp
import com.cubeos.meshsat.R
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.ble.MeshtasticProtocol
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.channel.ChannelRegistry
import com.cubeos.meshsat.channel.registerAndroidDefaults
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.crypto.MsvqscCodebook
import com.cubeos.meshsat.crypto.MsvqscEncoder
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ForwardingRuleEntity
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.data.NodePosition
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.data.SignalRecord
import com.cubeos.meshsat.engine.AckTracker
import com.cubeos.meshsat.engine.Dispatcher
import com.cubeos.meshsat.engine.FailoverResolver
import com.cubeos.meshsat.engine.IridiumFragment
import com.cubeos.meshsat.engine.InterfaceConfig
import com.cubeos.meshsat.engine.InterfaceManager
import com.cubeos.meshsat.engine.InterfaceState
import com.cubeos.meshsat.engine.InterfaceStatusProvider
import com.cubeos.meshsat.engine.SequenceTracker
import com.cubeos.meshsat.api.LocalApiServer
import com.cubeos.meshsat.config.ConfigManager
import com.cubeos.meshsat.routing.KeyValueStore
import com.cubeos.meshsat.rules.AccessEvaluator
import com.cubeos.meshsat.rules.ForwardingRule
import com.cubeos.meshsat.rules.RouteMessage
import com.cubeos.meshsat.rules.RulesEngine
import com.cubeos.meshsat.aprs.AprsCodec
import com.cubeos.meshsat.aprs.AprsConfig
import com.cubeos.meshsat.aprs.AprsPacket
import com.cubeos.meshsat.aprs.Ax25Address
import com.cubeos.meshsat.aprs.Ax25Codec
import com.cubeos.meshsat.aprs.KissClient
import com.cubeos.meshsat.codec.ProtocolVersion
import com.cubeos.meshsat.mqtt.MqttTransport
import com.cubeos.meshsat.sms.SmsSender
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps Bluetooth connections alive
 * for Meshtastic BLE and Iridium SPP (HC-05).
 *
 * Manages transport lifecycle, message routing via RulesEngine,
 * signal history tracking, node position storage, notifications, and SOS.
 */
class GatewayService : Service() {

    companion object {
        const val ACTION_CONNECT_MESH = "com.cubeos.meshsat.CONNECT_MESH"
        const val ACTION_CONNECT_IRIDIUM = "com.cubeos.meshsat.CONNECT_IRIDIUM"
        const val ACTION_CONNECT_IRIDIUM9704 = "com.cubeos.meshsat.CONNECT_IRIDIUM9704"
        const val ACTION_DISCONNECT_MESH = "com.cubeos.meshsat.DISCONNECT_MESH"
        const val ACTION_DISCONNECT_IRIDIUM = "com.cubeos.meshsat.DISCONNECT_IRIDIUM"
        const val ACTION_DISCONNECT_IRIDIUM9704 = "com.cubeos.meshsat.DISCONNECT_IRIDIUM9704"
        const val ACTION_CONNECT_ASTROCAST = "com.cubeos.meshsat.CONNECT_ASTROCAST"
        const val ACTION_DISCONNECT_ASTROCAST = "com.cubeos.meshsat.DISCONNECT_ASTROCAST"
        const val ACTION_SOS_ACTIVATE = "com.cubeos.meshsat.SOS_ACTIVATE"
        const val ACTION_SOS_CANCEL = "com.cubeos.meshsat.SOS_CANCEL"
        const val ACTION_SEND_MESH = "com.cubeos.meshsat.SEND_MESH"
        const val ACTION_SEND_IRIDIUM = "com.cubeos.meshsat.SEND_IRIDIUM"
        const val ACTION_SEND_SMS = "com.cubeos.meshsat.SEND_SMS"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_TEXT = "text"
        const val EXTRA_RECIPIENT = "recipient"

        // Singleton references for UI state observation
        var meshtasticBle: MeshtasticBle? = null
            private set
        var iridiumSpp: IridiumSpp? = null
            private set
        var iridium9704Spp: com.cubeos.meshsat.bt.Iridium9704Spp? = null
            private set
        var astrocastSpp: com.cubeos.meshsat.astrocast.AstrocastSpp? = null
            private set
        val rulesEngine = RulesEngine()

        // SOS state
        private val _sosActive = MutableStateFlow(false)
        val sosActive: StateFlow<Boolean> = _sosActive
        private val _sosSends = MutableStateFlow(0)
        val sosSends: StateFlow<Int> = _sosSends

        // Phone GPS location (updated continuously)
        private val _phoneLocation = MutableStateFlow<Location?>(null)
        val phoneLocation: StateFlow<Location?> = _phoneLocation

        // Phase D: field intelligence singletons (exposed for UI observation)
        var geofenceMonitor: com.cubeos.meshsat.engine.GeofenceMonitor? = null
            private set
        var deadManSwitch: com.cubeos.meshsat.engine.DeadManSwitch? = null
            private set
        var burstQueue: com.cubeos.meshsat.engine.BurstQueue? = null
            private set
        var healthScorer: com.cubeos.meshsat.engine.HealthScorer? = null
            private set

        // Phase H: exposed for bridge rules UI reload
        var accessEval: AccessEvaluator? = null
            private set

        // Phase I: exposed for interface management UI
        var ifaceManager: InterfaceManager? = null
            private set
        var channelReg: ChannelRegistry? = null
            private set
        // Hub MQTT transport
        var mqttTransport: MqttTransport? = null
            private set
        // APRS transport (KISS TCP or APRS-IS)
        var kissClient: KissClient? = null
            private set
        var aprsIsClient: com.cubeos.meshsat.aprs.AprsIsClient? = null
            private set

        // Phase J: exposed for audit log UI
        var signingServiceRef: com.cubeos.meshsat.engine.SigningService? = null
            private set

        // TAK/CoT integration (MESHSAT-191)
        var takIntegration: com.cubeos.meshsat.tak.TakIntegration? = null
            private set

        // APRS beacon (MESHSAT-231)
        var aprsBeacon: com.cubeos.meshsat.aprs.AprsBeacon? = null
            private set

        // APRS directed message tracker (MESHSAT-232)
        var aprsMessageTracker: com.cubeos.meshsat.aprs.AprsMessageTracker? = null
            private set

        // Reticulum TCP interface (MESHSAT-268)
        var rnsTcpInterface: com.cubeos.meshsat.reticulum.RnsTcpInterface? = null
            private set

        // Reticulum Transport Node (MESHSAT-199/267)
        var rnsTransportNode: com.cubeos.meshsat.reticulum.RnsTransportNode? = null
            private set

        // Hub Reporter — bridge-to-hub uplink protocol (MESHSAT-292)
        var hubReporter: com.cubeos.meshsat.hub.HubReporter? = null
            private set

        private var notificationId = 100
    }

    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var db: AppDatabase
    private var sosJob: kotlinx.coroutines.Job? = null
    private var msvqscEncoder: MsvqscEncoder? = null

    // Phase A: core infrastructure (dedup + transform)
    private val deduplicator = com.cubeos.meshsat.dedup.Deduplicator()
    private val transformPipeline = com.cubeos.meshsat.engine.TransformPipeline()

    // Phase B: structured dispatch (replaces basic if/else routing)
    private var dispatcher: Dispatcher? = null
    private var accessEvaluator: AccessEvaluator? = null

    // SBD fragmentation: wrapping counter for Iridium 2-byte fragment header.
    private var iridiumMsgID = 0
    private fun nextMsgID(): Int = (iridiumMsgID++ and 0xFF)

    // Phase C: transport hardening
    private var interfaceManager: InterfaceManager? = null
    private var ackTracker: AckTracker? = null
    private val sequenceTracker = SequenceTracker()

    // Phase F: config, API, signing
    private var signingService: com.cubeos.meshsat.engine.SigningService? = null
    private var configManager: ConfigManager? = null
    private var localApiServer: LocalApiServer? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        db = AppDatabase.getInstance(this)

        // Initialize SecureKeyStore early (triggers key migration from old storage — MESHSAT-194)
        com.cubeos.meshsat.crypto.SecureKeyStore.getInstance(this)

        meshtasticBle = MeshtasticBle(this)
        iridiumSpp = IridiumSpp(this)
        iridium9704Spp = com.cubeos.meshsat.bt.Iridium9704Spp(this)
        astrocastSpp = com.cubeos.meshsat.astrocast.AstrocastSpp(this)

        startForegroundNotification()

        try {
            // Complete encryption key migration (remove from DataStore after secure store confirmed)
            scope.launch { settings.completeMigration() }
            loadRulesFromDb()
            deduplicator.startPruner(scope)
            initInterfaceManager()
            initDispatcher()
            initFieldIntelligence()
            initSigningAndApi()
            observeTransports()
            startSignalPolling()
            startLocationUpdates()
            initMsvqsc()
            initMqtt()
            initSmsRelay()
            initAprs()
            initRnsTcp()
            initReticulumTransportNode()
            initHubReporter()
        } catch (e: Exception) {
            Log.e("MeshSat", "Service init error (non-fatal): ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT_MESH -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                meshtasticBle?.connect(addr)
                scope.launch { settings.setMeshtasticBleAddress(addr) }
            }
            ACTION_CONNECT_IRIDIUM -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                iridiumSpp?.connect(addr)
                scope.launch { settings.setIridiumBtAddress(addr) }
            }
            ACTION_CONNECT_IRIDIUM9704 -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                iridium9704Spp?.connect(addr)
                scope.launch { settings.setIridium9704BtAddress(addr) }
            }
            ACTION_DISCONNECT_MESH -> meshtasticBle?.disconnect()
            ACTION_DISCONNECT_IRIDIUM -> iridiumSpp?.disconnect()
            ACTION_DISCONNECT_IRIDIUM9704 -> iridium9704Spp?.disconnect()
            ACTION_CONNECT_ASTROCAST -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                astrocastSpp?.connect(addr)
            }
            ACTION_DISCONNECT_ASTROCAST -> astrocastSpp?.disconnect()
            ACTION_SOS_ACTIVATE -> activateSos()
            ACTION_SOS_CANCEL -> cancelSos()
            ACTION_SEND_MESH -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                sendMeshMessage(text)
            }
            ACTION_SEND_IRIDIUM -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                sendIridiumMessage(text)
            }
            ACTION_SEND_SMS -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                val recipient = intent.getStringExtra(EXTRA_RECIPIENT) ?: return START_STICKY
                scope.launch { sendSmsMessage(text, recipient) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hubReporter?.stop()
        hubReporter = null
        aprsMessageTracker?.cancelAll()
        aprsMessageTracker = null
        aprsBeacon?.stop()
        aprsBeacon = null
        aprsIsClient?.disconnect()
        aprsIsClient = null
        takIntegration = null
        localApiServer?.stop()
        localApiServer = null
        signingService = null
        signingServiceRef = null
        configManager = null
        deadManSwitch?.stop()
        deadManSwitch = null
        geofenceMonitor = null
        burstQueue = null
        healthScorer = null
        deduplicator.stopPruner()
        ackTracker?.stop()
        ackTracker = null
        dispatcher?.stop()
        dispatcher = null
        interfaceManager?.stopAll()
        interfaceManager = null
        ifaceManager = null
        channelReg = null
        accessEvaluator = null
        accessEval = null
        sosJob?.cancel()
        rnsTransportNode?.stop()
        rnsTransportNode = null
        meshtasticBle?.disconnect()
        iridiumSpp?.disconnect()
        iridium9704Spp?.disconnect()
        astrocastSpp?.disconnect()
        meshtasticBle = null
        iridiumSpp = null
        iridium9704Spp = null
        astrocastSpp = null
        msvqscEncoder?.close()
        msvqscEncoder = null
        com.cubeos.meshsat.sms.SmsReceiver.relayCallback = null
        scope.cancel()
        super.onDestroy()
    }

    /** Initialize Phase D: field intelligence components (geofence, dead man's switch, burst queue, health). */
    private fun initFieldIntelligence() {
        try {
            // Geofence monitor
            val gm = com.cubeos.meshsat.engine.GeofenceMonitor()
            geofenceMonitor = gm

            // Dead man's switch (default 2h timeout, disabled by default)
            val dms = com.cubeos.meshsat.engine.DeadManSwitch(
                positionDao = db.nodePositionDao(),
                timeout = kotlin.time.Duration.parse("2h"),
            )
            dms.sosCallback = { lat, lon, lastSeen ->
                android.util.Log.w("MeshSat", "Dead man's switch triggered at $lat,$lon (last seen: $lastSeen)")
                // Emit dead man CoT event to ATAK + Hub (MESHSAT-191)
                val elapsed = (System.currentTimeMillis() / 1000) - lastSeen
                takIntegration?.sendDeadman(lat, lon, elapsed.toInt())
                activateSos()
            }
            deadManSwitch = dms

            // Burst queue (max 10 messages, 5 min age)
            val bq = com.cubeos.meshsat.engine.BurstQueue(
                maxSize = 10,
                maxAge = kotlin.time.Duration.parse("5m"),
            )
            burstQueue = bq

            // Health scorer (needs interfaceManager + channel registry)
            val im = interfaceManager
            if (im != null) {
                val hs = com.cubeos.meshsat.engine.HealthScorer(
                    interfaceManager = im,
                    channelRegistry = com.cubeos.meshsat.channel.ChannelRegistry().also {
                        com.cubeos.meshsat.channel.registerAndroidDefaults(it)
                    },
                    signalDao = db.signalDao(),
                    deliveryDao = db.messageDeliveryDao(),
                )
                healthScorer = hs
            }

            android.util.Log.i("MeshSat", "Phase D field intelligence initialized")
        } catch (e: Exception) {
            android.util.Log.w("MeshSat", "Phase D init failed: ${e.message}")
        }
    }

    /**
     * Initialize Phase F: signing service, config manager, and local REST API server.
     * Uses SharedPreferences as KeyValueStore for Ed25519 keypair persistence.
     */
    private fun initSigningAndApi() {
        scope.launch {
            try {
                // KeyValueStore backed by hardware-secured Android Keystore (MESHSAT-194)
                val kvStore: KeyValueStore = com.cubeos.meshsat.crypto.SecureKeyStore.getInstance(this@GatewayService)

                // Signing service
                val signing = com.cubeos.meshsat.engine.SigningService(db.auditLogDao(), kvStore)
                signing.loadLastHash()
                signingService = signing
                signingServiceRef = signing
                Log.i("MeshSat", "SigningService initialized: ${signing.signerId.take(16)}...")

                // Config manager
                val cfgMgr = ConfigManager(db.accessRuleDao(), db.objectGroupDao(), db.failoverGroupDao())
                configManager = cfgMgr

                // Local API server (localhost:6051)
                val server = LocalApiServer(
                    scope = scope,
                    interfaceManager = interfaceManager,
                    channelRegistry = null, // Not stored as field yet; could be wired later
                    healthScorer = healthScorer,
                    deliveryDao = db.messageDeliveryDao(),
                    auditLogDao = db.auditLogDao(),
                    geofenceMonitor = geofenceMonitor,
                    deadManSwitch = deadManSwitch,
                    signingService = signing,
                    configManager = cfgMgr,
                )
                server.start()
                localApiServer = server
                Log.i("MeshSat", "Local API server started on 127.0.0.1:${LocalApiServer.DEFAULT_PORT}")
            } catch (e: Exception) {
                Log.w("MeshSat", "Phase F init failed (signing/API disabled): ${e.message}")
            }
        }
    }

    /** Initialize MSVQ-SC encoder in background (loads ONNX model + codebook). */
    private fun initMsvqsc() {
        scope.launch {
            try {
                val codebook = MsvqscCodebook.loadFromAssets(this@GatewayService)
                if (codebook != null) {
                    transformPipeline.msvqscCodebook = codebook
                    val encoder = MsvqscEncoder.loadFromAssets(this@GatewayService, codebook)
                    if (encoder != null) {
                        msvqscEncoder = encoder
                        transformPipeline.msvqscEncoder = encoder
                        Log.i("MeshSat", "MSVQ-SC encoder ready (${codebook.stages} stages, K=${codebook.k})")
                    }
                }
            } catch (e: Exception) {
                Log.w("MeshSat", "MSVQ-SC init failed (compression disabled): ${e.message}")
            }
        }
    }

    /** Initialize Hub MQTT transport if enabled in settings. */
    private fun initMqtt() {
        scope.launch {
            try {
                val enabled = settings.mqttEnabled.first()
                if (!enabled) {
                    Log.d("MeshSat", "MQTT Hub disabled in settings")
                    return@launch
                }
                val brokerUrl = settings.mqttBrokerUrl.first()
                val deviceId = settings.mqttDeviceId.first()
                if (brokerUrl.isBlank() || deviceId.isBlank()) {
                    Log.w("MeshSat", "MQTT: broker URL or device ID not configured")
                    return@launch
                }
                val username = settings.mqttUsername.first()
                val password = settings.mqttPassword.first()
                val certPin = settings.mqttCertPin.first()
                val certPinBackup = settings.mqttCertPinBackup.first()

                val transport = MqttTransport(scope)
                transport.setMessageCallback { topic, payload ->
                    handleMqttInbound(topic, payload)
                }
                transport.connect(brokerUrl, deviceId, username, password, certPin, certPinBackup)
                mqttTransport = transport

                // Initialize TAK/CoT integration (MESHSAT-191)
                takIntegration = com.cubeos.meshsat.tak.TakIntegration(
                    context = this@GatewayService,
                    mqtt = transport,
                    deviceId = deviceId,
                )
                Log.i("MeshSat", "TAK/CoT integration initialized: callsign=${takIntegration?.callsign}")

                // Observe state for InterfaceManager
                scope.launch {
                    transport.state.collect { state ->
                        when (state) {
                            MqttTransport.State.Connected ->
                                interfaceManager?.setOnline("mqtt_0")
                            MqttTransport.State.Error ->
                                interfaceManager?.setError("mqtt_0", "connection error")
                            MqttTransport.State.Disconnected ->
                                interfaceManager?.setError("mqtt_0", "disconnected")
                            else -> {}
                        }
                    }
                }

                Log.i("MeshSat", "MQTT Hub transport initialized")
            } catch (e: Exception) {
                Log.w("MeshSat", "MQTT init failed: ${e.message}")
            }
        }
    }

    /** Initialize Hub Reporter — bridge-to-hub uplink protocol (MESHSAT-292). */
    private fun initHubReporter() {
        scope.launch {
            try {
                val enabled = settings.hubEnabled.first()
                if (!enabled) {
                    Log.d("MeshSat", "Hub Reporter disabled in settings")
                    return@launch
                }
                val hubUrl = settings.hubUrl.first()
                val bridgeId = settings.hubBridgeId.first().ifEmpty {
                    android.provider.Settings.Secure.getString(
                        contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID,
                    ) ?: "android-unknown"
                }
                if (hubUrl.isBlank()) {
                    Log.w("MeshSat", "Hub Reporter: URL not configured")
                    return@launch
                }
                val callsign = settings.hubCallsign.first()
                val username = settings.hubUsername.first()
                val password = settings.hubPassword.first()
                val healthInterval = settings.hubHealthInterval.first().toIntOrNull() ?: 30
                val certPin = settings.mqttCertPin.first()
                val certPinBackup = settings.mqttCertPinBackup.first()

                val config = com.cubeos.meshsat.hub.HubReporterConfig(
                    hubUrl = hubUrl,
                    bridgeId = bridgeId,
                    callsign = callsign,
                    username = username,
                    password = password,
                    certPin = certPin,
                    certPinBackup = certPinBackup,
                    healthIntervalSec = healthInterval,
                )

                val reporter = com.cubeos.meshsat.hub.HubReporter(
                    context = this@GatewayService,
                    scope = scope,
                    config = config,
                )
                reporter.setCommandCallback { cmd ->
                    handleHubCommand(cmd)
                }
                reporter.start()
                hubReporter = reporter
                Log.i("MeshSat", "Hub Reporter initialized: bridge=$bridgeId")
            } catch (e: Exception) {
                Log.w("MeshSat", "Hub Reporter init failed: ${e.message}")
            }
        }
    }

    /** Handle inbound commands from the Hub via HubReporter. */
    private fun handleHubCommand(cmd: com.cubeos.meshsat.hub.HubCommand) {
        scope.launch {
            try {
                when (cmd.cmd) {
                    "send_text" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val text = json.optString("text", "")
                        if (text.isNotBlank()) {
                            val msg = com.cubeos.meshsat.rules.RouteMessage(
                                text = text, from = "hub", channel = 0, portNum = 1,
                                visited = listOf("hub_reporter"),
                            )
                            dispatcher?.dispatchAccess("hub_reporter", msg, text.toByteArray())
                        }
                        hubReporter?.publishCommandResponse(
                            com.cubeos.meshsat.hub.CommandResponse(
                                requestId = cmd.requestId,
                                cmd = cmd.cmd,
                                status = "ok",
                            )
                        )
                    }
                    "credential_push" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val credId = json.optString("credential_id", "")
                        val provider = json.optString("provider", "")
                        val name = json.optString("name", "")
                        val credType = json.optString("cred_type", "")
                        val version = json.optInt("version", 1)
                        val dataB64 = json.optString("data", "")
                        val certNotAfter = json.optString("cert_not_after", "")
                        val certFingerprint = json.optString("cert_fingerprint", "")
                        val data = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
                        val db = com.cubeos.meshsat.data.AppDatabase.getInstance(this@GatewayService)
                        db.providerCredentialDao().upsert(
                            com.cubeos.meshsat.data.ProviderCredential(
                                id = credId, provider = provider, name = name,
                                credType = credType, encryptedData = data,
                                certNotAfter = certNotAfter.ifBlank { null },
                                certFingerprint = certFingerprint, version = version,
                                source = "hub",
                            )
                        )
                        Log.i("MeshSat", "Credential received from Hub: $credId ($provider)")
                        hubReporter?.publishCommandResponse(
                            com.cubeos.meshsat.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                            )
                        )
                    }
                    "credential_revoke" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val credId = json.optString("credential_id", "")
                        val db = com.cubeos.meshsat.data.AppDatabase.getInstance(this@GatewayService)
                        db.providerCredentialDao().deleteById(credId)
                        Log.i("MeshSat", "Credential revoked by Hub: $credId")
                        hubReporter?.publishCommandResponse(
                            com.cubeos.meshsat.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                            )
                        )
                    }
                    else -> {
                        hubReporter?.publishCommandResponse(
                            com.cubeos.meshsat.hub.CommandResponse(
                                requestId = cmd.requestId,
                                cmd = cmd.cmd,
                                status = "error",
                                error = "unsupported command: ${cmd.cmd}",
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("MeshSat", "Hub command handling failed: ${e.message}")
                hubReporter?.publishCommandResponse(
                    com.cubeos.meshsat.hub.CommandResponse(
                        requestId = cmd.requestId,
                        cmd = cmd.cmd,
                        status = "error",
                        error = e.message ?: "unknown error",
                    )
                )
            }
        }
    }

    /** Handle inbound MQTT messages (MT sends, TAK events, config updates). */
    private fun handleMqttInbound(topic: String, payload: String) {
        scope.launch {
            try {
                when {
                    topic.contains("/mt/send") -> {
                        // Inbound MT — store as message and route through dispatcher
                        val json = org.json.JSONObject(payload)
                        val text = json.optString("text", "")
                        if (text.isNotBlank()) {
                            db.messageDao().insert(
                                Message(
                                    transport = "mqtt", direction = "rx", sender = "hub",
                                    text = text, timestamp = System.currentTimeMillis(),
                                )
                            )
                            val msg = com.cubeos.meshsat.rules.RouteMessage(
                                text = text, from = "hub", channel = 0, portNum = 1,
                                visited = listOf("mqtt_0"),
                            )
                            dispatcher?.dispatchAccess("mqtt_0", msg, text.toByteArray())
                        }
                    }
                    topic.contains("/sms/outbound") -> {
                        // Hub requests Android to send an SMS (MESHSAT-196)
                        val json = org.json.JSONObject(payload)
                        val to = json.optString("to", "")
                        val text = json.optString("text", "")
                        if (to.isNotBlank() && text.isNotBlank()) {
                            SmsSender.send(this@GatewayService, to, text)
                            db.messageDao().insert(
                                Message(
                                    transport = "sms", direction = "tx", sender = to,
                                    text = text, timestamp = System.currentTimeMillis(),
                                )
                            )
                            Log.i("MeshSat", "SMS sent via Hub relay: to=$to len=${text.length}")
                        }
                    }
                    topic.contains("/tak/cot/in") -> {
                        // TAK event from Hub — parse CoT XML and store as message (MESHSAT-191)
                        val tak = takIntegration
                        val cotEvent = tak?.parseInbound(payload)
                        val displayText = if (cotEvent != null && tak != null) {
                            tak.formatForDisplay(cotEvent)
                        } else {
                            payload.take(500)
                        }
                        val sender = cotEvent?.detail?.contact?.callsign ?: "tak-server"
                        db.messageDao().insert(
                            Message(
                                transport = "tak", direction = "rx", sender = sender,
                                text = displayText, timestamp = System.currentTimeMillis(),
                            )
                        )
                        // Store position from inbound CoT if it has valid coords
                        if (cotEvent != null && cotEvent.point.lat != 0.0 && cotEvent.point.lon != 0.0) {
                            val nodeHash = sender.hashCode().toLong() and 0xFFFFFFFFL
                            db.nodePositionDao().insert(
                                NodePosition(
                                    nodeId = nodeHash,
                                    nodeName = sender,
                                    latitude = cotEvent.point.lat,
                                    longitude = cotEvent.point.lon,
                                    altitude = cotEvent.point.hae.toInt(),
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MeshSat", "MQTT inbound handling error: ${e.message}")
            }
        }
    }

    /**
     * Wire inbound SMS relay to Hub via MQTT (MESHSAT-196).
     * When an SMS is received, SmsReceiver calls this relay which publishes
     * the message to meshsat/{deviceId}/sms/inbound on MQTT.
     */
    private fun initSmsRelay() {
        com.cubeos.meshsat.sms.SmsReceiver.relayCallback =
            com.cubeos.meshsat.sms.SmsRelayCallback { sender, text, rawText, wasEncrypted, wasCompressed ->
                mqttTransport?.publishSmsInbound(
                    sender = sender,
                    text = text,
                    rawText = rawText,
                    wasEncrypted = wasEncrypted,
                    wasCompressed = wasCompressed,
                )
            }
        Log.i("MeshSat", "SMS → MQTT relay initialized")
    }

    /** Initialize the APRS transport (KISS TCP or APRS-IS direct) if enabled. */
    private fun initAprs() {
        scope.launch {
            try {
                val enabled = settings.aprsEnabled.first()
                if (!enabled) {
                    Log.d("MeshSat", "APRS disabled in settings")
                    return@launch
                }
                val callsign = settings.aprsCallsign.first()
                if (callsign.isBlank()) {
                    Log.w("MeshSat", "APRS: callsign not configured")
                    return@launch
                }
                val ssid = settings.aprsSsid.first()
                val fullCallsign = if (ssid.isNotBlank() && ssid != "0") "$callsign-$ssid" else callsign
                val mode = settings.aprsMode.first()

                if (mode == "is") {
                    initAprsIs(fullCallsign)
                } else {
                    initAprsKiss(fullCallsign)
                }

                // Initialize APRS directed message tracker (MESHSAT-232)
                initAprsMessageTracker(fullCallsign, mode)

                // Initialize APRS position beaconing (MESHSAT-231)
                initAprsBeacon(fullCallsign, mode)
            } catch (e: Exception) {
                Log.w("MeshSat", "APRS init failed: ${e.message}")
            }
        }
    }

    /** Initialize APRS directed message tracker with ACK/REJ (MESHSAT-232). */
    private fun initAprsMessageTracker(fullCallsign: String, mode: String) {
        val tracker = com.cubeos.meshsat.aprs.AprsMessageTracker(scope)
        tracker.onSend = { to, text, msgId ->
            scope.launch {
                try {
                    if (mode == "is") {
                        aprsIsClient?.sendMessage(fullCallsign, to, text, msgId)
                    } else {
                        val client = kissClient ?: return@launch
                        if (!client.isConnected) return@launch
                        val ssid = fullCallsign.substringAfter("-", "10").toIntOrNull() ?: 10
                        val callBase = fullCallsign.substringBefore("-")
                        val src = Ax25Address(callBase, ssid)
                        val dst = Ax25Address("APMSHT", 0)
                        val path = listOf(Ax25Address("WIDE1", 1), Ax25Address("WIDE2", 1))
                        val info = AprsCodec.encodeMessage(to, text, msgId)
                        val ax25 = Ax25Codec.encode(dst, src, path, info)
                        client.sendFrame(ax25)
                    }
                    Log.d("MeshSat", "APRS TX directed msg $msgId to $to: $text")
                } catch (e: Exception) {
                    Log.w("MeshSat", "APRS TX failed for msg $msgId: ${e.message}")
                }
            }
        }
        tracker.onStatusChange = { msgId, status ->
            Log.i("MeshSat", "APRS msg $msgId delivery: $status")
            scope.launch {
                db.messageDao().insert(
                    Message(
                        transport = "aprs", direction = "rx",
                        sender = "system", text = "[APRS] Message $msgId: $status",
                        timestamp = System.currentTimeMillis(),
                    )
                )
            }
        }
        aprsMessageTracker = tracker
        Log.i("MeshSat", "APRS message tracker initialized (MESHSAT-232)")
    }

    /** Initialize APRS position beaconing if enabled (MESHSAT-231). */
    private suspend fun initAprsBeacon(fullCallsign: String, mode: String) {
        val beaconEnabled = settings.aprsIsBeaconEnabled.first()
        if (!beaconEnabled) return

        val intervalMin = settings.aprsIsBeaconInterval.first().toIntOrNull() ?: 10
        val beacon = com.cubeos.meshsat.aprs.AprsBeacon(scope)
        beacon.slowRateSec = (intervalMin * 60).coerceAtLeast(60)
        beacon.fastRateSec = 90

        beacon.onBeacon = { lat, lon, alt, course, speed, comment ->
            scope.launch {
                try {
                    if (mode == "is") {
                        aprsIsClient?.sendPosition(fullCallsign, lat, lon, comment = comment)
                    } else {
                        val client = kissClient ?: return@launch
                        if (!client.isConnected) return@launch
                        val ssid = fullCallsign.substringAfter("-", "10").toIntOrNull() ?: 10
                        val call = fullCallsign.substringBefore("-")
                        val src = Ax25Address(call, ssid)
                        val dst = Ax25Address("APMSHT", 0)
                        val path = listOf(Ax25Address("WIDE1", 1), Ax25Address("WIDE2", 1))
                        val info = AprsCodec.encodePosition(lat, lon, comment = comment)
                        val ax25 = Ax25Codec.encode(dst, src, path, info)
                        client.sendFrame(ax25)
                    }
                    db.messageDao().insert(
                        Message(
                            transport = "aprs", direction = "tx", sender = "self",
                            text = "[APRS:$fullCallsign] beacon %.4f,%.4f $comment".format(lat, lon),
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    Log.d("MeshSat", "APRS beacon TX via $mode")
                } catch (e: Exception) {
                    Log.w("MeshSat", "APRS beacon TX failed: ${e.message}")
                }
            }
        }

        beacon.start()
        aprsBeacon = beacon
        Log.i("MeshSat", "APRS beacon started (interval=${intervalMin}min, smart beaconing enabled)")
    }

    /** Initialize APRS via KISS TCP (APRSDroid/Direwolf). */
    private suspend fun initAprsKiss(fullCallsign: String) {
        val host = settings.aprsKissHost.first().ifBlank { "localhost" }
        val port = settings.aprsKissPort.first().toIntOrNull() ?: 8001

        val client = KissClient(scope)
        client.setFrameCallback { ax25Frame ->
            val pkt = AprsCodec.parse(ax25Frame)
            handleAprsPacket(pkt)
        }

        client.connect(host, port)
        kissClient = client

        scope.launch {
            client.state.collect { state ->
                when (state) {
                    KissClient.State.Connected -> interfaceManager?.setOnline("aprs_0")
                    KissClient.State.Disconnected -> interfaceManager?.setOffline("aprs_0")
                    KissClient.State.Error -> interfaceManager?.setError("aprs_0", "KISS connection error")
                    KissClient.State.Connecting -> interfaceManager?.setConnecting("aprs_0")
                }
            }
        }

        Log.i("MeshSat", "APRS KISS initialized ($fullCallsign on $host:$port)")
    }

    /** Initialize APRS via direct APRS-IS TCP connection (MESHSAT-230). */
    private suspend fun initAprsIs(fullCallsign: String) {
        val server = settings.aprsIsServer.first().ifBlank { "rotate.aprs2.net" }
        val port = settings.aprsIsPort.first().toIntOrNull() ?: 14580
        val passcode = settings.aprsIsPasscode.first().ifBlank { "-1" }
        val filterRange = settings.aprsIsFilterRange.first().toIntOrNull() ?: 100

        // Use phone GPS for filter center
        val location = _phoneLocation.value
        val filterLat = location?.latitude ?: 0.0
        val filterLon = location?.longitude ?: 0.0

        val client = com.cubeos.meshsat.aprs.AprsIsClient(scope)
        client.setPacketCallback { pkt -> handleAprsPacket(pkt) }

        client.connect(
            server = server,
            port = port,
            callsign = fullCallsign,
            passcode = passcode,
            filterLat = filterLat,
            filterLon = filterLon,
            filterRange = filterRange,
        )
        aprsIsClient = client

        scope.launch {
            client.state.collect { state ->
                when (state) {
                    com.cubeos.meshsat.aprs.AprsIsClient.State.Connected ->
                        interfaceManager?.setOnline("aprs_0")
                    com.cubeos.meshsat.aprs.AprsIsClient.State.Disconnected ->
                        interfaceManager?.setOffline("aprs_0")
                    com.cubeos.meshsat.aprs.AprsIsClient.State.Error ->
                        interfaceManager?.setError("aprs_0", "APRS-IS connection error")
                    com.cubeos.meshsat.aprs.AprsIsClient.State.Connecting ->
                        interfaceManager?.setConnecting("aprs_0")
                }
            }
        }

        Log.i("MeshSat", "APRS-IS initialized ($fullCallsign on $server:$port, filter=${filterRange}km)")
        // Position beaconing handled by initAprsBeacon() with smart beaconing (MESHSAT-231)
    }

    /** Handle an inbound APRS packet (from either KISS or APRS-IS). */
    private fun handleAprsPacket(pkt: AprsPacket) {
        // MESHSAT-232: Check for ACK/REJ for our outbound messages first
        val tracker = aprsMessageTracker
        if (tracker != null && tracker.processInbound(pkt)) {
            Log.d("MeshSat", "APRS ACK/REJ handled for msg from ${pkt.source}")
            return // ACK/REJ packets are control-only, don't store as messages
        }

        val text = when (pkt.dataType) {
            '!', '=', '/', '@' ->
                "[APRS:${pkt.source}] ${String.format("%.4f,%.4f", pkt.lat, pkt.lon)} ${pkt.comment}"
            ':' ->
                "[APRS:${pkt.source}\u2192${pkt.msgTo}] ${pkt.message}"
            else ->
                "[APRS:${pkt.source}] ${pkt.raw}"
        }

        scope.launch {
            db.messageDao().insert(
                Message(
                    transport = "aprs", direction = "rx",
                    sender = pkt.source, text = text,
                    timestamp = System.currentTimeMillis(),
                )
            )

            // MESHSAT-232: Send ACK for directed messages addressed to us with a msgId
            if (pkt.dataType == ':' && pkt.msgId.isNotEmpty()) {
                val callsign = settings.aprsCallsign.first()
                val ssid = settings.aprsSsid.first()
                val fullCallsign = if (ssid.isNotBlank() && ssid != "0") "$callsign-$ssid" else callsign
                if (pkt.msgTo.equals(fullCallsign, ignoreCase = true)) {
                    sendAprsAck(fullCallsign, pkt.source, pkt.msgId)
                }
            }

            // Store position from APRS station on the map
            if (pkt.lat != 0.0 && pkt.lon != 0.0) {
                val nodeHash = pkt.source.hashCode().toLong() and 0xFFFFFFFFL
                db.nodePositionDao().insert(
                    NodePosition(
                        nodeId = nodeHash,
                        nodeName = pkt.source,
                        latitude = pkt.lat,
                        longitude = pkt.lon,
                        altitude = 0,
                    )
                )
            }

            val msg = RouteMessage(
                text = text, from = pkt.source, channel = 0, portNum = 1,
                visited = listOf("aprs_0"),
            )
            dispatcher?.dispatchAccess("aprs_0", msg, text.toByteArray())
            interfaceManager?.recordActivity("aprs_0")
        }
    }

    /** Send an APRS ACK for a directed message we received (MESHSAT-232). */
    private suspend fun sendAprsAck(ourCallsign: String, to: String, msgId: String) {
        try {
            val isClient = aprsIsClient
            val kiss = kissClient
            if (isClient?.isConnected == true) {
                isClient.sendAck(ourCallsign, to, msgId)
            } else if (kiss?.isConnected == true) {
                val ssid = ourCallsign.substringAfter("-", "10").toIntOrNull() ?: 10
                val callBase = ourCallsign.substringBefore("-")
                val src = Ax25Address(callBase, ssid)
                val dst = Ax25Address("APMSHT", 0)
                val path = listOf(Ax25Address("WIDE1", 1), Ax25Address("WIDE2", 1))
                val padded = to.padEnd(9)
                val info = ":$padded:ack$msgId".toByteArray()
                val ax25 = Ax25Codec.encode(dst, src, path, info)
                kiss.sendFrame(ax25)
            }
            Log.d("MeshSat", "APRS ACK sent for msg $msgId to $to")
        } catch (e: Exception) {
            Log.w("MeshSat", "Failed to send APRS ACK for msg $msgId: ${e.message}")
        }
    }

    /**
     * Initialize Reticulum TCP interface (MESHSAT-268).
     * Connects to a stock RNS node over TCP with HDLC framing.
     */
    private fun initRnsTcp() {
        scope.launch {
            try {
                val enabled = settings.rnsTcpEnabled.first()
                if (!enabled) {
                    Log.d("MeshSat", "RNS TCP disabled in settings")
                    return@launch
                }
                val host = settings.rnsTcpHost.first()
                if (host.isBlank()) {
                    Log.w("MeshSat", "RNS TCP: host not configured")
                    return@launch
                }
                val port = settings.rnsTcpPort.first().toIntOrNull()
                    ?: com.cubeos.meshsat.reticulum.RnsTcpInterface.DEFAULT_PORT

                val tcp = com.cubeos.meshsat.reticulum.RnsTcpInterface(scope)
                tcp.connect(host, port)
                rnsTcpInterface = tcp
                scope.launch {
                    tcp.state.collect { state ->
                        when (state) {
                            com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Connected ->
                                interfaceManager?.setOnline("tcp_rns_0")
                            com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Disconnected ->
                                interfaceManager?.setOffline("tcp_rns_0")
                            com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Error ->
                                interfaceManager?.setError("tcp_rns_0", tcp.error.value)
                            com.cubeos.meshsat.reticulum.RnsTcpInterface.State.Connecting ->
                                interfaceManager?.setConnecting("tcp_rns_0")
                        }
                    }
                }
                Log.i("MeshSat", "RNS TCP interface initialized: $host:$port")
            } catch (e: Exception) {
                Log.w("MeshSat", "RNS TCP init failed (non-fatal): ${e.message}")
            }
        }
    }

    /**
     * Initialize Reticulum Transport Node (MESHSAT-199/267).
     * Creates the routing identity, all RnsInterfaces, and starts the transport node.
     */
    private fun initReticulumTransportNode() {
        scope.launch {
            try {
                val kvStore = com.cubeos.meshsat.crypto.SecureKeyStore.getInstance(this@GatewayService)
                val identity = com.cubeos.meshsat.routing.Identity.loadOrGenerate(kvStore)

                val announceHandler = com.cubeos.meshsat.reticulum.RnsAnnounceHandler(
                    identity = identity,
                    scope = scope,
                )
                announceHandler.startPruner()

                val localDestHash = announceHandler.localDestHash

                val linkManager = com.cubeos.meshsat.reticulum.RnsLinkManager(
                    identity = identity,
                    localDestHash = localDestHash,
                )

                // Build RnsInterface map from available transports
                val rnsInterfaces: () -> Map<String, com.cubeos.meshsat.reticulum.RnsInterface> = {
                    val map = mutableMapOf<String, com.cubeos.meshsat.reticulum.RnsInterface>()
                    meshtasticBle?.let { ble ->
                        map["mesh_rns_0"] = com.cubeos.meshsat.reticulum.RnsMeshtasticBleInterface(ble, scope)
                    }
                    iridiumSpp?.let { spp ->
                        map["iridium_rns_0"] = com.cubeos.meshsat.reticulum.RnsIridiumInterface(spp)
                    }
                    iridium9704Spp?.let { spp ->
                        map["iridium9704_rns_0"] = com.cubeos.meshsat.reticulum.RnsIridium9704Interface(spp, scope)
                    }
                    rnsTcpInterface?.let { tcp ->
                        map["tcp_rns_0"] = tcp
                    }
                    astrocastSpp?.let { spp ->
                        map["astrocast_rns_0"] = com.cubeos.meshsat.reticulum.RnsAstrocastInterface(spp, scope)
                    }
                    // SMS, MQTT, APRS interfaces can be added here as they become available
                    map
                }

                val pathTable = com.cubeos.meshsat.reticulum.RnsPathTable(
                    interfaces = { rnsInterfaces().values.toList() },
                )

                val forwardingTable = com.cubeos.meshsat.reticulum.RnsForwardingTable()

                val node = com.cubeos.meshsat.reticulum.RnsTransportNode(
                    localDestHash = localDestHash,
                    announceHandler = announceHandler,
                    linkManager = linkManager,
                    pathTable = pathTable,
                    forwardingTable = forwardingTable,
                    interfaces = rnsInterfaces,
                    scope = scope,
                )

                // Deliver locally-addressed Reticulum packets to the message pipeline
                node.localDeliveryCallback = com.cubeos.meshsat.reticulum.RnsTransportNode.LocalDeliveryCallback { packet, sourceInterface ->
                    val text = packet.data.toString(Charsets.UTF_8)
                    scope.launch {
                        db.messageDao().insert(
                            Message(
                                transport = "reticulum",
                                direction = "rx",
                                sender = packet.destHash.joinToString("") { "%02x".format(it) }.take(8),
                                text = text,
                                encrypted = false,
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                    }
                }

                node.start()
                rnsTransportNode = node

                Log.i("MeshSat", "Reticulum Transport Node started: ${identity.destHashHex} (${rnsInterfaces().size} interfaces)")
            } catch (e: Exception) {
                Log.w("MeshSat", "Reticulum init failed (non-fatal): ${e.message}", e)
            }
        }
    }

    private fun loadRulesFromDb() {
        scope.launch {
            val entities = db.forwardingRuleDao().getAllSync()
            rulesEngine.setRules(entities.map { it.toRule() })
        }
    }

    /**
     * Initialize the Phase C InterfaceManager with the 3 Android transports.
     * Registers connect/disconnect callbacks and observes transport state flows
     * to drive state machine transitions.
     */
    private fun initInterfaceManager() {
        val mgr = InterfaceManager(scope)

        // Register the 3 Android interfaces
        mgr.register(InterfaceConfig(
            id = "mesh_0", channelType = "mesh",
            autoReconnect = true,
            initialBackoff = 5.seconds,
            maxBackoff = 60.seconds,
        ))
        mgr.register(InterfaceConfig(
            id = "iridium_0", channelType = "iridium",
            autoReconnect = true,
            initialBackoff = 10.seconds,
            maxBackoff = 120.seconds,
        ))
        mgr.register(InterfaceConfig(
            id = "sms_0", channelType = "sms",
            autoReconnect = false,
            alwaysOnline = true, // SMS is always available via Android
        ))
        mgr.register(InterfaceConfig(
            id = "mqtt_0", channelType = "mqtt",
            autoReconnect = true,
            initialBackoff = 5.seconds,
            maxBackoff = 120.seconds,
        ))
        mgr.register(InterfaceConfig(
            id = "iridium9704_0", channelType = "iridium9704",
            autoReconnect = true,
            initialBackoff = 10.seconds,
            maxBackoff = 120.seconds,
        ))
        mgr.register(InterfaceConfig(
            id = "astrocast_0", channelType = "astrocast",
            autoReconnect = true,
            initialBackoff = 30.seconds,
            maxBackoff = 300.seconds,
        ))
        mgr.register(InterfaceConfig(
            id = "aprs_0", channelType = "aprs",
            autoReconnect = true,
            initialBackoff = 10.seconds,
            maxBackoff = 120.seconds,
        ))
        mgr.register(InterfaceConfig(
            id = "tcp_rns_0", channelType = "tcp",
            autoReconnect = true,
            initialBackoff = 5.seconds,
            maxBackoff = 60.seconds,
        ))

        // Connect callback — triggers actual BLE/SPP connection
        // Uses the saved BLE/SPP addresses from settings to reconnect.
        mgr.setConnectCallback { interfaceId ->
            when {
                interfaceId.startsWith("mesh") -> {
                    val ble = meshtasticBle ?: return@setConnectCallback "mesh transport not available"
                    // BLE address was saved on first connect; we don't have a flow for it,
                    // so we rely on the transport layer's last-known address.
                    // If the user hasn't connected before, the InterfaceManager won't auto-reconnect.
                    ble.reconnect()
                    null // connection is async — setOnline called from state observer
                }
                interfaceId == "iridium_0" -> {
                    val spp = iridiumSpp ?: return@setConnectCallback "iridium transport not available"
                    spp.reconnect()
                    null
                }
                interfaceId == "iridium9704_0" -> {
                    val spp = iridium9704Spp ?: return@setConnectCallback "9704 transport not available"
                    spp.reconnect()
                    null
                }
                interfaceId == "astrocast_0" -> {
                    // Astrocast reconnect not implemented — requires explicit BT address
                    "astrocast requires explicit connect"
                }
                interfaceId.startsWith("mqtt") -> {
                    // MQTT reconnect — re-read settings and connect
                    val brokerUrl = settings.mqttBrokerUrl.first()
                    val deviceId = settings.mqttDeviceId.first()
                    if (brokerUrl.isBlank() || deviceId.isBlank()) {
                        return@setConnectCallback "mqtt not configured"
                    }
                    val transport = mqttTransport ?: MqttTransport(scope).also { mqttTransport = it }
                    transport.connect(brokerUrl, deviceId,
                        settings.mqttUsername.first(), settings.mqttPassword.first(),
                        settings.mqttCertPin.first(), settings.mqttCertPinBackup.first())
                    null
                }
                interfaceId.startsWith("aprs") -> {
                    val callsign = settings.aprsCallsign.first()
                    if (callsign.isBlank()) {
                        return@setConnectCallback "APRS callsign not configured"
                    }
                    val host = settings.aprsKissHost.first().ifBlank { "localhost" }
                    val port = settings.aprsKissPort.first().toIntOrNull() ?: 8001
                    val client = kissClient ?: KissClient(scope).also { kissClient = it }
                    client.connect(host, port)
                    null // connection is async — setOnline called from state observer
                }
                interfaceId.startsWith("tcp_rns") -> {
                    val host = settings.rnsTcpHost.first()
                    if (host.isBlank()) {
                        return@setConnectCallback "RNS TCP host not configured"
                    }
                    val port = settings.rnsTcpPort.first().toIntOrNull()
                        ?: com.cubeos.meshsat.reticulum.RnsTcpInterface.DEFAULT_PORT
                    val tcp = rnsTcpInterface
                        ?: com.cubeos.meshsat.reticulum.RnsTcpInterface(scope).also { rnsTcpInterface = it }
                    tcp.connect(host, port)
                    null
                }
                else -> null
            }
        }

        // Disconnect callback
        mgr.setDisconnectCallback { interfaceId ->
            when {
                interfaceId.startsWith("mesh") -> meshtasticBle?.disconnect()
                interfaceId == "iridium_0" -> iridiumSpp?.disconnect()
                interfaceId == "iridium9704_0" -> iridium9704Spp?.disconnect()
                interfaceId == "astrocast_0" -> astrocastSpp?.disconnect()
                interfaceId.startsWith("mqtt") -> mqttTransport?.disconnect()
                interfaceId.startsWith("aprs") -> kissClient?.disconnect()
                interfaceId.startsWith("tcp_rns") -> rnsTcpInterface?.disconnect()
            }
        }

        interfaceManager = mgr
        ifaceManager = mgr  // Phase I: expose for UI

        // Observe BLE state → drive InterfaceManager
        meshtasticBle?.let { ble ->
            scope.launch {
                ble.state.collect { state ->
                    when (state) {
                        MeshtasticBle.State.Connected -> mgr.setOnline("mesh_0")
                        MeshtasticBle.State.Disconnected -> mgr.setOffline("mesh_0")
                        MeshtasticBle.State.Scanning,
                        MeshtasticBle.State.Connecting -> mgr.setConnecting("mesh_0")
                    }
                }
            }
        }

        // Observe SPP state → drive InterfaceManager
        iridiumSpp?.let { spp ->
            scope.launch {
                spp.state.collect { state ->
                    when (state) {
                        IridiumSpp.State.Connected -> mgr.setOnline("iridium_0")
                        IridiumSpp.State.Disconnected -> mgr.setOffline("iridium_0")
                        IridiumSpp.State.Connecting -> mgr.setConnecting("iridium_0")
                    }
                }
            }
        }

        // Observe BLE errors → drive InterfaceManager
        meshtasticBle?.let { ble ->
            scope.launch {
                ble.error.collect { err ->
                    if (err.isNotBlank()) mgr.setError("mesh_0", err)
                }
            }
        }
        iridiumSpp?.let { spp ->
            scope.launch {
                spp.error.collect { err ->
                    if (err.isNotBlank()) mgr.setError("iridium_0", err)
                }
            }
        }

        // Observe 9704 SPP state → drive InterfaceManager
        iridium9704Spp?.let { spp ->
            scope.launch {
                spp.state.collect { state ->
                    when (state) {
                        com.cubeos.meshsat.bt.Iridium9704Spp.State.Ready -> mgr.setOnline("iridium9704_0")
                        com.cubeos.meshsat.bt.Iridium9704Spp.State.Disconnected -> mgr.setOffline("iridium9704_0")
                        com.cubeos.meshsat.bt.Iridium9704Spp.State.Connecting,
                        com.cubeos.meshsat.bt.Iridium9704Spp.State.Connected,
                        com.cubeos.meshsat.bt.Iridium9704Spp.State.Initializing -> mgr.setConnecting("iridium9704_0")
                    }
                }
            }
            scope.launch {
                spp.error.collect { err ->
                    if (err.isNotBlank()) mgr.setError("iridium9704_0", err)
                }
            }
        }

        // Observe Astrocast SPP state → drive InterfaceManager
        astrocastSpp?.let { spp ->
            scope.launch {
                spp.state.collect { state ->
                    when (state) {
                        com.cubeos.meshsat.astrocast.AstrocastSpp.State.Connected -> mgr.setOnline("astrocast_0")
                        com.cubeos.meshsat.astrocast.AstrocastSpp.State.Disconnected -> mgr.setOffline("astrocast_0")
                        com.cubeos.meshsat.astrocast.AstrocastSpp.State.Connecting -> mgr.setConnecting("astrocast_0")
                        com.cubeos.meshsat.astrocast.AstrocastSpp.State.Error -> mgr.setError("astrocast_0", "connection error")
                    }
                }
            }
        }

        Log.i("MeshSat", "InterfaceManager initialized with 8 interfaces")
    }

    /**
     * Initialize the Phase B structured dispatch stack:
     * AccessEvaluator → FailoverResolver → Dispatcher with delivery workers.
     * Falls back gracefully to the legacy RulesEngine if no access rules exist.
     */
    private fun initDispatcher() {
        scope.launch {
            try {
                // Channel registry (from Phase A)
                val registry = ChannelRegistry()
                registerAndroidDefaults(registry)
                channelReg = registry  // Phase I: expose for UI

                // Access evaluator
                val eval = AccessEvaluator(db.accessRuleDao(), db.objectGroupDao(), scope)
                eval.reloadFromDb()
                accessEvaluator = eval
                accessEval = eval

                // Interface status provider — delegates to InterfaceManager (Phase C)
                val mgr = interfaceManager
                val statusProvider = InterfaceStatusProvider { interfaceId ->
                    mgr?.isOnline(interfaceId) ?: when {
                        interfaceId.startsWith("mesh") ->
                            meshtasticBle?.state?.value == MeshtasticBle.State.Connected
                        interfaceId == "iridium_0" ->
                            iridiumSpp?.state?.value == IridiumSpp.State.Connected
                        interfaceId == "iridium9704_0" ->
                            iridium9704Spp?.state?.value == com.cubeos.meshsat.bt.Iridium9704Spp.State.Ready
                        interfaceId == "astrocast_0" ->
                            astrocastSpp?.state?.value == com.cubeos.meshsat.astrocast.AstrocastSpp.State.Connected
                        interfaceId.startsWith("sms") -> true
                        interfaceId.startsWith("mqtt") ->
                            mqttTransport?.isConnected == true
                        interfaceId.startsWith("aprs") ->
                            kissClient?.isConnected == true
                        else -> false
                    }
                }

                // Failover resolver
                val failover = FailoverResolver(db.failoverGroupDao(), statusProvider)

                // Delivery callback: routes to the correct transport
                val callback = Dispatcher.DeliveryCallback { interfaceId, payload, textPreview ->
                    deliverToTransport(interfaceId, payload, textPreview)
                }

                // Create and start dispatcher (Phase C: with sequence tracker)
                val disp = Dispatcher(
                    deliveryDao = db.messageDeliveryDao(),
                    accessEvaluator = eval,
                    failoverResolver = failover,
                    registry = registry,
                    deliveryCallback = callback,
                    scope = scope,
                    sequenceTracker = sequenceTracker,
                )

                // Wire InterfaceManager state changes to Dispatcher hold/unhold
                interfaceManager?.setStateChangeCallback { id, channelType, old, new ->
                    disp.onInterfaceStateChange(id, channelType, old, new)
                }

                // Start workers for all Android interfaces
                val interfaces = mapOf(
                    "mesh_0" to "mesh",
                    "iridium_0" to "iridium",
                    "iridium9704_0" to "iridium9704",
                    "astrocast_0" to "astrocast",
                    "sms_0" to "sms",
                    "mqtt_0" to "mqtt",
                    "aprs_0" to "aprs",
                )
                disp.start(interfaces)
                dispatcher = disp

                // Phase C: Start ACK tracker
                val tracker = AckTracker(db.messageDeliveryDao(), scope)
                tracker.start()
                ackTracker = tracker

                Log.i("MeshSat", "Dispatcher initialized (${eval.ruleCount()} access rules, ACK tracker started)")
            } catch (e: Exception) {
                Log.e("MeshSat", "Dispatcher init failed (legacy routing active): ${e.message}")
            }
        }
    }

    /**
     * Delivery callback: sends a message payload to the named interface.
     * Returns null on success, error message on failure.
     */
    private suspend fun deliverToTransport(interfaceId: String, payload: ByteArray, textPreview: String): String? {
        return try {
            when {
                interfaceId.startsWith("mesh") -> {
                    val ble = meshtasticBle
                        ?: return "mesh not available"
                    if (ble.state.value != MeshtasticBle.State.Connected)
                        return "mesh not connected"
                    val proto = MeshtasticProtocol.encodeTextMessage(textPreview)
                    ble.sendToRadio(proto)
                    db.messageDao().insert(
                        Message(
                            transport = "mesh", direction = "tx", sender = "self",
                            text = textPreview, forwarded = true, forwardedTo = "mesh:broadcast",
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    null // success
                }
                interfaceId == "iridium_0" -> {
                    val spp = iridiumSpp
                        ?: return "iridium not available"
                    if (spp.state.value != IridiumSpp.State.Connected)
                        return "iridium not connected"
                    val data = if (payload.isNotEmpty()) payload else textPreview.toByteArray()
                    // Fragment messages >340B using Iridium 2-byte header.
                    val fragments = IridiumFragment.fragment(data, IridiumFragment.MO_MTU, nextMsgID())
                    val chunks = fragments ?: listOf(data)
                    for ((i, chunk) in chunks.withIndex()) {
                        val written = spp.writeMoBuffer(chunk)
                        if (!written) return "MO buffer write failed (fragment $i/${chunks.size})"
                        val result = spp.sbdix()
                        if (result?.moSuccess != true) return "SBDIX failed (fragment $i/${chunks.size}): mo_status=${result?.moStatus}"
                    }
                    db.messageDao().insert(
                        Message(
                            transport = "iridium", direction = "tx", sender = "self",
                            text = textPreview, forwarded = true, forwardedTo = "iridium:sbd",
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    null // success
                }
                interfaceId == "iridium9704_0" -> {
                    val spp = iridium9704Spp
                        ?: return "9704 not available"
                    if (spp.state.value != com.cubeos.meshsat.bt.Iridium9704Spp.State.Ready)
                        return "9704 not ready"
                    val data = if (payload.isNotEmpty()) payload else textPreview.toByteArray()
                    // 9704 supports up to 100KB — no app-level fragmentation needed
                    val status = spp.sendMessageBlocking(data)
                    if (status != "mo_ack_received") {
                        return "9704 MO failed: ${status ?: "timeout"}"
                    }
                    db.messageDao().insert(
                        Message(
                            transport = "iridium9704", direction = "tx", sender = "self",
                            text = textPreview, forwarded = true, forwardedTo = "iridium9704:imt",
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    null // success
                }
                interfaceId == "astrocast_0" -> {
                    val spp = astrocastSpp
                        ?: return "astrocast not available"
                    if (spp.state.value != com.cubeos.meshsat.astrocast.AstrocastSpp.State.Connected)
                        return "astrocast not connected"
                    val data = if (payload.isNotEmpty()) payload else textPreview.toByteArray()
                    val result = spp.sendPayload(0, data)
                    if (result < 0) return "astrocast payload queue failed"
                    db.messageDao().insert(
                        Message(
                            transport = "astrocast", direction = "tx", sender = "self",
                            text = textPreview, forwarded = true, forwardedTo = "astrocast:leo",
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    null // success
                }
                interfaceId.startsWith("sms") -> {
                    val phone = settings.meshsatPiPhone.first()
                    if (phone.isBlank()) return "no SMS destination configured"
                    SmsSender.send(context = this, to = phone, text = textPreview)
                    db.messageDao().insert(
                        Message(
                            transport = "sms", direction = "tx", sender = "self",
                            recipient = phone, text = textPreview, forwarded = true,
                            forwardedTo = "sms:$phone", timestamp = System.currentTimeMillis(),
                        )
                    )
                    null // success
                }
                interfaceId.startsWith("mqtt") -> {
                    val mqtt = mqttTransport
                        ?: return "mqtt not available"
                    if (!mqtt.isConnected) return "mqtt not connected"
                    mqtt.publishMODecoded(textPreview, channel = "android")
                    db.messageDao().insert(
                        Message(
                            transport = "mqtt", direction = "tx", sender = "self",
                            text = textPreview, forwarded = true,
                            forwardedTo = "mqtt:hub", timestamp = System.currentTimeMillis(),
                        )
                    )
                    null // success
                }
                interfaceId.startsWith("aprs") -> {
                    val isClient = aprsIsClient
                    val kiss = kissClient
                    if (isClient?.isConnected != true && kiss?.isConnected != true)
                        return "aprs not connected"

                    val callsign = settings.aprsCallsign.first()
                    val ssidStr = settings.aprsSsid.first()
                    val fullCallsign = if (ssidStr.isNotBlank() && ssidStr != "0") "$callsign-$ssidStr" else callsign

                    // MESHSAT-232: Directed message if text starts with @CALLSIGN
                    val directedMatch = Regex("^@([A-Za-z0-9-]{1,9})\\s+(.+)$").find(textPreview)
                    if (directedMatch != null) {
                        val toCallsign = directedMatch.groupValues[1].uppercase()
                        val msgText = directedMatch.groupValues[2].take(67)
                        val tracker = aprsMessageTracker
                        if (tracker != null) {
                            val msgId = tracker.send(toCallsign, msgText)
                            db.messageDao().insert(
                                Message(
                                    transport = "aprs", direction = "tx", sender = "self",
                                    text = "[APRS:$fullCallsign\u2192$toCallsign] $msgText {$msgId}",
                                    forwarded = true, forwardedTo = "aprs:$toCallsign",
                                    timestamp = System.currentTimeMillis(),
                                )
                            )
                        } else {
                            // Tracker not initialized — send without ACK tracking
                            if (isClient?.isConnected == true) {
                                isClient.sendMessage(fullCallsign, toCallsign, msgText)
                            } else if (kiss?.isConnected == true) {
                                val ssid = ssidStr.toIntOrNull() ?: 10
                                val src = Ax25Address(callsign, ssid)
                                val dst = Ax25Address("APMSHT", 0)
                                val path = listOf(Ax25Address("WIDE1", 1), Ax25Address("WIDE2", 1))
                                val info = AprsCodec.encodeMessage(toCallsign, msgText)
                                val ax25 = Ax25Codec.encode(dst, src, path, info)
                                kiss.sendFrame(ax25)
                            }
                            db.messageDao().insert(
                                Message(
                                    transport = "aprs", direction = "tx", sender = "self",
                                    text = "[APRS:$fullCallsign\u2192$toCallsign] $msgText",
                                    forwarded = true, forwardedTo = "aprs:$toCallsign",
                                    timestamp = System.currentTimeMillis(),
                                )
                            )
                        }
                    } else {
                        // Bulletin (BLN1) — no ACK expected
                        if (isClient?.isConnected == true) {
                            isClient.sendMessage(fullCallsign, "BLN1", textPreview.take(67))
                        } else if (kiss?.isConnected == true) {
                            val ssid = ssidStr.toIntOrNull() ?: 10
                            val src = Ax25Address(callsign, ssid)
                            val dst = Ax25Address("APMSHT", 0)
                            val path = listOf(Ax25Address("WIDE1", 1), Ax25Address("WIDE2", 1))
                            val info = AprsCodec.encodeMessage("BLN1", textPreview.take(67))
                            val ax25 = Ax25Codec.encode(dst, src, path, info)
                            kiss.sendFrame(ax25)
                        }
                        db.messageDao().insert(
                            Message(
                                transport = "aprs", direction = "tx", sender = "self",
                                text = textPreview, forwarded = true,
                                forwardedTo = "aprs:rf", timestamp = System.currentTimeMillis(),
                            )
                        )
                    }
                    null // success
                }
                else -> "unknown interface: $interfaceId"
            }
        } catch (e: Exception) {
            e.message ?: "delivery failed"
        }
    }

    // --- Phone GPS Location ---

    private val locationListener = LocationListener { location ->
        _phoneLocation.value = location
        // Store phone position in node_positions with special nodeId=0
        scope.launch {
            db.nodePositionDao().insert(
                NodePosition(
                    nodeId = 0,
                    nodeName = "Phone",
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude.toInt(),
                )
            )
            // Emit CoT PLI to ATAK + Hub (MESHSAT-191)
            takIntegration?.sendPosition(
                lat = location.latitude,
                lon = location.longitude,
                alt = location.altitude,
                course = location.bearing.toDouble(),
                speed = location.speed.toDouble(),
            )
        }
        // Publish position to Hub via HubReporter (MESHSAT-292)
        hubReporter?.let { reporter ->
            if (reporter.isConnected) {
                val pos = com.cubeos.meshsat.hub.DevicePosition(
                    lat = location.latitude,
                    lon = location.longitude,
                    alt = location.altitude,
                    speed = location.speed.toDouble(),
                    course = location.bearing.toDouble(),
                    source = "gps",
                )
                val deviceId = try {
                    kotlinx.coroutines.runBlocking { settings.hubBridgeId.first() }
                        .ifEmpty { "android-phone" }
                } catch (_: Exception) { "android-phone" }
                reporter.publishDevicePosition(deviceId, pos)
            }
        }
        // Feed APRS beacon smart beaconing engine (MESHSAT-231)
        aprsBeacon?.onLocationUpdate(location)
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return

        // Request updates every 60s or 50m
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 60_000L, 50f, locationListener
            )
        } catch (_: Exception) {}

        try {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 60_000L, 100f, locationListener
            )
        } catch (_: Exception) {}

        // Seed with last known
        val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (last != null) {
            _phoneLocation.value = last
        }
    }

    /** Poll all transport signals every 60s and record to signal_history. */
    private fun startSignalPolling() {
        scope.launch {
            while (true) {
                delay(60_000)

                // Iridium signal (0-5)
                val spp = iridiumSpp
                if (spp != null && spp.state.value == IridiumSpp.State.Connected) {
                    val sig = spp.pollSignal()
                    if (sig != null) {
                        db.signalDao().insert(SignalRecord(source = "iridium", value = sig))
                    }
                }

                // Meshtastic BLE RSSI (negative dBm)
                val ble = meshtasticBle
                if (ble != null && ble.state.value == MeshtasticBle.State.Connected) {
                    ble.readRssi()
                    delay(500) // Allow callback to fire
                    val rssi = ble.rssi.value
                    if (rssi != 0) {
                        db.signalDao().insert(SignalRecord(source = "mesh", value = rssi))
                    }
                }

                // Phone cellular signal (dBm)
                val cellSignal = getCellularSignalDbm()
                if (cellSignal != null) {
                    db.signalDao().insert(SignalRecord(source = "cellular", value = cellSignal))
                }

                // Cleanup old signal records (keep 24h)
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                db.signalDao().deleteBefore(cutoff)
            }
        }
    }

    /** Get the phone's cellular signal strength in dBm. */
    private fun getCellularSignalDbm(): Int? {
        return try {
            val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.signalStrength?.let { ss ->
                    val best = ss.cellSignalStrengths.minByOrNull { it.dbm }
                    best?.dbm?.takeIf { it != Int.MAX_VALUE && it != Int.MIN_VALUE }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d("MeshSat", "Cellular signal read failed: ${e.message}")
            null
        }
    }

    private fun observeTransports() {
        // Listen for incoming Meshtastic messages — unified single-parse dispatch (MESHSAT-241)
        meshtasticBle?.let { ble ->
            scope.launch {
                ble.receivedData.collect { data ->
                    val result = MeshtasticProtocol.parseFromRadioFull(data) ?: return@collect

                    // --- Text message ---
                    result.textMessage?.let { msg ->
                        val nodeId = MeshtasticProtocol.formatNodeId(msg.from)
                        ble.touchNode(msg.from)
                        val dedupKey = "mesh:${msg.from}:${msg.id}"
                        if (deduplicator.isDuplicateKey(dedupKey)) {
                            Log.d("MeshSat", "Dedup: skipping duplicate mesh msg $dedupKey")
                            return@collect
                        }
                        db.messageDao().insert(
                            Message(
                                transport = "mesh",
                                direction = "rx",
                                sender = nodeId,
                                text = msg.text,
                                encrypted = false,
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                        postMessageNotification("Mesh: $nodeId", msg.text)
                        interfaceManager?.recordActivity("mesh_0")
                        evaluateAndForward(ForwardingRule.Transport.MESH, msg.text, nodeId)
                        return@collect
                    }

                    // --- Position ---
                    result.position?.let { pos ->
                        val nodeId = MeshtasticProtocol.formatNodeId(pos.from)
                        ble.touchNode(pos.from)
                        db.nodePositionDao().insert(
                            NodePosition(
                                nodeId = pos.from,
                                nodeName = nodeId,
                                latitude = pos.latitude,
                                longitude = pos.longitude,
                                altitude = pos.altitude,
                            )
                        )
                        geofenceMonitor?.checkPosition(nodeId, pos.latitude, pos.longitude)
                        Log.d("MeshSat", "Position from $nodeId: ${pos.latitude},${pos.longitude}")
                        return@collect
                    }

                    // --- Device telemetry (battery level) ---
                    result.telemetry?.let { telemetry ->
                        ble.touchNode(telemetry.from)
                        if (telemetry.batteryLevel in 0..100) {
                            ble.updateNodeBattery(telemetry.from, telemetry.batteryLevel)
                        }
                        return@collect
                    }

                    // --- Environment telemetry (temperature, humidity, pressure) ---
                    result.environmentTelemetry?.let { env ->
                        ble.touchNode(env.from)
                        Log.d("MeshSat", "EnvTelemetry from ${MeshtasticProtocol.formatNodeId(env.from)}: " +
                            "temp=${env.temperature}°C humidity=${env.relativeHumidity}% pressure=${env.barometricPressure}hPa")
                        return@collect
                    }

                    // --- My node info (device info on connect) ---
                    result.myInfo?.let { myInfo ->
                        ble.setMyInfo(myInfo)
                        Log.d("MeshSat", "MyInfo: node=${myInfo.myNodeNum}, fw=${myInfo.firmwareVersion}")
                        return@collect
                    }

                    // --- Node info ---
                    result.nodeInfo?.let { nodeInfo ->
                        ble.addNodeInfo(nodeInfo)
                        // If this is our own node, capture owner name
                        if (nodeInfo.nodeNum == (ble.myInfo.value?.myNodeNum ?: 0L)) {
                            ble.setOwner(nodeInfo.longName, nodeInfo.shortName)
                        }
                        Log.d("MeshSat", "NodeInfo: ${nodeInfo.longName} (${nodeInfo.shortName})")
                        return@collect
                    }

                    // --- Routing (ACK/NAK) ---
                    result.routing?.let { routing ->
                        val nodeId = MeshtasticProtocol.formatNodeId(routing.from)
                        if (routing.isAck) {
                            Log.d("MeshSat", "ACK from $nodeId for request ${routing.requestId}")
                        } else {
                            Log.w("MeshSat", "NAK from $nodeId: ${routing.errorName} (request ${routing.requestId})")
                        }
                        scope.launch {
                            ackTracker?.processAck("mesh", routing.requestId, routing.isAck)
                        }
                        return@collect
                    }

                    // --- Waypoint ---
                    result.waypoint?.let { wp ->
                        val nodeId = MeshtasticProtocol.formatNodeId(wp.from)
                        Log.d("MeshSat", "Waypoint from $nodeId: '${wp.name}' at ${wp.latitude},${wp.longitude}")
                        db.messageDao().insert(
                            Message(
                                transport = "mesh",
                                direction = "rx",
                                sender = nodeId,
                                text = "\uD83D\uDCCD Waypoint: ${wp.name} — ${wp.description}",
                                encrypted = false,
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                        return@collect
                    }

                    // --- Neighbor info (mesh topology) ---
                    result.neighborInfo?.let { ni ->
                        val nodeId = MeshtasticProtocol.formatNodeId(ni.nodeId)
                        val neighborList = ni.neighbors.joinToString { "${MeshtasticProtocol.formatNodeId(it.nodeId)}(${it.snr}dB)" }
                        Log.d("MeshSat", "NeighborInfo from $nodeId: [$neighborList]")
                        return@collect
                    }

                    // --- Traceroute ---
                    result.traceroute?.let { tr ->
                        val nodeId = MeshtasticProtocol.formatNodeId(tr.from)
                        val hops = tr.route.joinToString(" → ") { MeshtasticProtocol.formatNodeId(it) }
                        Log.d("MeshSat", "Traceroute from $nodeId: $hops")
                        return@collect
                    }

                    // --- Store-and-forward ---
                    result.storeForward?.let { sf ->
                        val nodeId = MeshtasticProtocol.formatNodeId(sf.from)
                        if (sf.text != null) {
                            Log.d("MeshSat", "StoreForward text from $nodeId: ${sf.text}")
                            db.messageDao().insert(
                                Message(
                                    transport = "mesh",
                                    direction = "rx",
                                    sender = nodeId,
                                    text = sf.text,
                                    encrypted = false,
                                    timestamp = System.currentTimeMillis(),
                                )
                            )
                            interfaceManager?.recordActivity("mesh_0")
                        } else {
                            Log.d("MeshSat", "StoreForward ${sf.requestResponseName} from $nodeId (${sf.messagesSaved}/${sf.messagesTotal} msgs)")
                        }
                        return@collect
                    }

                    // --- Range test ---
                    result.rangeTest?.let { rt ->
                        val nodeId = MeshtasticProtocol.formatNodeId(rt.from)
                        Log.d("MeshSat", "RangeTest from $nodeId: '${rt.payload}' SNR=${rt.rxSnr} RSSI=${rt.rxRssi}")
                        return@collect
                    }

                    // --- Detection sensor ---
                    result.detectionSensor?.let { ds ->
                        val nodeId = MeshtasticProtocol.formatNodeId(ds.from)
                        Log.d("MeshSat", "DetectionSensor from $nodeId: ${ds.name}")
                        db.messageDao().insert(
                            Message(
                                transport = "mesh",
                                direction = "rx",
                                sender = nodeId,
                                text = "\u26A0\uFE0F Sensor alert: ${ds.name}",
                                encrypted = false,
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                        return@collect
                    }

                    // --- Paxcounter ---
                    result.paxcounter?.let { pc ->
                        Log.d("MeshSat", "Paxcounter from ${MeshtasticProtocol.formatNodeId(pc.from)}")
                        return@collect
                    }

                    // --- Reply (emoji reaction) ---
                    result.reply?.let { reply ->
                        val nodeId = MeshtasticProtocol.formatNodeId(reply.from)
                        Log.d("MeshSat", "Reply from $nodeId: '${reply.payload}' emoji=${reply.emoji}")
                        return@collect
                    }

                    // --- Channel config ---
                    result.channel?.let { ch ->
                        ble.addChannel(ch)
                        Log.d("MeshSat", "Channel[${ch.index}]: '${ch.name}' role=${ch.role}")
                        return@collect
                    }

                    // --- Device metadata ---
                    result.deviceMetadata?.let { md ->
                        ble.setDeviceMetadata(md)
                        Log.d("MeshSat", "DeviceMetadata: fw=${md.firmwareVersion} hw=${md.hwModel} shutdown=${md.canShutdown}")
                        return@collect
                    }

                    // --- Radio config (response to getConfig request) ---
                    result.config?.let { config ->
                        ble.setConfig(config)
                        val section = when {
                            config.hasDevice() -> "device"
                            config.hasPosition() -> "position"
                            config.hasPower() -> "power"
                            config.hasNetwork() -> "network"
                            config.hasDisplay() -> "display"
                            config.hasLora() -> "lora"
                            config.hasBluetooth() -> "bluetooth"
                            config.hasSecurity() -> "security"
                            else -> "unknown"
                        }
                        Log.d("MeshSat", "Config received: $section")
                        return@collect
                    }

                    // --- Config complete ---
                    result.configCompleteId?.let { id ->
                        Log.d("MeshSat", "Config complete: id=$id")
                        return@collect
                    }
                }
            }
        }

        // Listen for Iridium MT (mobile-terminated) messages
        iridiumSpp?.let { spp ->
            scope.launch {
                spp.state.collect { state ->
                    if (state == IridiumSpp.State.Connected) {
                        pollIridiumMt()
                    }
                }
            }
        }

        // Listen for Iridium 9704 MT (mobile-terminated) messages via SharedFlow
        iridium9704Spp?.let { spp ->
            scope.launch {
                spp.receivedMessages.collect { payload ->
                    val text = payload.toString(Charsets.UTF_8)
                    val imei = spp.modemInfo.value.imei.ifBlank { "9704" }
                    val dedupKey = "iridium9704:${payload.contentHashCode()}"
                    if (deduplicator.isDuplicateKey(dedupKey)) return@collect
                    db.messageDao().insert(
                        Message(
                            transport = "iridium9704",
                            direction = "rx",
                            sender = imei,
                            text = text,
                            encrypted = false,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    postMessageNotification("Iridium 9704: $imei", text)
                    interfaceManager?.recordActivity("iridium9704_0")
                    evaluateAndForward(ForwardingRule.Transport.IRIDIUM, text, imei)
                }
            }
        }

        // Update notification on state changes
        meshtasticBle?.let { ble ->
            scope.launch { ble.state.collect { updateNotification() } }
        }
        iridiumSpp?.let { spp ->
            scope.launch { spp.state.collect { updateNotification() } }
        }
        iridium9704Spp?.let { spp ->
            scope.launch { spp.state.collect { updateNotification() } }
        }

        // Log errors
        meshtasticBle?.let { ble ->
            scope.launch { ble.error.collect { err -> Log.w("MeshSat", "BLE: $err") } }
        }
        iridiumSpp?.let { spp ->
            scope.launch { spp.error.collect { err -> Log.w("MeshSat", "SPP: $err") } }
        }
        iridium9704Spp?.let { spp ->
            scope.launch { spp.error.collect { err -> Log.w("MeshSat", "9704: $err") } }
        }
    }

    /**
     * Check Iridium mailbox via SBDSX, then SBDIX if messages waiting.
     * Reads MT buffer and stores/forwards the message.
     */
    private suspend fun pollIridiumMt() {
        val spp = iridiumSpp ?: return
        val status = spp.sbdStatus() ?: return

        if (status.mtFlag || status.msgWaiting > 0) {
            val result = spp.sbdix() ?: return
            if (result.mtAvailable) {
                val mtText = spp.readMtBuffer() ?: return
                val imei = spp.modemInfo.value.imei.ifBlank { "iridium" }

                // Dedup: skip if we've already processed this exact message
                val dedupKey = "iridium:$imei:${mtText.hashCode()}"
                if (deduplicator.isDuplicateKey(dedupKey)) {
                    Log.d("MeshSat", "Dedup: skipping duplicate iridium MT")
                    return
                }

                db.messageDao().insert(
                    Message(
                        transport = "iridium",
                        direction = "rx",
                        sender = imei,
                        text = mtText,
                        encrypted = false,
                        timestamp = System.currentTimeMillis(),
                    )
                )

                postMessageNotification("Iridium: $imei", mtText)
                interfaceManager?.recordActivity("iridium_0")
                evaluateAndForward(ForwardingRule.Transport.IRIDIUM, mtText, imei)
            }
        }
    }

    private suspend fun evaluateAndForward(source: ForwardingRule.Transport, text: String, sender: String) {
        // Phase B: dispatch through access rules if available
        val disp = dispatcher
        if (disp != null) {
            val sourceInterface = when (source) {
                ForwardingRule.Transport.MESH -> "mesh_0"
                ForwardingRule.Transport.IRIDIUM -> "iridium_0"
                ForwardingRule.Transport.SMS -> "sms_0"
            }
            val routeMsg = RouteMessage(text = text, from = sender, portNum = 1)
            val n = disp.dispatchAccess(sourceInterface, routeMsg, text.toByteArray())
            if (n > 0) {
                Log.i("MeshSat", "Dispatched $n deliveries via access rules from $sourceInterface")
                return // access rules handled it
            }
            // Fall through to legacy rules if no access rules matched
        }

        // Legacy: simple forwarding rules
        val decisions = rulesEngine.evaluate(source = source, text = text, sender = sender)
        for (decision in decisions) {
            if (!decision.shouldForward) continue
            when (decision.rule?.destTransport) {
                ForwardingRule.Transport.SMS -> forwardToSms(text, decision.encrypt)
                ForwardingRule.Transport.MESH -> forwardToMesh(text)
                ForwardingRule.Transport.IRIDIUM -> forwardToIridium(text)
                null -> {}
            }
        }
    }

    private suspend fun forwardToSms(text: String, encrypt: Boolean) {
        val phone = settings.meshsatPiPhone.first()
        if (phone.isBlank()) return

        val encKey = if (encrypt) {
            val key = settings.encryptionKey.first()
            key.ifBlank { null }
        } else {
            null
        }

        val compressMode = settings.compressSms.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3
        val encoder = if (compressMode == "msvqsc") msvqscEncoder else null

        SmsSender.send(
            context = this,
            to = phone,
            text = text,
            encryptionKey = encKey,
            msvqscEncoder = encoder,
            msvqscStages = stages,
        )

        db.messageDao().insert(
            Message(
                transport = "sms",
                direction = "tx",
                sender = "self",
                recipient = phone,
                text = text,
                encrypted = encrypt,
                forwarded = true,
                forwardedTo = "sms:$phone",
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun forwardToMesh(text: String) {
        val ble = meshtasticBle ?: return
        if (ble.state.value != MeshtasticBle.State.Connected) return

        val compressMode = settings.compressMesh.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3
        val outText: String
        if (compressMode == "msvqsc" && msvqscEncoder != null) {
            val wire = msvqscEncoder!!.encode(text, stages)
            if (wire != null) {
                val versioned = ProtocolVersion.prependVersionByte(wire)
                outText = android.util.Base64.encodeToString(versioned, android.util.Base64.NO_WRAP)
                Log.d("MeshSat", "Mesh TX compressed: ${text.length} chars → ${outText.length} chars (MSVQ-SC $stages stages)")
            } else {
                outText = text
            }
        } else {
            outText = text
        }

        val proto = MeshtasticProtocol.encodeTextMessage(outText)
        ble.sendToRadio(proto)

        db.messageDao().insert(
            Message(
                transport = "mesh",
                direction = "tx",
                sender = "self",
                text = text,
                forwarded = true,
                forwardedTo = "mesh:broadcast",
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun forwardToIridium(text: String) {
        val spp = iridiumSpp ?: return
        if (spp.state.value != IridiumSpp.State.Connected) return

        val compressMode = settings.compressIridium.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3

        var data = text.toByteArray(Charsets.UTF_8)
        if (compressMode == "msvqsc" && msvqscEncoder != null) {
            val wire = msvqscEncoder!!.encode(text, stages)
            if (wire != null) {
                data = ProtocolVersion.prependVersionByte(wire)
                Log.d("MeshSat", "Iridium TX compressed: ${text.length} chars → ${data.size} bytes (MSVQ-SC $stages stages)")
            }
        }

        // Fragment messages >340B using Iridium 2-byte header.
        val fragments = IridiumFragment.fragment(data, IridiumFragment.MO_MTU, nextMsgID())
        val chunks = fragments ?: listOf(data)

        for ((i, chunk) in chunks.withIndex()) {
            val written = spp.writeMoBuffer(chunk)
            if (!written) {
                Log.w("MeshSat", "Iridium MO buffer write failed for fragment $i/${chunks.size}")
                return
            }
            val result = spp.sbdix()
            if (result?.moSuccess != true) {
                Log.w("MeshSat", "SBDIX failed for fragment $i/${chunks.size}: mo_status=${result?.moStatus}")
                return
            }
        }

        db.messageDao().insert(
            Message(
                transport = "iridium",
                direction = "tx",
                sender = "self",
                text = text,
                forwarded = true,
                forwardedTo = "iridium:sbd",
                timestamp = System.currentTimeMillis(),
            )
        )
        if (fragments != null) {
            Log.i("MeshSat", "Iridium MO sent ${chunks.size} fragments (${data.size} bytes)")
        }
    }

    /** Send a text message to mesh (called from UI compose bar). */
    fun sendMeshMessage(text: String, to: Long = 0xFFFFFFFFL, channel: Int = 0) {
        val ble = meshtasticBle ?: return
        if (ble.state.value != MeshtasticBle.State.Connected) return

        scope.launch {
            val compressMode = settings.compressMesh.first()
            val stages = settings.msvqscStages.first().toIntOrNull() ?: 3
            val outText: String
            if (compressMode == "msvqsc" && msvqscEncoder != null) {
                val wire = msvqscEncoder!!.encode(text, stages)
                if (wire != null) {
                    val versioned = ProtocolVersion.prependVersionByte(wire)
                    outText = android.util.Base64.encodeToString(versioned, android.util.Base64.NO_WRAP)
                    Log.d("MeshSat", "Mesh TX compressed: ${text.length} chars → ${outText.length} chars (MSVQ-SC $stages stages)")
                } else {
                    outText = text
                }
            } else {
                outText = text
            }

            val proto = MeshtasticProtocol.encodeTextMessage(outText, to, channel)
            ble.sendToRadio(proto)

            db.messageDao().insert(
                Message(
                    transport = "mesh",
                    direction = "tx",
                    sender = "self",
                    text = text,
                    timestamp = System.currentTimeMillis(),
                )
            )

            // Emit chat CoT to ATAK + Hub (MESHSAT-191)
            takIntegration?.sendChat(text)
        }
    }

    /** Send SBD message via Iridium (called from UI). */
    fun sendIridiumMessage(text: String) {
        scope.launch {
            forwardToIridium(text)
        }
    }

    /** Send SMS message to a specific recipient (called from conversation compose). */
    private suspend fun sendSmsMessage(text: String, recipient: String) {
        // Check if we should encrypt for this recipient
        val convKeyRepo = com.cubeos.meshsat.data.ConversationKeyRepository(db.conversationKeyDao(), com.cubeos.meshsat.crypto.SecureKeyStore.getInstance(this))
        val convKey = convKeyRepo.getBySender(recipient)?.hexKey
        val globalKey = settings.encryptionKey.first()
        val encEnabled = settings.encryptionEnabled.first()

        val keyToUse = convKey?.ifEmpty { null } ?: if (encEnabled) globalKey.ifEmpty { null } else null

        val compressMode = settings.compressSms.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3

        SmsSender.send(
            context = this,
            to = recipient,
            text = text,
            encryptionKey = keyToUse,
            msvqscEncoder = if (compressMode == "msvqsc") msvqscEncoder else null,
            msvqscStages = stages,
        )

        db.messageDao().insert(
            Message(
                transport = "sms",
                direction = "tx",
                sender = "self",
                recipient = recipient,
                text = text,
                rawText = "",
                encrypted = keyToUse != null,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    // --- Notifications ---

    private fun postMessageNotification(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, MeshSatApp.CHANNEL_MESSAGES)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(notificationId++, notification)
        } catch (_: SecurityException) {}
    }

    // --- SOS ---

    private fun activateSos() {
        if (_sosActive.value) return
        _sosActive.value = true
        _sosSends.value = 0

        sosJob = scope.launch {
            val location = getLastKnownLocation()
            val locStr = if (location != null) {
                "%.6f,%.6f alt=%dm".format(location.latitude, location.longitude, location.altitude.toInt())
            } else {
                "position unknown"
            }

            val sosText = "SOS EMERGENCY - MeshSat - $locStr"

            // Emit SOS CoT event to ATAK + Hub (MESHSAT-191)
            takIntegration?.sendSOS(
                lat = location?.latitude ?: 0.0,
                lon = location?.longitude ?: 0.0,
                alt = location?.altitude ?: 0.0,
                reason = sosText,
            )

            repeat(3) { i ->
                if (!_sosActive.value) return@launch
                _sosSends.value = i + 1
                Log.w("MeshSat", "SOS send ${i + 1}/3: $sosText")

                // Send via mesh (broadcast)
                val ble = meshtasticBle
                if (ble != null && ble.state.value == MeshtasticBle.State.Connected) {
                    val proto = MeshtasticProtocol.encodeTextMessage(sosText)
                    ble.sendToRadio(proto)
                }

                // Send via Iridium
                val spp = iridiumSpp
                if (spp != null && spp.state.value == IridiumSpp.State.Connected) {
                    val data = sosText.toByteArray(Charsets.UTF_8)
                    if (data.size <= 340) {
                        val written = spp.writeMoBuffer(data)
                        if (written) spp.sbdix()
                    }
                }

                // Send via SMS to Pi
                val phone = settings.meshsatPiPhone.first()
                if (phone.isNotBlank()) {
                    SmsSender.send(this@GatewayService, phone, sosText)
                }

                // Store in DB
                db.messageDao().insert(
                    Message(
                        transport = "sos",
                        direction = "tx",
                        sender = "self",
                        text = sosText,
                        timestamp = System.currentTimeMillis(),
                    )
                )

                if (i < 2) delay(30_000) // 30s between sends
            }

            _sosActive.value = false
        }
    }

    private fun cancelSos() {
        _sosActive.value = false
        sosJob?.cancel()
        sosJob = null
        _sosSends.value = 0
        Log.w("MeshSat", "SOS cancelled")
    }

    @Suppress("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, MeshSatApp.CHANNEL_GATEWAY)
            .setContentTitle("MeshSat Gateway")
            .setContentText("Listening for mesh, satellite, and SMS messages")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Dynamically select foreground service types based on granted permissions.
                // Requesting a type without the matching runtime permission crashes on Android 14+.
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasLocation) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(1, notification, types)
            } else {
                @Suppress("DEPRECATION")
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.w("MeshSat", "startForeground with type failed, retrying: ${e.message}")
            try {
                // Fallback: connectedDevice only (always safe — permission is in manifest)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    @Suppress("DEPRECATION")
                    startForeground(1, notification)
                }
            } catch (e2: Exception) {
                Log.e("MeshSat", "startForeground failed entirely: ${e2.message}")
            }
        }
    }

    private fun updateNotification() {
        val meshState = meshtasticBle?.state?.value ?: MeshtasticBle.State.Disconnected
        val iridiumState = iridiumSpp?.state?.value ?: IridiumSpp.State.Disconnected

        val statusParts = mutableListOf<String>()
        if (meshState == MeshtasticBle.State.Connected) statusParts.add("Mesh")
        if (iridiumState == IridiumSpp.State.Connected) statusParts.add("Iridium")
        statusParts.add("SMS")

        val text = if (statusParts.size == 1) "SMS only" else statusParts.joinToString(" + ")

        val notification = NotificationCompat.Builder(this, MeshSatApp.CHANNEL_GATEWAY)
            .setContentTitle("MeshSat Gateway")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        try {
            @Suppress("DEPRECATION")
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(1, notification)
        } catch (_: Exception) {}
    }
}
