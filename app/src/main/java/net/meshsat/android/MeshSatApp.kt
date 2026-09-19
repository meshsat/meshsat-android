package net.meshsat.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import net.meshsat.android.engine.TelemetryLogger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MeshSatApp : Application() {

    companion object {
        const val CHANNEL_GATEWAY = "meshsat_gateway"
        const val CHANNEL_MESSAGES = "meshsat_messages"
        /**
         * The status-bar satellite icon. A silent (LOW) channel is hidden from the status bar
         * by Android's default "Hide silent notifications in status bar", so this one is
         * DEFAULT importance with no sound or vibration. A channel's importance cannot be raised
         * after it exists, hence the new id; the old one is deleted (MESHSAT-1241).
         */
        const val CHANNEL_IRIDIUM_SIGNAL = "meshsat_iridium_status"
        const val CHANNEL_SOS = "meshsat_sos"
        private const val CHANNEL_IRIDIUM_SIGNAL_OLD = "meshsat_iridium_signal"
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

        // An SOS in progress: loud once, then kept up to date quietly (MESHSAT-1249).
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SOS,
                "SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "An SOS or alarm test in progress, with a button to cancel it"
            }
        )

        // Low importance: the icon shows in the status bar, with no sound or badge (MESHSAT-1241).
        manager.deleteNotificationChannel(CHANNEL_IRIDIUM_SIGNAL_OLD)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_IRIDIUM_SIGNAL,
                "Iridium signal",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Satellite icon with the Iridium signal while the modem is connected"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
        )
    }
}
