package net.meshsat.android.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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
import net.meshsat.android.MainActivity
import net.meshsat.android.MeshSatApp
import net.meshsat.android.R
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.MeshtasticProtocol
import net.meshsat.android.ble.IridiumPipeContract
import net.meshsat.android.ble.asModemLink
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.channel.ChannelRegistry
import net.meshsat.android.channel.registerAndroidDefaults
import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.crypto.MsvqscCodebook
import net.meshsat.android.crypto.MsvqscEncoder
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.ForwardingRuleEntity
import net.meshsat.android.data.Message
import net.meshsat.android.data.NodePosition
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.data.SignalRecord
import net.meshsat.android.engine.HubOrigin
import net.meshsat.android.engine.OutgoingText
import net.meshsat.android.engine.SatelliteLimits
import net.meshsat.android.engine.AckTracker
import net.meshsat.android.engine.Dispatcher
import net.meshsat.android.engine.FailoverResolver
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.engine.IridiumFragment
import net.meshsat.android.engine.InterfaceConfig
import net.meshsat.android.engine.InterfaceManager
import net.meshsat.android.engine.InterfaceState
import net.meshsat.android.engine.InterfaceStatusProvider
import net.meshsat.android.engine.SequenceTracker
import net.meshsat.android.api.LocalApiServer
import net.meshsat.android.config.ConfigManager
import net.meshsat.android.routing.KeyValueStore
import net.meshsat.android.rules.AccessEvaluator
import net.meshsat.android.rules.ForwardingRule
import net.meshsat.android.rules.RouteMessage
import net.meshsat.android.rules.RulesEngine
import net.meshsat.android.aprs.AprsCodec
import net.meshsat.android.aprs.AprsConfig
import net.meshsat.android.aprs.AprsPacket
import net.meshsat.android.aprs.Ax25Address
import net.meshsat.android.aprs.Ax25Codec
import net.meshsat.android.aprs.KissClient
import net.meshsat.android.codec.ProtocolVersion
import net.meshsat.android.mqtt.MqttTransport
import net.meshsat.android.sms.SmsSender
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Bluetooth connections alive: the MeshSat node over BLE,
 * carrying both Meshtastic and the 9603's Iridium pipe, and a 9704 over HC-05 SPP.
 *
 * Manages transport lifecycle, message routing via RulesEngine,
 * signal history tracking, node position storage, notifications, and SOS.
 */
class GatewayService : Service() {

    companion object {
        const val ACTION_CONNECT_MESH = "net.meshsat.android.CONNECT_MESH"
        const val ACTION_CONNECT_IRIDIUM9704 = "net.meshsat.android.CONNECT_IRIDIUM9704"
        const val ACTION_DISCONNECT_MESH = "net.meshsat.android.DISCONNECT_MESH"
        const val ACTION_DISCONNECT_IRIDIUM9704 = "net.meshsat.android.DISCONNECT_IRIDIUM9704"
        const val ACTION_SOS_ACTIVATE = "net.meshsat.android.SOS_ACTIVATE"
        const val ACTION_SOS_CANCEL = "net.meshsat.android.SOS_CANCEL"
        const val ACTION_SEND_MESH = "net.meshsat.android.SEND_MESH"
        const val ACTION_SEND_IRIDIUM = "net.meshsat.android.SEND_IRIDIUM"
        const val ACTION_SEND_SMS = "net.meshsat.android.SEND_SMS"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_TEXT = "text"
        const val EXTRA_RECIPIENT = "recipient"
        /** With [ACTION_SOS_ACTIVATE]: a test of the alarm routes, which raises no alarm (MESHSAT-1249). */
        const val EXTRA_SOS_TEST = "sos_test"

        // Multi-instance transport registry (MESHSAT-388)
        val registry = TransportRegistry()

        // Singleton references for UI state observation
        var meshtasticBle: MeshtasticBle? = null
            private set
        var iridiumSpp: IridiumSpp? = null
            private set
        var iridium9704Spp: net.meshsat.android.bt.Iridium9704Spp? = null
            private set

        private var service: GatewayService? = null

        /** The running service as a Context, for the local API's diagnostics (MESHSAT-616). */
        val appContext: android.content.Context? get() = service
        private const val IRIDIUM_STATUS_NOTIFICATION_ID = 7603
        const val IRIDIUM_QUEUED = "iridium:queued"
        /** A satellite send that reported a failure after the upload, so it may have arrived. */
        const val IRIDIUM_UNCONFIRMED = "iridium:unconfirmed"
        /** The Hub confirmed it has the satellite message: the second tick (MESHSAT-1246). */
        const val IRIDIUM_DELIVERED = "iridium:delivered"
        /** A signal reading this strong sends what waits for the satellite at once (the Bridge's min_signal_bars). */
        const val IRIDIUM_MIN_SIGNAL_BARS = 1
        /** How often the node is asked again for its modem while the phone does not hold it. */
        private const val PIPE_CLAIM_RETRY_MS = 15_000L
        private const val PIPE_CLAIM_FIRST_RETRY_MS = 3_000L

        /** At most one node reconnect this often when the pipe stops taking writes (MESHSAT-1270). */
        private const val PIPE_RECOVERY_COOLDOWN_MS = 60_000L

        /** A mailbox check the user asked for (MESHSAT-400): running, or its last outcome. */
        data class MailboxCheck(
            val running: Boolean = false,
            val result: IridiumSpp.MailboxResult? = null,
            val finishedAt: Long = 0,
        )

        private val _passes = MutableStateFlow<List<net.meshsat.android.satellite.PassPrediction>>(emptyList())

        /** The last pass predictions for the phone's position, for Home's satellite lane. */
        val passes: StateFlow<List<net.meshsat.android.satellite.PassPrediction>> = _passes

        private val _mailbox = MutableStateFlow(MailboxCheck())
        val mailbox: StateFlow<MailboxCheck> = _mailbox

        /**
         * Check the Iridium mailbox now: one billed SBDIX (see [IridiumSpp.checkMailbox]). It
         * runs in the service, so leaving the screen does not lose a received message.
         * Returns false if the service is not running or a check is already under way.
         */
        fun checkIridiumMailbox(): Boolean = service?.startMailboxCheck() ?: false
        val rulesEngine = RulesEngine()

        // SOS state: a real SOS is on (MESHSAT-1249; the run itself is SosController.run)
        private val _sosActive = MutableStateFlow(false)
        val sosActive: StateFlow<Boolean> = _sosActive
        fun noteSosActive(active: Boolean) { _sosActive.value = active }

        // Phone GPS location (updated continuously)
        private val _phoneLocation = MutableStateFlow<Location?>(null)
        val phoneLocation: StateFlow<Location?> = _phoneLocation

        // Phase D: field intelligence singletons (exposed for UI observation)
        var geofenceMonitor: net.meshsat.android.engine.GeofenceMonitor? = null
            private set
        var deadManSwitch: net.meshsat.android.engine.DeadManSwitch? = null
            private set
        var burstQueue: net.meshsat.android.engine.BurstQueue? = null
            private set
        var healthScorer: net.meshsat.android.engine.HealthScorer? = null
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
        var aprsIsClient: net.meshsat.android.aprs.AprsIsClient? = null
            private set

        // Phase J: exposed for audit log UI
        var signingServiceRef: net.meshsat.android.engine.SigningService? = null
            private set

        // TAK/CoT integration (MESHSAT-191)
        var takIntegration: net.meshsat.android.tak.TakIntegration? = null
            private set

        // APRS beacon (MESHSAT-231)
        var aprsBeacon: net.meshsat.android.aprs.AprsBeacon? = null
            private set

        // APRS directed message tracker (MESHSAT-232)
        var aprsMessageTracker: net.meshsat.android.aprs.AprsMessageTracker? = null
            private set

        // Reticulum TCP interface (MESHSAT-268)
        var rnsTcpInterface: net.meshsat.android.reticulum.RnsTcpInterface? = null
            private set

        // Reticulum MQTT interface (MESHSAT-354)
        var rnsMqttInterface: net.meshsat.android.reticulum.RnsMqttInterface? = null
            private set

        // Reticulum Transport Node (MESHSAT-199/267)
        var rnsTransportNode: net.meshsat.android.reticulum.RnsTransportNode? = null
            private set

        // This phone's Reticulum identity, the key that signs a contact card (MESHSAT-566/575)
        var routingIdentity: net.meshsat.android.routing.Identity? = null
            private set

        // Hub Reporter — bridge-to-hub uplink protocol (MESHSAT-292)
        private val _hubReporterNow = MutableStateFlow<net.meshsat.android.hub.HubReporter?>(null)

        /**
         * The reporter as a flow, for screens: a restart of the gateway makes a new reporter,
         * and a screen that read the old one kept showing it - "Not set up" on the Hub card
         * right after provisioning (MESHSAT-749).
         */
        val hubReporterNow: StateFlow<net.meshsat.android.hub.HubReporter?> = _hubReporterNow

        var hubReporter: net.meshsat.android.hub.HubReporter? = null
            private set(value) {
                field = value
                _hubReporterNow.value = value
            }

        /** The battery of the node this phone is connected to, as it last reported it (MESHSAT-1315). */
        data class NodeBatteryNow(
            val nodeNum: Long,
            val level: Int,
            val voltage: Float,
            val hoursLeft: Double?,
            val atMs: Long,
        )

        private val _nodeBattery = MutableStateFlow<NodeBatteryNow?>(null)
        val nodeBattery: StateFlow<NodeBatteryNow?> = _nodeBattery

        /** One stored battery reading a minute is plenty for an estimate over hours. */
        private const val NODE_BATTERY_SAMPLE_MS = 60_000L

        // Hub relay client — Reticulum over a Hub WebSocket tunnel to one kit (MESHSAT-1157)
        var hubRelayTransport: net.meshsat.android.hub.relay.RelayBridgeTransport? = null
            private set

        // Pass-aware satellite scheduling (MESHSAT-386)
        var passScheduler: net.meshsat.android.satellite.PassScheduler? = null
            private set

        // Release telemetry ring buffer (MESHSAT-494)
        var telemetryLogger: net.meshsat.android.engine.TelemetryLogger? = null
            private set

        private var notificationId = 100

        /**
         * Schedule a service restart via AlarmManager.
         * Sends the response before stopping, then the alarm re-starts the service.
         */
        fun scheduleRestart(context: android.content.Context, delayMs: Long = 2000L) {
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(context, GatewayService::class.java)
            val pi = android.app.PendingIntent.getService(
                context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            am.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + delayMs,
                pi,
            )
            // Called from a screen, not from the service, this used to set the alarm and stop
            // nothing: the alarm then "started" a service that was already running, so Settings'
            // Restart button restarted nothing (found 21 Sep 2026 on MESHSAT-749).
            if (context is android.app.Service) context.stopSelf()
            else context.stopService(android.content.Intent(context, GatewayService::class.java))
        }
    }

    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var db: AppDatabase
    private var sosController: net.meshsat.android.sos.SosController? = null
    private var msvqscEncoder: MsvqscEncoder? = null

    // Phase A: core infrastructure (dedup + transform)
    private val deduplicator = net.meshsat.android.dedup.Deduplicator()
    private val transformPipeline = net.meshsat.android.engine.TransformPipeline()

    // Phase B: structured dispatch (replaces basic if/else routing)
    private var dispatcher: Dispatcher? = null
    private var accessEvaluator: AccessEvaluator? = null

    // Phase C: transport hardening
    private var interfaceManager: InterfaceManager? = null
    private var ackTracker: AckTracker? = null
    private val sequenceTracker = SequenceTracker()

    // Phase F: config, API, signing
    private var signingService: net.meshsat.android.engine.SigningService? = null
    private var configManager: ConfigManager? = null
    private var localApiServer: LocalApiServer? = null

    override fun onCreate() {
        super.onCreate()
        service = this
        settings = SettingsRepository(this)
        db = AppDatabase.getInstance(this)

        // Initialize SecureKeyStore early (triggers key migration from old storage — MESHSAT-194)
        net.meshsat.android.crypto.SecureKeyStore.getInstance(this)

        meshtasticBle = MeshtasticBle(this)
        registry.register("ble_mesh_0", meshtasticBle!!)
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        iridiumSpp = IridiumSpp().also { spp ->
            // Every satellite session is kept, taken or not, for the chart's session dots (MESHSAT-1300).
            scope.launch {
                spp.sessionOutcomes.collect { ok ->
                    db.signalDao().insert(SignalRecord(source = "gss", value = if (ok) 1 else 0))
                }
            }
            // Any satellite session can bring a message in: it is stored the moment it arrives.
            spp.mtSink = { bytes -> storeIridiumMt(spp, String(bytes, Charsets.UTF_8)) }
        }
        registry.register("iridium_spp_0", iridiumSpp!!)
        iridium9704Spp = net.meshsat.android.bt.Iridium9704Spp(this)
        registry.register("iridium_imt_0", iridium9704Spp!!)

        startForegroundNotification()

        try {
            // Complete encryption key migration (remove from DataStore after secure store confirmed)
            scope.launch { settings.completeMigration() }
            loadRulesFromDb()
            deduplicator.startPruner(scope)
            initInterfaceManager()
            initTelemetry()
            initSos()
            initDispatcher()
            initFieldIntelligence()
            initSigningAndApi()
            observeTransports()
            reconnectSavedNode()
            observeIridiumPipe()
            observeIridiumStatusIcon()
            startSignalPolling()
            startLocationUpdates()
            initMsvqsc()
            initMqtt()
            initSmsRelay()
            initAprs()
            initRnsTcp()
            initHubRelay()
            initReticulumTransportNode()
            initHubReporter()
            // 15. Pass-aware scheduling (MESHSAT-386)
            initPassScheduler()
        } catch (e: Exception) {
            Log.e("MeshSat", "Service init error (non-fatal): ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT_MESH -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                // Connect re-arms the auto-reconnect that Disconnect switched off (MESHSAT-1239).
                interfaceManager?.enable("mesh_0")
                meshtasticBle?.connect(addr)
                scope.launch { settings.setMeshtasticBleAddress(addr) }
            }
            ACTION_CONNECT_IRIDIUM9704 -> {
                val addr = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                iridium9704Spp?.connect(addr)
                scope.launch { settings.setIridium9704BtAddress(addr) }
            }
            ACTION_DISCONNECT_MESH -> {
                // The user's own Disconnect: no auto-reconnect, now or at the next start.
                interfaceManager?.disable("mesh_0")
                meshtasticBle?.disconnect()
                scope.launch { settings.clearMeshtasticBleAddress() }
            }
            ACTION_DISCONNECT_IRIDIUM9704 -> iridium9704Spp?.disconnect()
            ACTION_SOS_ACTIVATE -> {
                val test = intent.getBooleanExtra(EXTRA_SOS_TEST, false)
                scope.launch { sosController?.start(test = test, trigger = "button") }
            }
            ACTION_SOS_CANCEL -> scope.launch { sosController?.cancel() }
            ACTION_SEND_MESH -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                // A reply to a node goes to that node, not to the whole channel (MESHSAT-1249).
                val to = net.meshsat.android.ui.Peers.nodeNum(intent.getStringExtra(EXTRA_RECIPIENT).orEmpty())
                sendMeshMessage(text, to ?: 0xFFFFFFFFL)
            }
            ACTION_SEND_IRIDIUM -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                queueIridiumMessage(text, intent.getStringExtra(EXTRA_RECIPIENT) ?: "")
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

    /**
     * Bluetooth going off never reaches the GATT callback, so the node link is told here
     * (MESHSAT-615): without it the app held a dead link and called it connected.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> meshtasticBle?.onBluetoothOff()
                BluetoothAdapter.STATE_ON -> meshtasticBle?.onBluetoothOn()
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered: onCreate did not get that far.
        }
        registry.clear()
        passScheduler?.stop()
        passScheduler = null
        hubReporter?.stop()
        hubReporter = null
        // It lives in the companion, so a restarted service would otherwise run a second
        // client beside it under the same client id (MESHSAT-1305).
        mqttTransport?.disconnect()
        mqttTransport = null
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
        sosController?.stop()
        sosController = null
        rnsTransportNode?.stop()
        rnsTransportNode = null
        hubRelayTransport?.shutdown()
        hubRelayTransport = null
        meshtasticBle?.disconnect()
        iridiumSpp?.disconnect()
        iridium9704Spp?.disconnect()
        meshtasticBle = null
        iridiumSpp = null
        iridium9704Spp = null
        msvqscEncoder?.close()
        msvqscEncoder = null
        net.meshsat.android.sms.SmsReceiver.relayCallback = null
        scope.cancel()
        if (service === this) service = null
        NotificationManagerCompat.from(this).cancel(IRIDIUM_STATUS_NOTIFICATION_ID)
        super.onDestroy()
    }

    /** Initialize Phase D: field intelligence components (geofence, dead man's switch, burst queue, health). */
    private fun initFieldIntelligence() {
        try {
            // Geofence monitor
            val gm = net.meshsat.android.engine.GeofenceMonitor()
            geofenceMonitor = gm

            // Dead man's switch (default 2h timeout, disabled by default)
            val dms = net.meshsat.android.engine.DeadManSwitch(
                positionDao = db.nodePositionDao(),
                timeout = kotlin.time.Duration.parse("2h"),
            )
            dms.sosCallback = { lat, lon, lastSeen ->
                android.util.Log.w("MeshSat", "Dead man's switch triggered at $lat,$lon (last seen: $lastSeen)")
                // Emit dead man CoT event to ATAK + Hub (MESHSAT-191)
                val elapsed = (System.currentTimeMillis() / 1000) - lastSeen
                takIntegration?.sendDeadman(lat, lon, elapsed.toInt())
                scope.launch { sosController?.start(test = false, trigger = "checkin") }
            }
            deadManSwitch = dms

            // Burst queue (max 10 messages, 5 min age)
            val bq = net.meshsat.android.engine.BurstQueue(
                maxSize = 10,
                maxAge = kotlin.time.Duration.parse("5m"),
            )
            burstQueue = bq

            // Health scorer (needs interfaceManager + channel registry)
            val im = interfaceManager
            if (im != null) {
                val hs = net.meshsat.android.engine.HealthScorer(
                    interfaceManager = im,
                    channelRegistry = net.meshsat.android.channel.ChannelRegistry().also {
                        net.meshsat.android.channel.registerAndroidDefaults(it)
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
     * Initialize release telemetry ring buffer (MESHSAT-494): recover any
     * pending crash dump from the previous launch, install the periodic heap
     * and health samplers, and record a service-start event.
     */
    private fun initTelemetry() {
        val logger = net.meshsat.android.engine.TelemetryLogger(
            dao = db.telemetryDao(),
            scope = scope,
            enabledProvider = { settings.telemetryEnabled.first() },
        )
        telemetryLogger = logger

        scope.launch {
            // 1. Recover any crash from the previous launch (must come before
            //    the heap sampler fires so the crash is earlier in the timeline)
            logger.recoverPendingCrashes(this@GatewayService)

            // 2. Service-start event
            logger.recordEvent(
                tag = "GatewayService",
                message = "Service started (v${net.meshsat.android.BuildConfig.VERSION_NAME})",
                detail = mapOf(
                    "versionCode" to net.meshsat.android.BuildConfig.VERSION_CODE,
                    "versionName" to net.meshsat.android.BuildConfig.VERSION_NAME,
                    "deviceModel" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    "osVersion" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                ),
            )

            // 3. Heap sampler — every 5 minutes
            scope.launch {
                while (isActive) {
                    logger.recordHeap()
                    delay(5 * 60 * 1000L)
                }
            }

            // 4. Health heartbeat — every 60 seconds
            scope.launch {
                while (isActive) {
                    val ifaces = interfaceManager?.getAllStatus() ?: emptyList()
                    val online = ifaces.count { it.state.isAvailable }
                    val mode = passScheduler?.mode?.value?.name ?: "none"
                    logger.recordHealth(
                        message = "iface ${online}/${ifaces.size} online, pass mode $mode",
                        detail = mapOf(
                            "interfacesOnline" to online,
                            "interfacesTotal" to ifaces.size,
                            "passMode" to mode,
                            "deadManTriggered" to (deadManSwitch?.isTriggered() ?: false),
                            "sosActive" to _sosActive.value,
                            "foregroundService" to true,
                        ),
                    )
                    delay(60 * 1000L)
                }
            }

            Log.i("MeshSat", "Telemetry initialized (heap+health samplers started)")
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
                val kvStore: KeyValueStore = net.meshsat.android.crypto.SecureKeyStore.getInstance(this@GatewayService)

                // Signing service
                val signing = net.meshsat.android.engine.SigningService(db.auditLogDao(), kvStore)
                signing.loadLastHash()
                signingService = signing
                signingServiceRef = signing
                Log.i("MeshSat", "SigningService initialized: ${signing.signerId.take(16)}...")

                // Config manager
                val cfgMgr = ConfigManager(db.accessRuleDao(), db.objectGroupDao(), db.failoverGroupDao()) {
                    // The evaluator keeps the rules in memory; an import must make it read again.
                    accessEval?.reloadFromDb()
                }
                configManager = cfgMgr

                // Local API server (localhost:6051). An edition without SMS has no send callback,
                // so POST /api/sms/send answers 503 instead of pretending (MESHSAT-1335).
                val smsSend: ((String, String) -> Unit)? =
                    if (net.meshsat.android.sms.SmsCapability.included) { to, text -> scope.launch { sendSmsMessage(text, to) } } else null
                val server = LocalApiServer(
                    scope = scope,
                    interfaceManager = interfaceManager,
                    channelRegistry = null, // Not stored as field yet; could be wired later
                    healthScorer = healthScorer,
                    deliveryDao = db.messageDeliveryDao(),
                    auditLogDao = db.auditLogDao(),
                    telemetryDao = db.telemetryDao(),
                    geofenceMonitor = geofenceMonitor,
                    deadManSwitch = deadManSwitch,
                    signingService = signing,
                    configManager = cfgMgr,
                    restartCallback = {
                        scope.launch {
                            delay(500)
                            scheduleRestart(this@GatewayService)
                        }
                    },
                    smsSendCallback = smsSend,
                    hubSettingsCallback = { settings ->
                        scope.launch {
                            val s = net.meshsat.android.data.SettingsRepository(this@GatewayService)
                            settings["hub_enabled"]?.let { s.setHubEnabled(it.toBoolean()) }
                            settings["hub_url"]?.let { s.setHubUrl(it) }
                            settings["hub_bridge_id"]?.let { s.setHubBridgeId(it) }
                            settings["hub_callsign"]?.let { s.setHubCallsign(it) }
                            settings["hub_username"]?.let { s.setHubUsername(it) }
                            settings["hub_password"]?.let { s.setHubPassword(it) }
                            settings["hub_relay_enabled"]?.let { s.setHubRelayEnabled(it.toBoolean()) }
                            settings["hub_relay_target"]?.let { s.setHubRelayTarget(it) }
                            settings["hub_relay_url"]?.let { s.setHubRelayUrl(it) }
                            settings["tak_enabled"]?.let { s.setTakEnabled(it.toBoolean()) }
                            settings["tak_callsign_prefix"]?.let { s.setTakCallsignPrefix(it) }
                            settings["tak_mqtt_export"]?.let { s.setTakMqttExport(it.toBoolean()) }
                            Log.i("MeshSat", "Hub settings updated via API (${settings.size} keys)")
                        }
                    },
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
                val clientCert = settings.hubClientCertPem.first()
                val clientKey = settings.hubClientKeyPem.first()
                val caCert = settings.hubCaCertPem.first()

                val transport = MqttTransport(scope)
                transport.setMessageCallback { topic, payload ->
                    handleMqttInbound(topic, payload)
                }
                transport.connect(brokerUrl, deviceId, username, password, certPin, certPinBackup,
                    clientCertPem = clientCert, clientKeyPem = clientKey, caCertPem = caCert)
                mqttTransport = transport

                // Initialize Reticulum MQTT interface (MESHSAT-354)
                val mqttDeviceId = deviceId
                rnsMqttInterface = net.meshsat.android.reticulum.RnsMqttInterface(
                    mqtt = transport,
                    deviceId = { mqttDeviceId },
                    interfaceId = "mqtt_rns_0",
                )
                Log.i("MeshSat", "RNS MQTT interface initialized for device $mqttDeviceId")

                // Initialize TAK/CoT integration (MESHSAT-191, MESHSAT-451)
                val takEn = settings.takEnabled.first()
                val takPrefix = settings.takCallsignPrefix.first().ifBlank { "MESHSAT" }
                val takAtak = settings.takAtakBroadcast.first()
                val takMqtt = settings.takMqttExport.first()
                takIntegration = net.meshsat.android.tak.TakIntegration(
                    context = this@GatewayService,
                    mqtt = if (takEn) transport else null,
                    deviceId = deviceId,
                    callsignPrefix = takPrefix,
                    atakBroadcastEnabled = takAtak,
                    mqttExportEnabled = takMqtt,
                )
                if (!takEn) {
                    Log.d("MeshSat", "TAK/CoT disabled in settings")
                } else {
                    Log.i("MeshSat", "TAK/CoT integration initialized: callsign=${takIntegration?.callsign} atak=$takAtak mqtt=$takMqtt")
                }

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
                val clientCert = settings.hubClientCertPem.first()
                val clientKey = settings.hubClientKeyPem.first()
                val caCert = settings.hubCaCertPem.first()

                val config = net.meshsat.android.hub.HubReporterConfig(
                    hubUrl = hubUrl,
                    bridgeId = bridgeId,
                    callsign = callsign,
                    username = username,
                    password = password,
                    certPin = certPin,
                    certPinBackup = certPinBackup,
                    healthIntervalSec = healthInterval,
                    clientCertPem = clientCert,
                    clientKeyPem = clientKey,
                    caCertPem = caCert,
                )

                val reporter = net.meshsat.android.hub.HubReporter(
                    context = this@GatewayService,
                    scope = scope,
                    config = config,
                )
                reporter.setCommandCallback { cmd ->
                    handleHubCommand(cmd)
                }
                // TAK CoT broadcast: parse and store positions in Room DB for map display
                reporter.onTakCot = { cotXml ->
                    scope.launch {
                        try {
                            // Try TakIntegration first, fall back to direct XML parsing
                            val tak = takIntegration
                            val cotEvent = tak?.parseInbound(cotXml)
                            val lat: Double
                            val lon: Double
                            val alt: Int
                            val sender: String
                            if (cotEvent != null) {
                                lat = cotEvent.point.lat
                                lon = cotEvent.point.lon
                                alt = cotEvent.point.hae.toInt()
                                sender = cotEvent.detail?.contact?.callsign ?: cotEvent.uid
                            } else {
                                // Direct XML extraction when TakIntegration not initialized
                                val latM = Regex("""lat="([^"]+)"""").find(cotXml)
                                val lonM = Regex("""lon="([^"]+)"""").find(cotXml)
                                val csM = Regex("""callsign="([^"]+)"""").find(cotXml)
                                val uidM = Regex("""uid="([^"]+)"""").find(cotXml)
                                lat = latM?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                                lon = lonM?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                                alt = 0
                                sender = csM?.groupValues?.get(1) ?: uidM?.groupValues?.get(1) ?: "unknown"
                            }
                            if (lat != 0.0 && lon != 0.0) {
                                val nodeHash = sender.hashCode().toLong() and 0xFFFFFFFFL
                                db.nodePositionDao().insert(
                                    net.meshsat.android.data.NodePosition(
                                        nodeId = nodeHash,
                                        nodeName = sender,
                                        latitude = lat,
                                        longitude = lon,
                                        altitude = alt,
                                    )
                                )
                                Log.i("MeshSat", "TAK position stored from Hub: $sender $lat,$lon")
                            }
                        } catch (e: Exception) {
                            Log.w("MeshSat", "TAK CoT parse failed: ${e.message}")
                        }
                    }
                }
                reporter.onMoAck = { imei, momsn -> scope.launch { onHubReceipt(imei, momsn) } }
                reporter.start()
                hubReporter = reporter
                Log.i("MeshSat", "Hub Reporter initialized: bridge=$bridgeId")
                // hub_0 follows this client (MESHSAT-1261). The observer belongs here, not in
                // initInterfaceManager: that runs before this one, so it would bind to a null
                // reporter and collect nothing, leaving hub_0 Offline while the Hub was up.
                scope.launch {
                    reporter.state.collect { state ->
                        val mgr = interfaceManager ?: return@collect
                        when (state) {
                            HubReporter.State.Connected -> mgr.setOnline("hub_0")
                            HubReporter.State.Connecting -> mgr.setConnecting("hub_0")
                            HubReporter.State.Disconnected -> mgr.setOffline("hub_0")
                            HubReporter.State.Error -> mgr.setError("hub_0", "Hub connection failed")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MeshSat", "Hub Reporter init failed: ${e.message}")
            }
        }
    }

    /** Handle inbound commands from the Hub via HubReporter. */
    private fun handleHubCommand(cmd: net.meshsat.android.hub.HubCommand) {
        scope.launch {
            try {
                when (cmd.cmd) {
                    "send_text" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val text = json.optString("text", "")
                        if (text.isNotBlank()) {
                            val msg = net.meshsat.android.rules.RouteMessage(
                                text = text, from = "hub", channel = 0, portNum = 1,
                                visited = listOf("hub_reporter"),
                            )
                            dispatcher?.dispatchAccess("hub_reporter", msg, text.toByteArray())
                        }
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
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
                        val db = net.meshsat.android.data.AppDatabase.getInstance(this@GatewayService)
                        db.providerCredentialDao().upsert(
                            net.meshsat.android.data.ProviderCredential(
                                id = credId, provider = provider, name = name,
                                credType = credType, encryptedData = data,
                                certNotAfter = certNotAfter.ifBlank { null },
                                certFingerprint = certFingerprint, version = version,
                                source = "hub",
                            )
                        )
                        Log.i("MeshSat", "Credential received from Hub: $credId ($provider)")
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                            )
                        )
                    }
                    "credential_revoke" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val credId = json.optString("credential_id", "")
                        val db = net.meshsat.android.data.AppDatabase.getInstance(this@GatewayService)
                        db.providerCredentialDao().deleteById(credId)
                        Log.i("MeshSat", "Credential revoked by Hub: $credId")
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                            )
                        )
                    }
                    "send_mt" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val text = json.optString("text", "")
                        val dataB64 = json.optString("data", "")
                        val targetDevice = json.optString("target_device", "iridium_spp_0")
                        val result: String? = when {
                            targetDevice.contains("9704") || targetDevice.contains("imt") -> {
                                val spp = iridium9704Spp
                                if (spp == null || spp.state.value != net.meshsat.android.bt.Iridium9704Spp.State.Ready) {
                                    "iridium 9704 not connected"
                                } else {
                                    val payload = if (dataB64.isNotBlank()) {
                                        android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
                                    } else text.toByteArray()
                                    val status = spp.sendMessageBlocking(payload)
                                    if (status != null) null else "send timed out"
                                }
                            }
                            else -> {
                                val spp = iridiumSpp
                                if (spp == null || spp.state.value != net.meshsat.android.bt.IridiumSpp.State.Connected) {
                                    "iridium spp not connected"
                                } else {
                                    val payload = if (dataB64.isNotBlank()) {
                                        android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
                                    } else text.toByteArray()
                                    val writeOk = spp.writeMoBuffer(payload)
                                    if (!writeOk) "failed to write MO buffer"
                                    else {
                                        val sbdResult = spp.sbdix()
                                        if (sbdResult != null && sbdResult.moStatus in 0..4) null
                                        else "SBDIX failed: moStatus=${sbdResult?.moStatus}"
                                    }
                                }
                            }
                        }
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd,
                                status = if (result == null) "ok" else "error",
                                error = result ?: "",
                            )
                        )
                    }
                    "flush_burst" -> {
                        val (payload, count) = burstQueue?.flush() ?: (null to 0)
                        // Through the delivery queue, never straight to the modem: a direct write
                        // overwrites whatever message the queue has in the MO buffer (MESHSAT-1249).
                        if (payload != null && count > 0) {
                            dispatcher?.enqueueDirect("iridium_0", payload, "Batch of $count messages", "burst:${System.currentTimeMillis()}")
                        }
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                                error = "",
                            )
                        )
                        Log.i("MeshSat", "Hub flush_burst: flushed $count messages")
                    }
                    "config_update" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        var updated = 0
                        if (json.has("health_interval")) {
                            settings.setHubHealthInterval(json.getInt("health_interval").toString())
                            updated++
                        }
                        if (json.has("deadman_enabled")) {
                            settings.setDeadmanEnabled(json.getBoolean("deadman_enabled"))
                            updated++
                        }
                        if (json.has("deadman_timeout_min")) {
                            settings.setDeadmanTimeoutMin(json.getInt("deadman_timeout_min").toString())
                            updated++
                        }
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                                error = "",
                            )
                        )
                        Log.i("MeshSat", "Hub config_update: updated $updated keys")
                    }
                    "reboot" -> {
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                                error = "",
                            )
                        )
                        Log.i("MeshSat", "Hub reboot command received, scheduling restart")
                        scheduleRestart(this@GatewayService)
                    }
                    "hemb_bond_create" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val bondId = json.optString("bond_id", "")
                        val label = json.optString("label", "")
                        val membersArr = json.optJSONArray("members")
                        val members = if (membersArr != null) {
                            (0 until membersArr.length()).map { membersArr.getString(it) }
                        } else emptyList()
                        val costBudget = json.optDouble("cost_budget", 0.0)
                        val db = net.meshsat.android.data.AppDatabase.getInstance(this@GatewayService)
                        db.hembBondGroupDao().insert(
                            net.meshsat.android.data.HembBondGroupEntity(
                                id = bondId,
                                label = label,
                                members = org.json.JSONArray(members).toString(),
                                costBudget = costBudget,
                            )
                        )
                        Log.i("MeshSat", "HeMB bond group created from Hub: $bondId ($label)")
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                            )
                        )
                    }
                    "hemb_bond_delete" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val bondId = json.optString("bond_id", "")
                        val db = net.meshsat.android.data.AppDatabase.getInstance(this@GatewayService)
                        db.hembBondGroupDao().delete(bondId)
                        Log.i("MeshSat", "HeMB bond group deleted by Hub: $bondId")
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                            )
                        )
                    }
                    "key_rotate" -> {
                        val json = org.json.JSONObject(cmd.payload)
                        val channelType = json.optString("channel_type", "")
                        val address = json.optString("address", "")
                        val keyHex = json.optString("key_hex", "")
                        val version = json.optInt("version", 1)
                        val secureStore = net.meshsat.android.crypto.SecureKeyStore.getInstance(this@GatewayService)
                        secureStore.set("hub_key:${channelType}:${address}", keyHex)
                        if (address.isNotBlank()) {
                            val db = net.meshsat.android.data.AppDatabase.getInstance(this@GatewayService)
                            val convKeyRepo = net.meshsat.android.data.ConversationKeyRepository(
                                db.conversationKeyDao(), secureStore,
                            )
                            convKeyRepo.upsert(address, keyHex, "hub-rotated-v$version")
                        }
                        Log.i("MeshSat", "Key rotated from Hub: $channelType:$address v$version")
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
                                requestId = cmd.requestId, cmd = cmd.cmd, status = "ok",
                            )
                        )
                    }
                    else -> {
                        hubReporter?.publishCommandResponse(
                            net.meshsat.android.hub.CommandResponse(
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
                    net.meshsat.android.hub.CommandResponse(
                        requestId = cmd.requestId,
                        cmd = cmd.cmd,
                        status = "error",
                        error = e.message ?: "unknown error",
                    )
                )
            }
        }
    }

    /** Initialize pass-aware satellite scheduling (MESHSAT-386, MESHSAT-498). */
    /**
     * Refresh the orbital elements when the phone happens to be online: the downloaded set is
     * over a day old, and at most one attempt every 12 hours, which also keeps the app polite
     * towards Celestrak. A failure keeps the current elements; predictions never wait on this.
     */
    private fun startTleRefresh(fetcher: net.meshsat.android.satellite.TleFetcher) {
        scope.launch {
            var lastAttemptMs = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (now - lastAttemptMs >= 12 * 3600_000L && fetcher.isCacheStale()) {
                    lastAttemptMs = now
                    fetcher.refreshFromNetwork()
                }
                delay(3600_000L)
            }
        }
    }

    private fun initPassScheduler() {
        val iridium = iridiumSpp ?: return
        // Offline first: the last download or the snapshot shipped in the app, never the network.
        val tleFetcher = net.meshsat.android.satellite.TleFetcher.forContext(this, db)
        startTleRefresh(tleFetcher)
        // Cache pass predictions to avoid recomputing SGP4 every 30s (MESHSAT-498)
        var cachedPasses: List<net.meshsat.android.satellite.PassPrediction> = emptyList()
        var cacheTimestampMs = 0L
        val cacheTtlMs = 5 * 60 * 1000L // 5 minutes
        val predictor = {
            val nowMs = System.currentTimeMillis()
            if (nowMs - cacheTimestampMs < cacheTtlMs && cachedPasses.isNotEmpty()) {
                cachedPasses.filter { it.losUnix >= nowMs / 1000 }
            } else {
                val tleSet = kotlinx.coroutines.runBlocking { tleFetcher.localTles() }
                val allTles = tleSet.tles
                val loc = _phoneLocation.value
                if (allTles.isNotEmpty() && loc != null) {
                    val startMs = System.currentTimeMillis()
                    val parsed = allTles
                    // PassPredictor expects unix SECONDS. Passing milliseconds would make
                    // the propagation loop iterate 1000x too many steps and OOM the heap (MESHSAT-498).
                    val nowSec = nowMs / 1000
                    val passes = parsed.flatMap { tleElements ->
                        try {
                            net.meshsat.android.satellite.PassPredictor.predictPasses(
                                tle = tleElements,
                                lat = loc.latitude,
                                lon = loc.longitude,
                                altKm = (loc.altitude / 1000.0),
                                // Three hours back as well, for the signal-vs-passes chart on
                                // Home (MESHSAT-1300); the scheduler still gets only what has
                                // not ended yet (below).
                                startUnix = nowSec - 3 * 3600L,
                                endUnix = nowSec + 6 * 3600L, // 6 hours in seconds
                            )
                        } catch (e: Exception) {
                            Log.w("MeshSat", "SGP4 failed for ${tleElements.name}: ${e.message}")
                            emptyList()
                        }
                    }
                    // Sort using Comparator to avoid Long autoboxing (MESHSAT-498)
                    val sorted = passes.sortedWith(Comparator { a, b -> a.aosUnix.compareTo(b.aosUnix) })
                    val elapsedMs = System.currentTimeMillis() - startMs
                    Log.i("MeshSat", "Pass prediction: ${parsed.size} TLEs (${tleSet.source}, ${tleSet.ageSec() / 3600}h old), ${sorted.size} passes, ${elapsedMs}ms")
                    cachedPasses = sorted
                    _passes.value = sorted
                    cacheTimestampMs = nowMs
                    sorted.filter { it.losUnix >= nowSec }
                } else emptyList()
            }
        }
        val scheduler = net.meshsat.android.satellite.PassScheduler(
            passProvider = predictor,
            // Gate at wire-up so the 5-second poll tick doesn't invoke anything
            // when no Iridium modem is paired (MESHSAT-499).
            signalPoller = {
                if (iridium.state.value == IridiumSpp.State.Connected) {
                    iridium.pollSignal()
                }
            },
            burstFlusher = {
                // Same gate: nothing to flush to if the modem is absent. Through the delivery queue,
                // never straight to the modem (MESHSAT-1249).
                if (iridium.state.value == IridiumSpp.State.Connected) {
                    burstQueue?.flush()?.let { (payload, count) ->
                        if (payload != null && count > 0) {
                            scope.launch {
                                dispatcher?.enqueueDirect("iridium_0", payload, "Batch of $count messages", "burst:${System.currentTimeMillis()}")
                            }
                        }
                    }
                }
            },
            scope = scope,
        )
        scheduler.start()
        passScheduler = scheduler
        Log.i("MeshSat", "Pass scheduler initialized")
    }

    /** Handle inbound MQTT messages (MT sends, TAK events, config updates). */
    private fun handleMqttInbound(topic: String, payload: String) {
        scope.launch {
            try {
                when {
                    topic.endsWith("/reticulum/rx") -> {
                        // Inbound Reticulum packet from Hub (MESHSAT-354)
                        rnsMqttInterface?.processIncomingMessage(topic, payload)
                    }
                    topic == "meshsat/reticulum/routes" -> {
                        // Route hints from Hub — pre-populate path table (MESHSAT-354)
                        try {
                            val json = org.json.JSONObject(payload)
                            val routes = json.optJSONArray("routes")
                            if (routes != null) {
                                for (i in 0 until routes.length()) {
                                    val r = routes.getJSONObject(i)
                                    val destHex = r.optString("dest_hash", "")
                                    val hops = r.optInt("hops", 1)
                                    val cost = r.optInt("cost", 0)
                                    if (destHex.length == 32) {
                                        Log.d("MeshSat", "Route hint: $destHex via MQTT (hops=$hops, cost=$cost)")
                                    }
                                }
                                Log.i("MeshSat", "Received ${routes.length()} route hints from Hub")
                            }
                        } catch (e: Exception) {
                            Log.w("MeshSat", "Route hints parse failed: ${e.message}")
                        }
                    }
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
                            val msg = net.meshsat.android.rules.RouteMessage(
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
                        if (!net.meshsat.android.sms.SmsCapability.included) {
                            Log.i("MeshSat", "Hub asked for an SMS to $to: this edition has no SMS")
                        } else if (to.isNotBlank() && text.isNotBlank()) {
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
        if (!net.meshsat.android.sms.SmsCapability.included) {
            Log.i("MeshSat", "SMS relay not started: this edition has no SMS")
            return
        }
        net.meshsat.android.sms.SmsReceiver.relayCallback =
            net.meshsat.android.sms.SmsRelayCallback { sender, text, rawText, wasEncrypted, wasCompressed ->
                mqttTransport?.publishSmsInbound(
                    sender = sender,
                    text = text,
                    rawText = rawText,
                    wasEncrypted = wasEncrypted,
                    wasCompressed = wasCompressed,
                )
                // A message that arrives by SMS goes through the rules, as one that arrives by
                // mesh or by satellite does. Until MESHSAT-1261 it did not: an inbound SMS was
                // stored, possibly forwarded to one fixed number, handed to the MQTT client that
                // nothing brings online, and that was all. `evaluateAndForward` even had an SMS
                // branch that nothing ever called, so a rule on sms_0 could not fire, whatever
                // it said.
                scope.launch { evaluateAndForward(ForwardingRule.Transport.SMS, text, sender) }
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
        val tracker = net.meshsat.android.aprs.AprsMessageTracker(scope)
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
        val beacon = net.meshsat.android.aprs.AprsBeacon(scope)
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

        val client = net.meshsat.android.aprs.AprsIsClient(scope)
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
                    net.meshsat.android.aprs.AprsIsClient.State.Connected ->
                        interfaceManager?.setOnline("aprs_0")
                    net.meshsat.android.aprs.AprsIsClient.State.Disconnected ->
                        interfaceManager?.setOffline("aprs_0")
                    net.meshsat.android.aprs.AprsIsClient.State.Error ->
                        interfaceManager?.setError("aprs_0", "APRS-IS connection error")
                    net.meshsat.android.aprs.AprsIsClient.State.Connecting ->
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
                    ?: net.meshsat.android.reticulum.RnsTcpInterface.DEFAULT_PORT
                val useTls = settings.rnsTcpTls.first() || port == 443

                // Build mTLS SSLSocketFactory from Hub certs if available
                val sslFactory = if (useTls) {
                    val clientCert = settings.hubClientCertPem.first()
                    val clientKey = settings.hubClientKeyPem.first()
                    val caCert = settings.hubCaCertPem.first()
                    if (clientCert.isNotBlank() && clientKey.isNotBlank()) {
                        try {
                            net.meshsat.android.mqtt.CertificatePinner.createMtlsSSLSocketFactory(
                                clientCertPem = clientCert,
                                clientKeyPem = clientKey,
                                caCertPem = caCert.ifBlank { null },
                            ).also { Log.i("MeshSat", "RNS TCP: mTLS enabled with Hub certs") }
                        } catch (e: Exception) {
                            Log.w("MeshSat", "RNS TCP: mTLS cert setup failed, using default TLS: ${e.message}")
                            null
                        }
                    } else null
                } else null

                val tcp = net.meshsat.android.reticulum.RnsTcpInterface(scope)
                tcp.connect(host, port, tls = useTls, sslSocketFactory = sslFactory)
                rnsTcpInterface = tcp
                scope.launch {
                    tcp.state.collect { state ->
                        when (state) {
                            net.meshsat.android.reticulum.RnsTcpInterface.State.Connected ->
                                interfaceManager?.setOnline("tcp_rns_0")
                            net.meshsat.android.reticulum.RnsTcpInterface.State.Disconnected ->
                                interfaceManager?.setOffline("tcp_rns_0")
                            net.meshsat.android.reticulum.RnsTcpInterface.State.Error ->
                                interfaceManager?.setError("tcp_rns_0", tcp.error.value)
                            net.meshsat.android.reticulum.RnsTcpInterface.State.Connecting ->
                                interfaceManager?.setConnecting("tcp_rns_0")
                        }
                    }
                }
                Log.i("MeshSat", "RNS TCP interface initialized: $host:$port")

                // Multi-peer loading (MESHSAT-392) — peers managed via Settings UI
                // Additional peers connect independently when configured in the database.
                scope.launch {
                    val peers = db.rnsTcpPeerDao().getEnabled()
                    for (peer in peers) {
                        // Each additional peer gets its own RnsTcpInterface instance
                        val peerTcp = net.meshsat.android.reticulum.RnsTcpInterface(scope, "tcp_rns_${peer.id}")
                        // Wire receive callback immediately — transport node start()
                        // may not have run yet (async coroutine race).
                        peerTcp.setReceiveCallback(net.meshsat.android.reticulum.RnsReceiveCallback { ifaceId, raw ->
                            rnsTransportNode?.let { node ->
                                scope.launch { node.onPacketReceived(ifaceId, raw) }
                            }
                        })
                        peerTcp.connect(peer.host, peer.port)
                        registry.register("tcp_rns_${peer.id}", peerTcp)
                    }
                    if (peers.isNotEmpty()) Log.i("MeshSat", "Loaded ${peers.size} additional TCP peers")
                }
            } catch (e: Exception) {
                Log.w("MeshSat", "RNS TCP init failed (non-fatal): ${e.message}")
            }
        }
    }

    /**
     * Initialize the Hub relay client (MESHSAT-1157).
     *
     * The fallback below LAN and RNS TCP: a WebSocket tunnel through the Hub to one
     * kit, carrying Reticulum packets as bare frames. Rides on the Hub Reporter's
     * identity (bridge id and MQTT password) and needs only a target bridge id.
     */
    private fun initHubRelay() {
        scope.launch {
            try {
                if (!settings.hubEnabled.first() || !settings.hubRelayEnabled.first()) {
                    Log.d("MeshSat", "Hub relay disabled in settings")
                    return@launch
                }
                val target = settings.hubRelayTarget.first()
                if (target.isBlank()) {
                    Log.d("MeshSat", "Hub relay: no target bridge configured")
                    return@launch
                }
                val hubApiBase = net.meshsat.android.hub.relay.RelayTunnel.deriveHubApiBase(
                    settings.hubUrl.first(),
                    settings.hubRelayUrl.first(),
                )
                val ownId = settings.hubBridgeId.first().ifEmpty {
                    android.provider.Settings.Secure.getString(
                        contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID,
                    ) ?: "android-unknown"
                }
                val password = settings.hubPassword.first()
                if (hubApiBase.isBlank() || password.isBlank()) {
                    Log.w("MeshSat", "Hub relay: Hub URL or password not configured")
                    return@launch
                }
                if (target == ownId) {
                    Log.w("MeshSat", "Hub relay: target is this device, not started")
                    return@launch
                }

                val relay = net.meshsat.android.hub.relay.RelayBridgeTransport(
                    scope = scope,
                    config = net.meshsat.android.hub.relay.RelayBridgeTransport.Config(
                        hubApiBase = hubApiBase,
                        targetBridgeId = target,
                        ownBridgeId = ownId,
                        password = password,
                    ),
                )
                // Wire receive immediately: the transport node may not have started yet.
                relay.setReceiveCallback(net.meshsat.android.reticulum.RnsReceiveCallback { ifaceId, raw ->
                    rnsTransportNode?.let { node ->
                        scope.launch { node.onPacketReceived(ifaceId, raw) }
                    }
                })
                relay.start()
                hubRelayTransport = relay
                registry.register(relay.interfaceId, relay)
                scope.launch {
                    relay.state.collect { state ->
                        when (state) {
                            is net.meshsat.android.hub.relay.RelayTunnel.RelayState.Open ->
                                interfaceManager?.setOnline(relay.interfaceId)
                            is net.meshsat.android.hub.relay.RelayTunnel.RelayState.Connecting ->
                                interfaceManager?.setConnecting(relay.interfaceId)
                            is net.meshsat.android.hub.relay.RelayTunnel.RelayState.Refused ->
                                interfaceManager?.setError(relay.interfaceId, "hub refused: HTTP ${state.httpCode}")
                            is net.meshsat.android.hub.relay.RelayTunnel.RelayState.Closed ->
                                when (state.reason) {
                                    net.meshsat.android.hub.relay.RelayTunnel.CloseReason.Normal,
                                    net.meshsat.android.hub.relay.RelayTunnel.CloseReason.Local ->
                                        interfaceManager?.setOffline(relay.interfaceId)
                                    else ->
                                        interfaceManager?.setError(relay.interfaceId, "${state.reason}: ${state.detail}")
                                }
                        }
                    }
                }
                Log.i("MeshSat", "Hub relay initialized: $ownId -> $target via $hubApiBase")
            } catch (e: Exception) {
                Log.w("MeshSat", "Hub relay init failed (non-fatal): ${e.message}")
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
                // Check if transport node is enabled (MESHSAT-394)
                val transportEnabled = settings.rnsTransportEnabled.first()
                if (!transportEnabled) {
                    Log.i("MeshSat", "Reticulum transport node disabled by settings")
                    return@launch
                }
                val announceMin = settings.rnsAnnounceInterval.first().toIntOrNull() ?: 10
                val announceIntervalMs = announceMin * 60 * 1000L

                val kvStore = net.meshsat.android.crypto.SecureKeyStore.getInstance(this@GatewayService)
                val identity = net.meshsat.android.routing.Identity.loadOrGenerate(kvStore)
                routingIdentity = identity

                val announceHandler = net.meshsat.android.reticulum.RnsAnnounceHandler(
                    identity = identity,
                    scope = scope,
                )
                announceHandler.startPruner()

                val localDestHash = announceHandler.localDestHash

                val linkManager = net.meshsat.android.reticulum.RnsLinkManager(
                    identity = identity,
                    localDestHash = localDestHash,
                )

                // Build RnsInterface map from available transports
                val rnsInterfaces: () -> Map<String, net.meshsat.android.reticulum.RnsInterface> = {
                    val map = mutableMapOf<String, net.meshsat.android.reticulum.RnsInterface>()
                    meshtasticBle?.let { ble ->
                        map["mesh_rns_0"] = net.meshsat.android.reticulum.RnsMeshtasticBleInterface(ble, scope)
                    }
                    iridiumSpp?.let { spp ->
                        map["iridium_rns_0"] = net.meshsat.android.reticulum.RnsIridiumInterface(spp)
                    }
                    iridium9704Spp?.let { spp ->
                        map["iridium9704_rns_0"] = net.meshsat.android.reticulum.RnsIridium9704Interface(spp, scope)
                    }
                    rnsTcpInterface?.let { tcp ->
                        map["tcp_rns_0"] = tcp
                    }
                    // MQTT Reticulum interface (MESHSAT-354)
                    rnsMqttInterface?.let { map["mqtt_rns_0"] = it }
                    // Hub relay tunnel to one kit (MESHSAT-1157)
                    hubRelayTransport?.let { map[it.interfaceId] = it }
                    map
                }

                val pathTable = net.meshsat.android.reticulum.RnsPathTable(
                    interfaces = { rnsInterfaces().values.toList() },
                )

                val forwardingTable = net.meshsat.android.reticulum.RnsForwardingTable()

                val node = net.meshsat.android.reticulum.RnsTransportNode(
                    localDestHash = localDestHash,
                    announceHandler = announceHandler,
                    linkManager = linkManager,
                    pathTable = pathTable,
                    forwardingTable = forwardingTable,
                    interfaces = rnsInterfaces,
                    scope = scope,
                    announceIntervalMs = announceIntervalMs,
                )

                // Deliver locally-addressed Reticulum packets to the message pipeline
                node.localDeliveryCallback = net.meshsat.android.reticulum.RnsTransportNode.LocalDeliveryCallback { packet, sourceInterface ->
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

                // HeMB inbound: persistent reassembly buffer for cross-bearer decode
                val hembReassembly = net.meshsat.android.hemb.HembReassemblyBuffer(deliverFn = { payload ->
                    Log.i("MeshSat", "hemb: DECODED payload ${payload.size}B")
                })
                node.hembCallback = net.meshsat.android.reticulum.RnsTransportNode.HembFrameCallback { sourceInterface, frameData ->
                    Log.i("MeshSat", "hemb: received HeMB frame via $sourceInterface (${frameData.size}B)")
                    hembReassembly.addFrame(frameData)
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
            alwaysOnline = true, // Android sends texts whenever the phone has a signal; disabled below where it cannot
        ))
        // The Hub link the phone actually uses is HubReporter, and until MESHSAT-1261 nothing
        // showed it here: the list said the Hub was offline while messages were flowing over it,
        // and a rule aimed at the Hub waited for mqtt_0, which nothing ever brings online.
        // HubReporter reconnects itself, so the manager only mirrors its state.
        mgr.register(InterfaceConfig(
            id = "hub_0", channelType = "hub",
            autoReconnect = false,
        ))
        // The older per-device MQTT client, off unless someone turns it on in Settings.
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
        // Hub relay tunnel (MESHSAT-1157): the transport reconnects by itself, so the
        // manager only mirrors its state and never schedules a reconnect of its own.
        mgr.register(InterfaceConfig(
            id = net.meshsat.android.hub.relay.RelayBridgeTransport.INTERFACE_ID, channelType = "tcp",
            autoReconnect = false,
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
                    // The 9603 arrives with the MeshSat node's BLE link; this only allows taking it.
                    iridiumWanted.value = true
                    // A modem still connected is simply online again: its state flow will not
                    // emit Connected a second time, so nothing else would restart the worker.
                    if (iridiumSpp?.state?.value == IridiumSpp.State.Connected) mgr.setOnline("iridium_0")
                    null
                }
                interfaceId == "iridium9704_0" -> {
                    val spp = iridium9704Spp ?: return@setConnectCallback "9704 transport not available"
                    spp.reconnect()
                    null
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
                        ?: net.meshsat.android.reticulum.RnsTcpInterface.DEFAULT_PORT
                    val useTls = settings.rnsTcpTls.first() || port == 443
                    val sslFactory = if (useTls) {
                        val cc = settings.hubClientCertPem.first()
                        val ck = settings.hubClientKeyPem.first()
                        val ca = settings.hubCaCertPem.first()
                        if (cc.isNotBlank() && ck.isNotBlank()) try {
                            net.meshsat.android.mqtt.CertificatePinner.createMtlsSSLSocketFactory(cc, ck, ca.ifBlank { null })
                        } catch (_: Exception) { null } else null
                    } else null
                    val tcp = rnsTcpInterface
                        ?: net.meshsat.android.reticulum.RnsTcpInterface(scope).also { rnsTcpInterface = it }
                    tcp.connect(host, port, tls = useTls, sslSocketFactory = sslFactory)
                    null
                }
                else -> null
            }
        }

        // Disconnect callback
        mgr.setDisconnectCallback { interfaceId ->
            when {
                interfaceId.startsWith("mesh") -> meshtasticBle?.disconnect()
                interfaceId == "iridium_0" -> { iridiumWanted.value = false }
                interfaceId == "iridium9704_0" -> iridium9704Spp?.disconnect()
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

        // hub_0's state is wired in initHubReporter, where the client it follows is made.

        // Interfaces this phone has no hardware or configuration for are marked Disabled rather
        // than left sitting at Offline: an Offline interface reads as something that is meant to
        // be working and is not, it drags the health figure down, and the Dispatcher holds
        // anything routed to it for ever (MESHSAT-1261).
        scope.launch {
            if (!settings.mqttEnabled.first()) mgr.disable("mqtt_0")
            if (!settings.aprsEnabled.first()) mgr.disable("aprs_0")
            if (settings.iridium9704BtAddress.first().isBlank()) mgr.disable("iridium9704_0")
            if (settings.hubRelayTarget.first().isBlank()) {
                mgr.disable(net.meshsat.android.hub.relay.RelayBridgeTransport.INTERFACE_ID)
            }
            if (!settings.hubEnabled.first()) mgr.disable("hub_0")
            // No telephony, or the Google Play edition, which has no SMS at all (MESHSAT-1335).
            // Until then sms_0 sat Online on every phone, tablets included, and /api/health
            // counted a link that could never carry anything.
            if (!net.meshsat.android.sms.SmsCapability.canSend(this@GatewayService)) mgr.disable("sms_0")
        }

        // Observe BLE errors → drive InterfaceManager
        meshtasticBle?.let { ble ->
            scope.launch {
                ble.error.collect { err ->
                    if (err.isNotBlank()) mgr.setError("mesh_0", err)
                }
            }
        }
        // The modem's errors never change the interface state: its link state comes from
        // spp.state above, and most of these are refusals (an SBDIX held after a failed
        // session), not link failures. As ERROR they stopped the worker and held the queue.
        iridiumSpp?.let { spp ->
            scope.launch {
                spp.error.collect { err ->
                    if (err.isNotBlank()) mgr.noteError("iridium_0", err)
                }
            }
        }

        // Observe 9704 SPP state → drive InterfaceManager
        iridium9704Spp?.let { spp ->
            scope.launch {
                spp.state.collect { state ->
                    when (state) {
                        net.meshsat.android.bt.Iridium9704Spp.State.Ready -> mgr.setOnline("iridium9704_0")
                        net.meshsat.android.bt.Iridium9704Spp.State.Disconnected -> mgr.setOffline("iridium9704_0")
                        net.meshsat.android.bt.Iridium9704Spp.State.Connecting,
                        net.meshsat.android.bt.Iridium9704Spp.State.Connected,
                        net.meshsat.android.bt.Iridium9704Spp.State.Initializing -> mgr.setConnecting("iridium9704_0")
                    }
                }
            }
            scope.launch {
                spp.error.collect { err ->
                    if (err.isNotBlank()) mgr.setError("iridium9704_0", err)
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
                            iridium9704Spp?.state?.value == net.meshsat.android.bt.Iridium9704Spp.State.Ready
                        interfaceId.startsWith("sms") -> net.meshsat.android.sms.SmsCapability.canSend(this@GatewayService)
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
                val callback = Dispatcher.DeliveryCallback { interfaceId, payload, textPreview, recipient, deliveryId, sourceBearer ->
                    deliverToTransport(interfaceId, payload, textPreview, recipient, deliveryId, sourceBearer)
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
                    "sms_0" to "sms",
                    "mqtt_0" to "mqtt",
                    "aprs_0" to "aprs",
                    // The Hub, so a rule can forward to it and have it delivered (MESHSAT-1261).
                    "hub_0" to "hub",
                )
                // Iridium sends (MESHSAT-1243): mark the chat message sent, or record a
                // rule-forwarded one; retries wait for the next pass window when one is known.
                disp.mayDeliver = { del -> sosController?.mayDeliver(del) ?: true }
                // The audit log had no writer at all (MESHSAT-1249); signing starts after this, so
                // the service is read when an event happens.
                disp.onAudit = { event, iface, deliveryId, ruleId, detail ->
                    signingService?.auditEvent(event, iface, "egress", deliveryId, ruleId, detail)
                }
                disp.onSent = { del ->
                    sosController?.onSent(del)
                    if (del.channel == "iridium_0") {
                        val msgId = del.msgRef.removePrefix("msg:").toLongOrNull()
                        if (del.msgRef.startsWith("msg:") && msgId != null) {
                            db.messageDao().setForwardedToUnlessDelivered(msgId, "iridium:sbd")
                        } else {
                            db.messageDao().insert(
                                Message(
                                    transport = "iridium", direction = "tx", sender = "self",
                                    recipient = net.meshsat.android.ui.Peers.SATELLITE,
                                    text = del.textPreview, forwarded = true, forwardedTo = "iridium:sbd",
                                )
                            )
                        }
                    }
                }
                // A satellite send that reported a failure after the upload may have arrived: the chat
                // shows "May have been sent" until a retry is confirmed (MESHSAT-1243).
                disp.onUnconfirmed = { del, _ ->
                    if (del.channel == "iridium_0" && del.msgRef.startsWith("msg:")) {
                        del.msgRef.removePrefix("msg:").toLongOrNull()?.let { db.messageDao().setForwardedTo(it, IRIDIUM_UNCONFIRMED) }
                    }
                }
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
    /**
     * The Hub's name for the link a message arrived on (MESHSAT-1274). The Hub stores this
     * verbatim in `messages.channel` and its routing engine reads it as the source of the
     * message, so the vocabulary is the Hub's, not this app's interface ids. An empty bearer
     * means the message was written here and did not arrive on anything.
     *
     * The satellite values were held back for a release while it looked as though they would
     * newly fire five unrestricted Hub routes, one of them transmitting on APRS. They do not:
     * a RockBLOCK's own traffic already reaches the Hub through the Ground Control webhook,
     * which sets `channel: iridium` itself, so those routes have been firing all along, and
     * the APRS destination is off in the Hub's config (MESHSAT-1275).
     */
    private fun hubChannelOf(sourceBearer: String): String = when {
        sourceBearer.startsWith("sms") -> "sms"
        sourceBearer.startsWith("mesh") -> "mesh"
        sourceBearer.startsWith("iridium9704") -> "iridium_imt"
        sourceBearer.startsWith("iridium") -> "iridium"
        sourceBearer.startsWith("aprs") -> "aprs"
        else -> "mqtt"
    }

    private suspend fun deliverToTransport(interfaceId: String, payload: ByteArray, textPreview: String, recipient: String = "", deliveryId: Long = 0, sourceBearer: String = ""): String? {
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
                            recipient = net.meshsat.android.ui.Peers.MESH_ALL,
                            text = textPreview, forwarded = true, forwardedTo = "mesh:broadcast",
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    null // success
                }
                interfaceId == "hub_0" -> {
                    val hub = hubReporter
                        ?: return "the Hub is not set up"
                    if (hub.state.value != HubReporter.State.Connected) return "not connected to the Hub"
                    // The id names who the message came from; bridge_id names this phone, which
                    // carried it. Until v2.18.10 every forward went out under the modem's IMEI, so
                    // a text from a phone number was filed as the RockBLOCK's (MESHSAT-1274).
                    val bridgeId = settings.hubBridgeId.first()
                    val origin = if (deliveryId != 0L) db.messageDeliveryDao().getById(deliveryId)?.origin.orEmpty() else ""
                    val deviceId = HubOrigin.deviceIdFor(
                        sourceBearer = sourceBearer,
                        origin = origin,
                        modemImei = iridiumSpp?.modemInfo?.value?.imei.orEmpty(),
                        bridgeId = bridgeId,
                    )
                    if (deviceId.isBlank()) return "no device id for the Hub"
                    val text = if (payload.isNotEmpty()) String(payload, Charsets.UTF_8) else textPreview
                    // The bearer it arrived on, not the one it leaves by: the Hub stores this
                    // verbatim and its routing engine reads it as the source of the message, so
                    // "mqtt" on a forwarded SMS both lost the provenance and stopped any Hub
                    // route scoped to sms from firing (MESHSAT-1274).
                    val messageId = if (deliveryId != 0L && bridgeId.isNotBlank()) "$bridgeId-d$deliveryId" else ""
                    if (!hub.publishMessage(deviceId, text, recipient, channel = hubChannelOf(sourceBearer), messageId = messageId)) {
                        return "the Hub did not take the message"
                    }
                    db.messageDao().insert(
                        Message(
                            transport = "hub", direction = "tx", sender = "self",
                            recipient = recipient.ifBlank { "hub" },
                            text = textPreview, forwarded = true, forwardedTo = "hub:mo/decoded",
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
                    // The modem's pause after a session found no network is not this message's
                    // failure: it waits without using up a try (MESHSAT-1243).
                    val hold = spp.sbdixHoldRemainingMs()
                    if (hold > 0) return "${Dispatcher.NOT_NOW}$hold the satellite modem pauses after a session found no network"
                    val data = if (payload.isNotEmpty()) payload else textPreview.toByteArray()
                    // One message, one frame: nothing is cut into parts any more (MESHSAT-1280).
                    if (!SatelliteLimits.fits(data.size)) return "${Dispatcher.NEVER} ${SatelliteLimits.tooLong(data.size)}"
                    val chunks = listOf(data)
                    var momsn = -1
                    for ((i, chunk) in chunks.withIndex()) {
                        val part = if (chunks.size > 1) " (part ${i + 1} of ${chunks.size})" else ""
                        val written = spp.writeMoBuffer(chunk)
                        if (!written) return "Could not hand the message to the modem$part"
                        val result = spp.sbdix() ?: return "The modem gave no readable answer$part"
                        if (!result.moSuccess) {
                            val why = "status ${result.moStatus}, ${IridiumSpp.moStatusText(result.moStatus)}, MOMSN ${result.moMsn}$part"
                            // The upload may have reached the gateway before the link was cut: say so,
                            // and retry all the same, so a message is never lost (a duplicate costs a credit).
                            return if (result.moStatus in IridiumSpp.MO_MAYBE_SENT) "${Dispatcher.UNCONFIRMED} $why" else "Not sent: $why"
                        }
                        momsn = result.moMsn
                    }
                    // The session's MOMSN, for the Hub's receipt (MESHSAT-1246). One part only: a
                    // message in fragments would need every part confirmed, so it keeps one tick.
                    val imei = spp.modemInfo.value.imei
                    if (chunks.size == 1 && deliveryId > 0 && imei.isNotBlank() && momsn >= 0) {
                        db.messageDeliveryDao().setSatRef(deliveryId, "$imei:$momsn")
                    }
                    null // success: Dispatcher.onSent records it
                }
                interfaceId == "iridium9704_0" -> {
                    val spp = iridium9704Spp
                        ?: return "9704 not available"
                    if (spp.state.value != net.meshsat.android.bt.Iridium9704Spp.State.Ready)
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
                interfaceId.startsWith("sms") -> {
                    // A delivery that names its recipient (an SOS emergency contact) goes there;
                    // anything else to the kit's number, as before (MESHSAT-1249).
                    val phone = recipient.ifBlank { settings.meshsatPiPhone.first() }
                    if (phone.isBlank()) return "no SMS destination configured"
                    // Wait for the phone to say the SMS left, so "no service" is retried, not lost.
                    SmsSender.sendAndWait(
                        context = this, to = phone, text = textPreview,
                        // The carrier's delivery report marks the delivery acknowledged (MESHSAT-1246).
                        deliveryIntent = if (deliveryId > 0) net.meshsat.android.sms.SmsStatusReceiver.deliveredIntent(this, deliveryId = deliveryId) else null,
                    )?.let { return it }
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
        // A coarse cell fix must not replace a recent GPS fix.
        if (!net.meshsat.android.location.LocationFixes.isBetter(location, _phoneLocation.value)) return@LocationListener
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
                val pos = net.meshsat.android.hub.DevicePosition(
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

        // The platform's fused provider (Android 12+) combines whatever the phone has.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                lm.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER, 60_000L, 50f, locationListener
                )
            } catch (_: Exception) {}
        }

        // Seed with the freshest last-known fix of any provider
        val last = net.meshsat.android.location.LocationFixes.freshest(lm)
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

    private val iridiumStatusIcons = intArrayOf(
        R.drawable.ic_stat_iridium_0,
        R.drawable.ic_stat_iridium_1,
        R.drawable.ic_stat_iridium_2,
        R.drawable.ic_stat_iridium_3,
        R.drawable.ic_stat_iridium_4,
        R.drawable.ic_stat_iridium_5,
    )

    /**
     * A satellite icon with the Iridium signal bars on the left of the status bar while the
     * 9603 is connected, gone when it is not (MESHSAT-1241). Android keeps the right-hand
     * system icons for itself; an ongoing notification is how an app shows one.
     */
    private fun observeIridiumStatusIcon() {
        val spp = iridiumSpp ?: return
        scope.launch {
            combine(spp.state, spp.signal) { state, signal -> state to signal }.collect { (state, signal) ->
                val nm = NotificationManagerCompat.from(this@GatewayService)
                if (state != IridiumSpp.State.Connected) {
                    nm.cancel(IRIDIUM_STATUS_NOTIFICATION_ID)
                    return@collect
                }
                if (ContextCompat.checkSelfPermission(this@GatewayService, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) return@collect
                val bars = signal.coerceIn(0, 5)
                val imei = spp.modemInfo.value.imei
                val tap = PendingIntent.getActivity(
                    this@GatewayService, 1,
                    Intent(this@GatewayService, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = NotificationCompat.Builder(this@GatewayService, MeshSatApp.CHANNEL_IRIDIUM_SIGNAL)
                    .setSmallIcon(iridiumStatusIcons[bars])
                    .setContentTitle("Iridium signal $bars/5")
                    .setContentText(if (imei.isNotBlank()) "RockBLOCK ${imei.takeLast(6)} via the MeshSat node" else "Iridium modem connected")
                    .setOngoing(true)
                    // Not setSilent: a silent notification's icon is hidden from the status bar
                    // on Pixels by default. The channel has no sound, and updates never alert.
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(tap)
                    .build()
                try {
                    nm.notify(IRIDIUM_STATUS_NOTIFICATION_ID, notification)
                } catch (_: SecurityException) {}
            }
        }
    }

    /** Lets InterfaceManager release the node's modem without changing the saved setting. */
    private val iridiumWanted = MutableStateFlow(true)

    /**
     * The RockBLOCK 9603 is reached through the MeshSat node's BLE pipe (MESHSAT-1236). While a
     * node offers it, the setting allows it and the interface is up, the phone subscribes,
     * which asks the node for the modem, and runs the 9603 driver whenever STATUS says the
     * phone owns it. Otherwise it lets go, so the node's own logic can use the modem.
     */
    /**
     * Reconnect to the MeshSat node chosen last (MESHSAT-1239): at service start here, and
     * after any drop through mesh_0's auto-reconnect, which uses the same address. Only the
     * user's Disconnect forgets it.
     */
    private var nodeBatteryStoredMs = 0L

    /**
     * The phone's own node reported its battery: keep a reading a minute, per node, and work out
     * how long it has left from how fast it has actually been falling (MESHSAT-1315).
     */
    private fun onOwnNodeBattery(nodeNum: Long, level: Int, voltage: Float) {
        scope.launch {
            val source = "node_battery:%08x".format(nodeNum)
            val nowMs = System.currentTimeMillis()
            if (nowMs - nodeBatteryStoredMs >= NODE_BATTERY_SAMPLE_MS) {
                nodeBatteryStoredMs = nowMs
                db.signalDao().insert(SignalRecord(source = source, value = level))
                Log.i("MeshSat", "Node battery: level $level, ${"%.2f".format(java.util.Locale.ROOT, voltage)} V")
            }
            val readings = db.signalDao().getSince(source, nowMs - net.meshsat.android.ble.NodeBattery.WINDOW_MS).first()
                .map { net.meshsat.android.ble.NodeBattery.Reading(it.timestamp, it.value) }
            _nodeBattery.value = NodeBatteryNow(
                nodeNum, level, voltage,
                net.meshsat.android.ble.NodeBattery.hoursLeft(readings, nowMs), nowMs,
            )
        }
    }

    private fun reconnectSavedNode() {
        val ble = meshtasticBle ?: return
        scope.launch {
            val addr = settings.meshtasticBleAddress.first()
            if (addr.isBlank()) return@launch
            ble.rememberNode(addr)
            if (ble.state.value != MeshtasticBle.State.Disconnected) return@launch
            Log.i("MeshSat", "Reconnecting to the MeshSat node $addr")
            try {
                ble.connect(addr)
            } catch (e: SecurityException) {
                Log.w("MeshSat", "Cannot reconnect to the node without the Bluetooth permission: ${e.message}")
            }
        }
    }

    private fun observeIridiumPipe() {
        val ble = meshtasticBle ?: return
        val spp = iridiumSpp ?: return
        // A pipe that stops taking writes never mended itself: the claim stayed answered, so
        // nothing here re-claimed, and only restarting the app brought the modem back after
        // thirteen minutes (MESHSAT-1270). Drop the link and let the node reconnect, which
        // re-runs the claim and the attach below.
        scope.launch {
            var lastRecoveryMs = 0L
            spp.linkFaults.collect {
                interfaceManager?.noteError("iridium_0", "The phone cannot reach the node's modem")
                spp.detach()
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastRecoveryMs < PIPE_RECOVERY_COOLDOWN_MS) {
                    Log.w("MeshSat", "Iridium: the pipe takes no writes; waiting out the reconnect cooldown")
                    return@collect
                }
                lastRecoveryMs = nowMs
                Log.w("MeshSat", "Iridium: the pipe takes no writes; reconnecting to the node")
                // Not reconnect(): that is a no-op while Android still holds a client to the
                // node, which is the very case this recovery exists for.
                ble.forceReconnect()
            }
        }
        scope.launch {
            combine(ble.iridiumPipe, settings.iridiumNodePipeEnabled, iridiumWanted) { pipe, enabled, wanted ->
                Triple(pipe, enabled, wanted)
            }.collectLatest { (pipe, enabled, wanted) ->
                spp.detach()
                if (pipe == null) return@collectLatest
                if (!enabled || !wanted) {
                    pipe.release()
                    return@collectLatest
                }
                coroutineScope {
                    // Keep at it until the phone holds the modem (MESHSAT-1239): a claim can
                    // race the link's encryption or the node's config dump and go unanswered,
                    // and a node busy with its own modem hands it over later. STATUS is read
                    // again in case its notification was lost.
                    launch {
                        var attempt = 0
                        while (true) {
                            when (pipe.owner.value) {
                                IridiumPipeContract.Owner.Phone -> {}
                                IridiumPipeContract.Owner.Node -> pipe.refreshStatus()
                                else -> if (!pipe.claim()) {
                                    Log.i("MeshSat", "Iridium: no handover from the node yet (owner ${pipe.owner.value}); asking again")
                                }
                            }
                            // The first claim right after connecting is refused within ms while the
                            // link comes up (seen on every reconnect), so the second comes soon.
                            delay(if (attempt++ == 0) PIPE_CLAIM_FIRST_RETRY_MS else PIPE_CLAIM_RETRY_MS)
                        }
                    }
                    pipe.owner.collect { owner ->
                        if (owner == IridiumPipeContract.Owner.Phone) {
                            if (spp.state.value == IridiumSpp.State.Disconnected) spp.attach(pipe.asModemLink())
                        } else {
                            spp.detach()
                        }
                    }
                }
            }
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
                        // 101 is Meshtastic's "on external power", kept so it can be shown as such.
                        if (telemetry.batteryLevel in 0..net.meshsat.android.ble.NodeBattery.EXTERNAL_POWER) {
                            ble.updateNodeBattery(telemetry.from, telemetry.batteryLevel)
                            if (telemetry.from == ble.myInfo.value?.myNodeNum) {
                                onOwnNodeBattery(telemetry.from, telemetry.batteryLevel, telemetry.voltage)
                            }
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

        // Listen for Iridium MT (mobile-terminated) messages: once the modem is up, and on every
        // ring alert. Never on a timer, because every SBDIX is billed (MESHSAT-1236).
        iridiumSpp?.let { spp ->
            scope.launch {
                spp.state.collect { state ->
                    if (state == IridiumSpp.State.Connected) pollIridiumMt(ringAlert = false)
                }
            }
            scope.launch {
                spp.ringAlerts.collect { pollIridiumMt(ringAlert = true) }
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
            // The modem sees a satellite: send what waits now instead of at its next retry, as the
            // Bridge drains its queue on a signal of at least one bar (MESHSAT-1249). Not during the
            // 3 minutes after a session that found no network: the modem gets them to reacquire.
            scope.launch {
                spp.signalReadings.collect { bars ->
                    if (bars >= IRIDIUM_MIN_SIGNAL_BARS && spp.sbdixHoldRemainingMs() == 0L) {
                        dispatcher?.drainNow("iridium_0", "the modem sees a satellite ($bars/5)")
                    }
                }
            }
        }
        iridium9704Spp?.let { spp ->
            scope.launch { spp.error.collect { err -> Log.w("MeshSat", "9704: $err") } }
        }
    }

    /**
     * Fetch MT traffic. A message already in the modem's MT buffer is read for free; only a
     * ring alert, or a gateway that reported messages waiting, is worth a billed SBDIX.
     */
    private suspend fun pollIridiumMt(ringAlert: Boolean) {
        val spp = iridiumSpp ?: return
        val status = spp.sbdStatus() ?: return

        if (status.mtFlag) receiveIridiumMt(spp)
        if (ringAlert || status.raFlag || status.msgWaiting > 0) {
            // The session's message, if any, is stored by the modem's mtSink. +SBDIXA when it
            // answers a ring alert, as the ISU AT Command Reference asks.
            spp.sbdix(answeringRing = ringAlert || status.raFlag)
        }
    }

    /** Read the MT buffer, then store and forward the message. */
    private suspend fun receiveIridiumMt(spp: IridiumSpp) {
        val mtText = spp.readMtBuffer() ?: return
        storeIridiumMt(spp, mtText)
        // Drop it from the modem now that it is in the database. The MT flag stays raised
        // while the message sits there, so the next poll would read and store it again, and
        // the dedup that hides this lives in memory and does not survive a restart
        // (MESHSAT-1266).
        spp.clearMtBuffer()
    }

    private fun startMailboxCheck(): Boolean {
        synchronized(_mailbox) {
            if (_mailbox.value.running) return false
            _mailbox.value = MailboxCheck(running = true)
        }
        scope.launch {
            val spp = iridiumSpp
            val result = spp?.checkMailbox { bytes -> storeIridiumMt(spp, String(bytes, Charsets.UTF_8)) }
                ?: IridiumSpp.MailboxResult.NotConnected
            Log.i("MeshSat", "Iridium mailbox check: $result")
            _mailbox.value = MailboxCheck(running = false, result = result, finishedAt = System.currentTimeMillis())
        }
        return true
    }

    /** Store an MT message and hand it to the routing rules. */
    private suspend fun storeIridiumMt(spp: IridiumSpp, mtText: String) {
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

        Log.i("MeshSat", "Iridium MT stored: ${mtText.length} chars")
        postMessageNotification("Iridium: $imei", mtText)
        interfaceManager?.recordActivity("iridium_0")
        evaluateAndForward(ForwardingRule.Transport.IRIDIUM, mtText, imei)
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
        if (!net.meshsat.android.sms.SmsCapability.included) return
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

        val outText = OutgoingText.onMesh(text)
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

        // One message, one frame: nothing is cut into parts any more (MESHSAT-1280).
        if (!SatelliteLimits.fits(data.size)) {
            Log.w("MeshSat", "Iridium: ${data.size} bytes is too long for one satellite message; not sent")
            postMessageNotification("Not sent by satellite", SatelliteLimits.tooLong(data.size))
            return
        }
        val chunks = listOf(data)

        for ((i, chunk) in chunks.withIndex()) {
            val written = spp.writeMoBuffer(chunk)
            if (!written) {
                Log.w("MeshSat", "Iridium MO buffer write failed for fragment $i/${chunks.size}")
                postMessageNotification("Iridium send failed", "The modem did not take the message.")
                return
            }
            val result = spp.sbdix()
            if (result?.moSuccess != true) {
                Log.w("MeshSat", "SBDIX failed for fragment $i/${chunks.size}: mo_status=${result?.moStatus}")
                val why = when (result?.moStatus) {
                    null -> if (spp.sbdixHoldRemainingMs() > 0) "held after a failed session, try again in ${spp.sbdixHoldRemainingMs() / 1000} s" else "no answer from the modem"
                    32 -> "no network (status 32)"
                    else -> "status ${result.moStatus}"
                }
                postMessageNotification("Iridium send failed", why)
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
    }

    /** Send a text message to mesh (called from UI compose bar). */
    fun sendMeshMessage(text: String, to: Long = 0xFFFFFFFFL, channel: Int = 0) {
        val ble = meshtasticBle ?: return
        if (ble.state.value != MeshtasticBle.State.Connected) return

        scope.launch {
            val outText = OutgoingText.onMesh(text)
            val proto = MeshtasticProtocol.encodeTextMessage(outText, to, channel)
            // Writing to a node we have no name for: ask it, so the conversation gets one.
            if (to != 0xFFFFFFFFL) ble.askWhoIs(to)
            ble.sendToRadio(proto)

            db.messageDao().insert(
                Message(
                    transport = "mesh",
                    direction = "tx",
                    sender = "self",
                    // Filed under the node it went to, or under everyone on the mesh.
                    recipient = if (to == 0xFFFFFFFFL) net.meshsat.android.ui.Peers.MESH_ALL else MeshtasticProtocol.formatNodeId(to),
                    text = text,
                    timestamp = System.currentTimeMillis(),
                )
            )

            // Emit chat CoT to ATAK + Hub (MESHSAT-191)
            takIntegration?.sendChat(text)
        }
    }

    /** Send SBD message via Iridium (called from UI). */
    /**
     * A message the user wrote for Iridium: shown in the chat at once as queued, stored in
     * the delivery queue and retried until it goes out, whether or not the modem is there
     * right now (MESHSAT-1243). A failed session never drops it.
     */
    fun queueIridiumMessage(text: String, recipient: String) {
        scope.launch {
            val msgId = db.messageDao().insert(
                Message(
                    transport = "iridium", direction = "tx", sender = "self", recipient = recipient,
                    text = text, forwarded = true, forwardedTo = IRIDIUM_QUEUED,
                )
            )
            val payload = encodeIridiumPayload(text)
            val queued = dispatcher?.enqueueDirect("iridium_0", payload, text, msgRef = "msg:$msgId")
            if (queued == null) {
                db.messageDao().setForwardedTo(msgId, "iridium:failed")
                postMessageNotification("Iridium message not queued", "The delivery queue is not running.")
            }
        }
    }

    /** What goes into the MO buffer for [text]: MSVQ-SC compressed when enabled. */
    private suspend fun encodeIridiumPayload(text: String): ByteArray {
        val compressMode = settings.compressIridium.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3
        if (compressMode == "msvqsc" && msvqscEncoder != null) {
            val wire = msvqscEncoder!!.encode(text, stages)
            if (wire != null) return ProtocolVersion.prependVersionByte(wire)
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    fun sendIridiumMessage(text: String) {
        scope.launch {
            forwardToIridium(text)
        }
    }

    /** Send SMS message to a specific recipient (called from conversation compose). */
    private suspend fun sendSmsMessage(text: String, recipient: String) {
        if (!net.meshsat.android.sms.SmsCapability.included) {
            Log.i("MeshSat", "SMS to $recipient not sent: this edition has no SMS")
            return
        }
        // Key resolution: per-recipient → Hub wildcard (sms:*) → global key [MESHSAT-447]
        val convKeyRepo = net.meshsat.android.data.ConversationKeyRepository(db.conversationKeyDao(), net.meshsat.android.crypto.SecureKeyStore.getInstance(this))
        val convKey = convKeyRepo.getBySender(recipient)?.hexKey
        val wildcardKey = convKeyRepo.getBySender("*")?.hexKey
        val globalKey = settings.encryptionKey.first()
        val encEnabled = settings.encryptionEnabled.first()

        val keyToUse = convKey?.ifEmpty { null }
            ?: wildcardKey?.ifEmpty { null }
            ?: if (encEnabled) globalKey.ifEmpty { null } else null

        val compressMode = settings.compressSms.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3

        // The row first, so the phone's "sent" and the carrier's delivery report can find it: the
        // chat shows a clock, one tick once it left the phone, two once delivered (MESHSAT-1246).
        val messageId = db.messageDao().insert(
            Message(
                transport = "sms",
                direction = "tx",
                sender = "self",
                recipient = recipient,
                text = text,
                rawText = "",
                encrypted = keyToUse != null,
                timestamp = System.currentTimeMillis(),
                forwardedTo = net.meshsat.android.sms.SmsStatusReceiver.SENDING,
            )
        )

        try {
            SmsSender.send(
                context = this,
                to = recipient,
                text = text,
                encryptionKey = keyToUse,
                smaz2 = compressMode == "smaz2" || (keyToUse != null && compressMode != "msvqsc"),
                msvqscEncoder = if (compressMode == "msvqsc") msvqscEncoder else null,
                msvqscStages = stages,
                sentIntent = net.meshsat.android.sms.SmsStatusReceiver.sentIntent(this, messageId),
                deliveryIntent = net.meshsat.android.sms.SmsStatusReceiver.deliveredIntent(this, messageId = messageId),
            )
        } catch (e: Exception) {
            Log.w("MeshSat", "SMS to $recipient failed: ${e.message}")
            db.messageDao().setForwardedTo(messageId, net.meshsat.android.sms.SmsStatusReceiver.FAILED)
        }
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

    /**
     * The Hub has the satellite message sent in session [momsn] (MESHSAT-1246): its delivery is
     * acknowledged, and a chat message gets its second tick.
     */
    private suspend fun onHubReceipt(imei: String, momsn: Int) {
        val ref = "$imei:$momsn"
        val dao = db.messageDeliveryDao()
        val rows = dao.getBySatRef(ref)
        if (rows.isEmpty()) return
        dao.markAckedBySatRef(ref)
        for (del in rows) {
            val msgId = del.msgRef.removePrefix("msg:").toLongOrNull()
            if (del.msgRef.startsWith("msg:") && msgId != null) db.messageDao().setForwardedTo(msgId, IRIDIUM_DELIVERED)
        }
        Log.i("MeshSat", "The Hub has MOMSN $momsn: ${rows.size} delivery(ies) confirmed")
    }

    // --- SOS (MESHSAT-1249) ---

    private fun initSos() {
        val service = this
        val controller = net.meshsat.android.sos.SosController(
            context = this, scope = scope, db = db, settings = settings,
            env = object : net.meshsat.android.sos.SosController.Env {
                override val dispatcher: Dispatcher? get() = service.dispatcher
                override val hubReporter: net.meshsat.android.hub.HubReporter? get() = GatewayService.hubReporter
                override suspend fun modemImei(): String =
                    iridiumSpp?.modemInfo?.value?.imei.orEmpty().ifBlank { settings.lastModemImei.first() }
                override suspend fun meshPaired(): Boolean = settings.meshtasticBleAddress.first().isNotBlank()
                override fun location(): Location? = getLastKnownLocation()
                override fun tak(lat: Double, lon: Double, alt: Double, reason: String) {
                    takIntegration?.sendSOS(lat = lat, lon = lon, alt = alt, reason = reason)
                }
                override suspend fun audit(event: String, detail: String) {
                    signingService?.auditEvent(eventType = event, detail = detail)
                }
            },
        )
        sosController = controller
        controller.restore()
        // An SOS uses the satellite whenever this phone has had a modem, connected right now or not.
        scope.launch {
            iridiumSpp?.modemInfo?.collect { info ->
                if (info.imei.isNotBlank() && info.imei != settings.lastModemImei.first()) settings.setLastModemImei(info.imei)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return net.meshsat.android.location.LocationFixes.freshest(lm)
    }

    /** Tapping the gateway notification opens the app; it did nothing before (MESHSAT-1249). */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, MeshSatApp.CHANNEL_GATEWAY)
            .setContentTitle("MeshSat Gateway")
            .setContentText("Listening for mesh, satellite, and SMS messages")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34): each foreground service type requires its matching
                // runtime permission. Build the bitmask from actually-granted permissions.
                // remoteMessaging is always safe (normal permission, auto-granted).
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING

                val hasBluetooth = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (hasBluetooth) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }

                val hasLocation = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                if (hasLocation) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }

                startForeground(1, notification, types)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-13: types declared in manifest are sufficient, no runtime check needed.
                startForeground(
                    1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.w("MeshSat", "startForeground with type failed, retrying: ${e.message}")
            try {
                // Fallback without location, which Android refuses to a service started at boot
                // before the app has been opened; Bluetooth stays, so the node still connects
                // (MESHSAT-1249). remoteMessaging alone is always allowed on API 34+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val bt = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                        (if (bt) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
                    try {
                        startForeground(1, notification, types)
                    } catch (e3: Exception) {
                        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        if (net.meshsat.android.sms.SmsCapability.canSend(this)) statusParts.add("SMS")

        val text = when {
            statusParts.isEmpty() -> "Waiting for a link"
            statusParts == listOf("SMS") -> "SMS only"
            else -> statusParts.joinToString(" + ")
        }

        val notification = NotificationCompat.Builder(this, MeshSatApp.CHANNEL_GATEWAY)
            .setContentTitle("MeshSat Gateway")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

        try {
            @Suppress("DEPRECATION")
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(1, notification)
        } catch (_: Exception) {}
    }
}
