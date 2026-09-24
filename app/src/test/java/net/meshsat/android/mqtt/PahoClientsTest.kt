package net.meshsat.android.mqtt

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A stopped client must stop reconnecting (MESHSAT-1305). On 21 Sep 2026 the old Hub client
 * of a restarted gateway went on logging in with a replaced password at 1.6, 2.5, 4.6, 8.5
 * and 16.6 s: Paho's automatic reconnect, which disconnect() does not reach.
 */
class PahoClientsTest {

    /** A broker that accepts every CONNECT, answers CONNACK and drops the connection. */
    private class DroppingBroker : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val connects = AtomicInteger()
        val url = "tcp://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        private fun serve(s: Socket) = s.use {
            val input = DataInputStream(it.getInputStream())
            input.readUnsignedByte() // CONNECT
            var len = 0
            var shift = 0
            do {
                val b = input.readUnsignedByte()
                len = len or ((b and 0x7f) shl shift)
                shift += 7
            } while (b and 0x80 != 0)
            input.readFully(ByteArray(len))
            connects.incrementAndGet()
            it.getOutputStream().apply { write(byteArrayOf(0x20, 0x02, 0x00, 0x00)); flush() }
            // Long enough for Paho to finish its own connect bookkeeping before the drop.
            Thread.sleep(100)
        }

        override fun close() = server.close()
    }

    private fun reconnectingClient(broker: DroppingBroker, id: String): MqttClient {
        val c = MqttClient(broker.url, id, MemoryPersistence())
        c.connect(MqttConnectOptions().apply {
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 5
        })
        val deadline = System.currentTimeMillis() + 15_000
        while (broker.connects.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the client reconnects on its own", broker.connects.get() >= 3)
        return c
    }

    @Test
    fun `disconnect alone leaves a dropped client reconnecting`() {
        DroppingBroker().use { broker ->
            val c = reconnectingClient(broker, "paho-disconnect-only")
            try {
                // Between connections, as the Hub client was: a client that is connected at
                // that moment would simply disconnect.
                while (c.isConnected) Thread.sleep(5)
                runCatching { c.disconnect(200) }
                val before = broker.connects.get()
                // Paho doubles its reconnect delay on every drop (1, 2, 4, 8 s...), so after the
                // third connect the next one can be 8 s away; a fixed 3 s wait failed on the CI
                // runner once in a while (pipeline 56142). Wait for it, within its own maximum.
                val deadline = System.currentTimeMillis() + 20_000
                while (broker.connects.get() <= before && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertTrue("still reconnecting after disconnect()", broker.connects.get() > before)
            } finally {
                PahoClients.retire(c)
            }
        }
    }

    @Test
    fun `a retired client stops reconnecting`() {
        DroppingBroker().use { broker ->
            val c = reconnectingClient(broker, "paho-retired")
            PahoClients.retire(c)
            Thread.sleep(2_000) // a reconnect already in flight may still land
            val settled = broker.connects.get()
            Thread.sleep(4_000)
            assertEquals("no connect after retire", settled, broker.connects.get())
        }
    }
}
