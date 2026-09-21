package net.meshsat.android.mqtt

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttClient
import org.eclipse.paho.client.mqttv3.MqttException
import kotlin.concurrent.thread

/**
 * Ends a Paho client for good (MESHSAT-1305).
 *
 * With automatic reconnect on, a client that has lost its connection keeps reconnecting on
 * its own timer. `disconnect()` does not stop that timer and fails on a client that is not
 * connected; only `close()` ends it (Paho 1.2.5: the next attempt finds the client closed
 * and nothing schedules another). On 21 Sep 2026 a restarted gateway left its old Hub client
 * trying a replaced password under the same client id for as long as the process lived;
 * with a valid password it would have taken the new session over.
 */
object PahoClients {
    private const val TAG = "PahoClients"
    private const val CLOSE_RETRY_MS = 500L
    private const val CLOSE_ATTEMPTS = 60

    /**
     * Disconnect [client] if it is connected, then close it, off the calling thread: this is
     * called from `onDestroy` and from Paho's own callback thread, where blocking deadlocks.
     * A connect in flight makes `close()` refuse until it has finished, so it is tried again.
     */
    fun retire(client: IMqttClient?, disconnectTimeoutMs: Long = 1000) {
        client ?: return
        thread(name = "paho-retire-${client.clientId}", isDaemon = true) {
            if (client.isConnected) runCatching { client.disconnect(disconnectTimeoutMs) }
            repeat(CLOSE_ATTEMPTS) {
                if (closeNow(client)) return@thread
                // Still connecting or connected: whatever it reached, cut it.
                if (client.isConnected) runCatching { client.disconnectForcibly(0, disconnectTimeoutMs) }
                Thread.sleep(CLOSE_RETRY_MS)
            }
            Log.w(TAG, "Could not close MQTT client ${client.clientId}")
        }
    }

    internal fun closeNow(client: IMqttClient): Boolean = try {
        client.close()
        true
    } catch (e: MqttException) {
        false
    }
}
