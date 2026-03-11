package com.cubeos.meshsat.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cubeos.meshsat.MeshSatApp
import com.cubeos.meshsat.R
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.ble.MeshtasticProtocol
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.rules.ForwardingRule
import com.cubeos.meshsat.rules.RulesEngine
import com.cubeos.meshsat.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps Bluetooth connections alive
 * for Meshtastic BLE and Iridium SPP (HC-05).
 *
 * Manages transport lifecycle, message routing via RulesEngine,
 * and notification updates for connection state.
 */
class GatewayService : Service() {

    companion object {
        const val ACTION_CONNECT_MESH = "com.cubeos.meshsat.CONNECT_MESH"
        const val ACTION_CONNECT_IRIDIUM = "com.cubeos.meshsat.CONNECT_IRIDIUM"
        const val ACTION_DISCONNECT_MESH = "com.cubeos.meshsat.DISCONNECT_MESH"
        const val ACTION_DISCONNECT_IRIDIUM = "com.cubeos.meshsat.DISCONNECT_IRIDIUM"
        const val EXTRA_ADDRESS = "address"

        // Singleton references for UI state observation
        var meshtasticBle: MeshtasticBle? = null
            private set
        var iridiumSpp: IridiumSpp? = null
            private set
        val rulesEngine = RulesEngine()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        db = AppDatabase.getInstance(this)

        meshtasticBle = MeshtasticBle(this)
        iridiumSpp = IridiumSpp(this)

        startForegroundNotification()
        observeTransports()
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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        meshtasticBle?.disconnect()
        iridiumSpp?.disconnect()
        meshtasticBle = null
        iridiumSpp = null
        scope.cancel()
        super.onDestroy()
    }

    private fun observeTransports() {
        // Listen for incoming Meshtastic messages
        meshtasticBle?.let { ble ->
            scope.launch {
                ble.receivedData.collect { data ->
                    val msg = MeshtasticProtocol.parseFromRadio(data) ?: return@collect
                    val nodeId = MeshtasticProtocol.formatNodeId(msg.from)

                    // Store message
                    db.messageDao().insert(
                        Message(
                            transport = "mesh",
                            direction = "in",
                            sender = nodeId,
                            text = msg.text,
                            encrypted = false,
                            timestamp = System.currentTimeMillis(),
                        )
                    )

                    // Evaluate forwarding rules
                    val decisions = rulesEngine.evaluate(
                        source = ForwardingRule.Transport.MESH,
                        text = msg.text,
                        sender = nodeId,
                    )
                    for (decision in decisions) {
                        if (!decision.shouldForward) continue
                        when (decision.rule?.destTransport) {
                            ForwardingRule.Transport.SMS -> forwardToSms(msg.text, decision.encrypt)
                            else -> {} // other transports not yet wired
                        }
                    }
                }
            }
        }

        // Update notification on state changes
        meshtasticBle?.let { ble ->
            scope.launch {
                ble.state.collect { updateNotification() }
            }
        }
        iridiumSpp?.let { spp ->
            scope.launch {
                spp.state.collect { updateNotification() }
            }
        }
    }

    private suspend fun forwardToSms(text: String, encrypt: Boolean) {
        val phone = settings.meshsatPiPhone.first()
        if (phone.isBlank()) return

        val payload = if (encrypt) {
            val key = settings.encryptionKey.first()
            if (key.isBlank()) text else AesGcmCrypto.encrypt(text, key) ?: text
        } else {
            text
        }

        SmsSender.send(this, phone, payload, encrypt)
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
