package com.cubeos.meshsat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class MeshSatApp : Application() {

    companion object {
        const val CHANNEL_GATEWAY = "meshsat_gateway"
        const val CHANNEL_MESSAGES = "meshsat_messages"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GATEWAY,
                "Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MeshSat gateway background service"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming mesh and satellite messages"
            }
        )
    }
}
