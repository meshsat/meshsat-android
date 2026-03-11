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
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ForwardingRuleEntity
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.data.NodePosition
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.data.SignalRecord
import com.cubeos.meshsat.rules.ForwardingRule
import com.cubeos.meshsat.rules.RulesEngine
import com.cubeos.meshsat.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var db: AppDatabase
    private var sosJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        db = AppDatabase.getInstance(this)

        meshtasticBle = MeshtasticBle(this)
        iridiumSpp = IridiumSpp(this)

        startForegroundNotification()
        loadRulesFromDb()
        observeTransports()
        startSignalPolling()
        startLocationUpdates()
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
        sosJob?.cancel()
        meshtasticBle?.disconnect()
        iridiumSpp?.disconnect()
        meshtasticBle = null
        iridiumSpp = null
        scope.cancel()
        super.onDestroy()
    }

    private fun loadRulesFromDb() {
        scope.launch {
            val entities = db.forwardingRuleDao().getAllSync()
            rulesEngine.setRules(entities.map { it.toRule() })
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
                evaluateAndForward(ForwardingRule.Transport.IRIDIUM, mtText, imei)
            }
        }
    }

    private suspend fun evaluateAndForward(source: ForwardingRule.Transport, text: String, sender: String) {
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

        val payload = if (encrypt) {
            val key = settings.encryptionKey.first()
            if (key.isBlank()) text else AesGcmCrypto.encryptToBase64(text, key)
        } else {
            text
        }

        SmsSender.send(this, phone, payload)

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
        val payload = if (keyToUse != null) {
            try {
                AesGcmCrypto.encryptToBase64(text, keyToUse)
            } catch (e: Exception) {
                Log.w("MeshSat", "Encrypt for SMS failed: ${e.message}")
                text
            }
        } else {
            text
        }

        SmsSender.send(this, recipient, payload)

        db.messageDao().insert(
            Message(
                transport = "sms",
                direction = "tx",
                sender = "self",
                recipient = recipient,
                text = text,
                rawText = if (keyToUse != null) payload else "",
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
