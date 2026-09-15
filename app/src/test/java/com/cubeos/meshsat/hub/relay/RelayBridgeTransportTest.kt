package com.cubeos.meshsat.hub.relay

import com.cubeos.meshsat.reticulum.RnsReceiveCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [RelayBridgeTransport] as a Reticulum interface over the tunnel (MESHSAT-1157):
 * packets are frames, frames are packets, and the interface goes offline the
 * moment the Hub ends the tunnel.
 */
class RelayBridgeTransportTest {

    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val transports = mutableListOf<RelayBridgeTransport>()

    private open class HubEnd : WebSocketListener() {
        val frames = LinkedBlockingQueue<ByteString>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.add(bytes) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    @After
    fun tearDown() {
        transports.forEach { it.shutdown() }
        scope.cancel()
        server.shutdown()
    }

    private fun transport(): RelayBridgeTransport {
        val cfg = RelayBridgeTransport.Config(
            hubApiBase = "http://${server.hostName}:${server.port}",
            targetBridgeId = "kit-a",
            ownBridgeId = "phone-1",
            password = "pw",
        )
        return RelayBridgeTransport(scope, cfg).also { transports += it }
    }

    private fun awaitOpen(t: RelayBridgeTransport) = runBlocking {
        withTimeout(5_000) { t.state.first { it is RelayTunnel.RelayState.Open } }
    }

    @Test
    fun `identity is hub_relay, free and bidirectional`() {
        val t = transport()
        assertEquals("hub_relay", t.interfaceId)
        assertEquals(0, t.costCents)
        assertTrue(t.isBidirectional)
        assertFalse("not online before start", t.isOnline)
        assertEquals("hub relay offline", runBlocking { t.send(byteArrayOf(1, 2, 3)) })
    }

    @Test
    fun `send writes one frame per packet and received frames reach the callback with the interface id`() {
        val hub = HubEnd()
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = transport()
        val received = LinkedBlockingQueue<Pair<String, ByteArray>>()
        t.setReceiveCallback(RnsReceiveCallback { id, pkt -> received.add(id to pkt) })
        runBlocking { t.start() }
        awaitOpen(t)
        assertTrue(t.isOnline)

        val packet = ByteArray(300) { it.toByte() }
        assertNull(runBlocking { t.send(packet) })
        val up = hub.frames.poll(5, TimeUnit.SECONDS)
        assertNotNull(up)
        assertArrayEquals(packet, up!!.toByteArray())

        val down = ByteArray(120) { (it * 3).toByte() }
        hub.socket!!.send(down.toByteString())
        val got = received.poll(5, TimeUnit.SECONDS)
        assertNotNull(got)
        assertEquals("hub_relay", got!!.first)
        assertArrayEquals(down, got.second)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/relay/connect/kit-a", req.path)
    }

    @Test
    fun `hub close 1008 takes the interface offline with reason budget`() {
        val hub = object : HubEnd() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                webSocket.close(1008, "relay budget exhausted for this minute")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = transport()
        runBlocking { t.start() }
        val end = runBlocking {
            withTimeout(5_000) { t.state.first { it is RelayTunnel.RelayState.Closed } }
        } as RelayTunnel.RelayState.Closed
        assertEquals(RelayTunnel.CloseReason.Budget, end.reason)
        assertFalse(t.isOnline)
        assertEquals("hub relay offline", runBlocking { t.send(byteArrayOf(1)) })
    }

    @Test
    fun `HTTP 403 before the upgrade is reported as Refused and the interface stays offline`() {
        server.enqueue(MockResponse().setResponseCode(403))
        val t = transport()
        runBlocking { t.start() }
        val end = runBlocking {
            withTimeout(5_000) { t.state.first { it is RelayTunnel.RelayState.Refused } }
        }
        assertEquals(RelayTunnel.RelayState.Refused(403), end)
        assertFalse(t.isOnline)
    }

    @Test
    fun `stop closes the tunnel and reports offline`() {
        val hub = HubEnd()
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = transport()
        runBlocking { t.start() }
        awaitOpen(t)
        runBlocking { t.stop() }
        assertFalse(t.isOnline)
        assertTrue(t.state.value is RelayTunnel.RelayState.Closed)
    }
}
