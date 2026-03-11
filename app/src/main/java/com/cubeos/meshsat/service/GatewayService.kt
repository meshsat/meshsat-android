package com.cubeos.meshsat.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cubeos.meshsat.MeshSatApp
import com.cubeos.meshsat.R

/**
 * Foreground service that keeps Bluetooth connections alive
 * for Meshtastic BLE and Iridium SPP (HC-05).
 *
 * Stub for now — will be expanded with BLE and SPP connection management.
 */
class GatewayService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
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
}
