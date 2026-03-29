package com.cubeos.meshsat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MeshSatApp : Application() {

    companion object {
        const val CHANNEL_GATEWAY = "meshsat_gateway"
        const val CHANNEL_MESSAGES = "meshsat_messages"
    }

    override fun onCreate() {
        super.onCreate()
        // Android 16 removed Ed25519/X25519 from Conscrypt (only AndroidKeyStore
        // has them, but it won't export raw private keys). Register BouncyCastle
        // as the highest-priority provider so standard JCA calls use it.
        Security.removeProvider("BC")  // Remove Android's stripped BC
        Security.insertProviderAt(BouncyCastleProvider(), 1)
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
