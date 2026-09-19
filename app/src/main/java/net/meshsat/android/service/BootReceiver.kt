package net.meshsat.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.meshsat.android.data.SettingsRepository

/**
 * Starts the gateway after the phone restarts or the app is updated, when the user switched
 * "Start after a phone restart" on in Setup (MESHSAT-1249; off unless chosen). Without it the phone
 * stopped relaying after every restart until someone opened the app.
 *
 * BOOT_COMPLETED arrives after the first unlock, when the app's settings and database can be read.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (SettingsRepository(context).startOnBoot.first()) {
                    Log.i("MeshSat", "Starting the gateway after ${intent.action}")
                    ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
                }
            } catch (e: Exception) {
                Log.w("MeshSat", "Could not start the gateway after ${intent.action}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
