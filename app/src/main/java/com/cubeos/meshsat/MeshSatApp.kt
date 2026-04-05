package com.cubeos.meshsat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.cubeos.meshsat.engine.TelemetryLogger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MeshSatApp : Application() {

    companion object {
        const val CHANNEL_GATEWAY = "meshsat_gateway"
        const val CHANNEL_MESSAGES = "meshsat_messages"
    }

    override fun onCreate() {
        super.onCreate()
        // Install the uncaught exception handler FIRST so any crash during
        // later init (BC registration, notification channels, etc.) is still
        // captured to pending_crash.json for next-startup recovery. See
        // TelemetryLogger.installCrashHandler for the ACRA-style pattern —
        // this intentionally does NOT touch Room or coroutines because the
        // process may be dying when it fires (MESHSAT-494).
        TelemetryLogger.installCrashHandler(this)

        // Android 16 only exposes Ed25519/X25519 via AndroidKeyStore, which won't
        // export raw private keys. Register BouncyCastle so callers can request
        // it explicitly by name (see Identity.kt / SigningService.kt).
        //
        // IMPORTANT: BC must be appended at the END of the provider chain, NOT
        // inserted at position 1. Putting BC at position 1 hijacks
        // SSLContext.getInstance("Default") and breaks Conscrypt's HTTPS stack
        // (blank map tiles, Hub TLS failure, etc. — see MESHSAT-497).
        Security.removeProvider("BC")  // Remove Android's stripped BC
        Security.addProvider(BouncyCastleProvider())  // Append at lowest priority
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
