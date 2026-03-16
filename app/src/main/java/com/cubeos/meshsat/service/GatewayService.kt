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
import com.cubeos.meshsat.engine.InterfaceConfig
import com.cubeos.meshsat.engine.InterfaceManager
import com.cubeos.meshsat.engine.InterfaceState
import com.cubeos.meshsat.engine.InterfaceStatusProvider
import com.cubeos.meshsat.engine.SequenceTracker
import com.cubeos.meshsat.rules.AccessEvaluator
import com.cubeos.meshsat.rules.ForwardingRule
import com.cubeos.meshsat.rules.RouteMessage
import com.cubeos.meshsat.rules.RulesEngine
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
        const val ACTION_DISCONNECT_MESH = "com.cubeos.meshsat.DISCONNECT_MESH"
        const val ACTION_DISCONNECT_IRIDIUM = "com.cubeos.meshsat.DISCONNECT_IRIDIUM"
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
        val rulesEngine = RulesEngine()

        // SOS state
        private val _sosActive = MutableStateFlow(false)
        val sosActive: StateFlow<Boolean> = _sosActive
        private val _sosSends = MutableStateFlow(0)
        val sosSends: StateFlow<Int> = _sosSends

        // Phone GPS location (updated continuously)
        private val _phoneLocation = MutableStateFlow<Location?>(null)
        val phoneLocation: StateFlow<Location?> = _phoneLocation

        private var notificationId = 100
    }

    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var db: AppDatabase
    private var sosJob: kotlinx.coroutines.Job? = null
    private var msvqscEncoder: MsvqscEncoder? = null

    // Phase B: structured dispatch (replaces basic if/else routing)
    private var dispatcher: Dispatcher? = null
    private var accessEvaluator: AccessEvaluator? = null

    // Phase C: transport hardening
    private var interfaceManager: InterfaceManager? = null
    private var ackTracker: AckTracker? = null
    private val sequenceTracker = SequenceTracker()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        db = AppDatabase.getInstance(this)

        meshtasticBle = MeshtasticBle(this)
        iridiumSpp = IridiumSpp(this)

        startForegroundNotification()
        loadRulesFromDb()
        initInterfaceManager()
        initDispatcher()
        observeTransports()
        startSignalPolling()
        startLocationUpdates()
        initMsvqsc()
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
            ACTION_DISCONNECT_MESH -> meshtasticBle?.disconnect()
            ACTION_DISCONNECT_IRIDIUM -> iridiumSpp?.disconnect()
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
        ackTracker?.stop()
        ackTracker = null
        dispatcher?.stop()
        dispatcher = null
        interfaceManager?.stopAll()
        interfaceManager = null
        accessEvaluator = null
        sosJob?.cancel()
        meshtasticBle?.disconnect()
        iridiumSpp?.disconnect()
        meshtasticBle = null
        iridiumSpp = null
        msvqscEncoder?.close()
        msvqscEncoder = null
        scope.cancel()
        super.onDestroy()
    }

    /** Initialize MSVQ-SC encoder in background (loads ONNX model + codebook). */
    private fun initMsvqsc() {
        scope.launch {
            try {
                val codebook = MsvqscCodebook.loadFromAssets(this@GatewayService)
                if (codebook != null) {
                    val encoder = MsvqscEncoder.loadFromAssets(this@GatewayService, codebook)
                    if (encoder != null) {
                        msvqscEncoder = encoder
                        Log.i("MeshSat", "MSVQ-SC encoder ready (${codebook.stages} stages, K=${codebook.k})")
                    }
                }
            } catch (e: Exception) {
                Log.w("MeshSat", "MSVQ-SC init failed (compression disabled): ${e.message}")
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
                interfaceId.startsWith("iridium") -> {
                    val spp = iridiumSpp ?: return@setConnectCallback "iridium transport not available"
                    spp.reconnect()
                    null
                }
                else -> null
            }
        }

        // Disconnect callback
        mgr.setDisconnectCallback { interfaceId ->
            when {
                interfaceId.startsWith("mesh") -> meshtasticBle?.disconnect()
                interfaceId.startsWith("iridium") -> iridiumSpp?.disconnect()
            }
        }

        interfaceManager = mgr

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

        Log.i("MeshSat", "InterfaceManager initialized with 3 interfaces")
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

                // Access evaluator
                val eval = AccessEvaluator(db.accessRuleDao(), db.objectGroupDao(), scope)
                eval.reloadFromDb()
                accessEvaluator = eval

                // Interface status provider — delegates to InterfaceManager (Phase C)
                val mgr = interfaceManager
                val statusProvider = InterfaceStatusProvider { interfaceId ->
                    mgr?.isOnline(interfaceId) ?: when {
                        interfaceId.startsWith("mesh") ->
                            meshtasticBle?.state?.value == MeshtasticBle.State.Connected
                        interfaceId.startsWith("iridium") ->
                            iridiumSpp?.state?.value == IridiumSpp.State.Connected
                        interfaceId.startsWith("sms") -> true
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

                // Start workers for the three Android interfaces
                val interfaces = mapOf(
                    "mesh_0" to "mesh",
                    "iridium_0" to "iridium",
                    "sms_0" to "sms",
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
                interfaceId.startsWith("iridium") -> {
                    val spp = iridiumSpp
                        ?: return "iridium not available"
                    if (spp.state.value != IridiumSpp.State.Connected)
                        return "iridium not connected"
                    val data = if (payload.isNotEmpty()) payload else textPreview.toByteArray()
                    if (data.size > 340) return "payload too large (${data.size} > 340)"
                    val written = spp.writeMoBuffer(data)
                    if (!written) return "MO buffer write failed"
                    val result = spp.sbdix()
                    if (result?.moSuccess != true) return "SBDIX failed: mo_status=${result?.moStatus}"
                    db.messageDao().insert(
                        Message(
                            transport = "iridium", direction = "tx", sender = "self",
                            text = textPreview, forwarded = true, forwardedTo = "iridium:sbd",
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
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

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
        // Listen for incoming Meshtastic messages (text + position + nodeinfo)
        meshtasticBle?.let { ble ->
            scope.launch {
                ble.receivedData.collect { data ->
                    // Try text message first
                    val msg = MeshtasticProtocol.parseFromRadio(data)
                    if (msg != null) {
                        val nodeId = MeshtasticProtocol.formatNodeId(msg.from)
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

                    // Try position
                    val pos = MeshtasticProtocol.parsePositionFromRadio(data)
                    if (pos != null) {
                        val nodeId = MeshtasticProtocol.formatNodeId(pos.from)
                        db.nodePositionDao().insert(
                            NodePosition(
                                nodeId = pos.from,
                                nodeName = nodeId,
                                latitude = pos.latitude,
                                longitude = pos.longitude,
                                altitude = pos.altitude,
                            )
                        )
                        Log.d("MeshSat", "Position from $nodeId: ${pos.latitude},${pos.longitude}")
                        return@collect
                    }

                    // Try my_info (device info on connect)
                    val myInfo = MeshtasticProtocol.parseMyInfo(data)
                    if (myInfo != null) {
                        ble.setMyInfo(myInfo)
                        Log.d("MeshSat", "MyInfo: node=${myInfo.myNodeNum}, fw=${myInfo.firmwareVersion}")
                        return@collect
                    }

                    // Try node_info
                    val nodeInfo = MeshtasticProtocol.parseNodeInfoFromRadio(data)
                    if (nodeInfo != null) {
                        ble.addNodeInfo(nodeInfo)
                        Log.d("MeshSat", "NodeInfo: ${nodeInfo.longName} (${nodeInfo.shortName})")
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

        // Update notification on state changes
        meshtasticBle?.let { ble ->
            scope.launch { ble.state.collect { updateNotification() } }
        }
        iridiumSpp?.let { spp ->
            scope.launch { spp.state.collect { updateNotification() } }
        }

        // Log errors
        meshtasticBle?.let { ble ->
            scope.launch { ble.error.collect { err -> Log.w("MeshSat", "BLE: $err") } }
        }
        iridiumSpp?.let { spp ->
            scope.launch { spp.error.collect { err -> Log.w("MeshSat", "SPP: $err") } }
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

        val msvqscOn = settings.msvqscEnabled.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3

        SmsSender.send(
            context = this,
            to = phone,
            text = text,
            encryptionKey = encKey,
            msvqscEncoder = if (msvqscOn) msvqscEncoder else null,
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

    private fun forwardToMesh(text: String) {
        val ble = meshtasticBle ?: return
        if (ble.state.value != MeshtasticBle.State.Connected) return
        val proto = MeshtasticProtocol.encodeTextMessage(text)
        ble.sendToRadio(proto)

        scope.launch {
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
    }

    private suspend fun forwardToIridium(text: String) {
        val spp = iridiumSpp ?: return
        if (spp.state.value != IridiumSpp.State.Connected) return

        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > 340) {
            Log.w("MeshSat", "Iridium MO payload too large: ${data.size}")
            return
        }

        val written = spp.writeMoBuffer(data)
        if (written) {
            val result = spp.sbdix()
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
            if (result?.moSuccess != true) {
                Log.w("MeshSat", "SBDIX failed: mo_status=${result?.moStatus}")
            }
        }
    }

    /** Send a text message to mesh (called from UI compose bar). */
    fun sendMeshMessage(text: String, to: Long = 0xFFFFFFFFL, channel: Int = 0) {
        val ble = meshtasticBle ?: return
        if (ble.state.value != MeshtasticBle.State.Connected) return
        val proto = MeshtasticProtocol.encodeTextMessage(text, to, channel)
        ble.sendToRadio(proto)

        scope.launch {
            db.messageDao().insert(
                Message(
                    transport = "mesh",
                    direction = "tx",
                    sender = "self",
                    text = text,
                    timestamp = System.currentTimeMillis(),
                )
            )
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
        val convKey = db.conversationKeyDao().getBySender(recipient)?.hexKey
        val globalKey = settings.encryptionKey.first()
        val encEnabled = settings.encryptionEnabled.first()

        val keyToUse = convKey?.ifEmpty { null } ?: if (encEnabled) globalKey.ifEmpty { null } else null

        val msvqscOn = settings.msvqscEnabled.first()
        val stages = settings.msvqscStages.first().toIntOrNull() ?: 3

        SmsSender.send(
            context = this,
            to = recipient,
            text = text,
            encryptionKey = keyToUse,
            msvqscEncoder = if (msvqscOn) msvqscEncoder else null,
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

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
