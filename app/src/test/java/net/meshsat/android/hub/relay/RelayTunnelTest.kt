package net.meshsat.android.hub.relay

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The client end of the Hub relay against a MockWebServer standing in for
 * `GET /api/relay/connect/{bridge_id}` (MESHSAT-1157; contract docs/relay.md).
 */
class RelayTunnelTest {

    private lateinit var server: MockWebServer

    /** What the fake Hub saw and can do. */
    private open class HubEnd : WebSocketListener() {
        val frames = LinkedBlockingQueue<ByteString>()
        val opened = CountDownLatch(1)
        @Volatile var socket: WebSocket? = null
        @Volatile var closeCode = -1

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            frames.add(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            webSocket.close(code, null)
        }
    }

    private val tunnels = mutableListOf<RelayTunnel>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    @After
    fun tearDown() {
        tunnels.forEach { it.close() }
        server.shutdown()
    }

    private fun hubBase() = "http://${server.hostName}:${server.port}"

    private fun tunnel(
        target: String = "kit-a",
        own: String = "phone-1",
        password: String = "s3cret",
        frames: ((ByteArray) -> Unit)? = null,
    ): RelayTunnel = RelayTunnel(hubBase(), target, own, password, frameListener = frames).also { tunnels += it }

    private fun <T : RelayTunnel.RelayState> awaitState(t: RelayTunnel, cls: Class<T>, ms: Long = 5_000): T =
        runBlocking { withTimeout(ms) { cls.cast(t.state.first { cls.isInstance(it) }) } }

    private fun awaitTerminal(t: RelayTunnel, ms: Long = 5_000): RelayTunnel.RelayState =
        runBlocking {
            withTimeout(ms) {
                t.state.first { it is RelayTunnel.RelayState.Closed || it is RelayTunnel.RelayState.Refused }
            }
        }

    // ── URL and identity ───────────────────────────────────────────────────

    @Test
    fun `upgrade carries HTTP Basic own-id colon password and names the target in the path`() {
        val hub = HubEnd()
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = tunnel(target = "kit-a", own = "phone-1", password = "pw/with:chars")
        t.open()
        val open = awaitState(t, RelayTunnel.RelayState.Open::class.java)
        assertTrue("a local port is allocated", open.localPort > 0)

        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req)
        assertEquals("/api/relay/connect/kit-a", req!!.path)
        val expected = "Basic " + Base64.getEncoder().encodeToString("phone-1:pw/with:chars".toByteArray())
        assertEquals(expected, req.getHeader("Authorization"))
        assertEquals("websocket", req.getHeader("Upgrade")?.lowercase())
    }

    @Test
    fun `hub api base is derived from the mqtt url unless an explicit one is given`() {
        assertEquals("https://hub.meshsat.net", RelayTunnel.deriveHubApiBase("wss://mqtt-hub.meshsat.net/mqtt"))
        assertEquals("https://hub.example.org", RelayTunnel.deriveHubApiBase("ssl://hub.example.org:8883"))
        assertEquals("http://broker.lan", RelayTunnel.deriveHubApiBase("tcp://broker.lan:1883"))
        assertEquals("https://hub.mine", RelayTunnel.deriveHubApiBase("wss://mqtt-hub.meshsat.net/mqtt", "https://hub.mine/"))
        assertEquals("https://hub.mine", RelayTunnel.deriveHubApiBase("", "hub.mine"))
        assertEquals("", RelayTunnel.deriveHubApiBase("not a url", ""))
        assertEquals(
            "https://hub.meshsat.net/api/relay/connect/kit%2Fa",
            RelayTunnel.connectUrl("wss://hub.meshsat.net", "kit/a"),
        )
    }

    // ── Bytes both ways ────────────────────────────────────────────────────

    @Test
    fun `bytes written to the local socket arrive at the hub as binary frames and frames come back out`() {
        val hub = HubEnd()
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = tunnel()
        t.open()
        val open = awaitState(t, RelayTunnel.RelayState.Open::class.java)

        Socket(InetAddress.getLoopbackAddress(), open.localPort).use { s ->
            s.getOutputStream().write("hello kit".toByteArray())
            s.getOutputStream().flush()
            val up = hub.frames.poll(5, TimeUnit.SECONDS)
            assertNotNull("frame reached the hub", up)
            assertEquals("hello kit", up!!.utf8())

            assertTrue(hub.opened.await(5, TimeUnit.SECONDS))
            hub.socket!!.send("hello phone".toByteArray().toByteString())
            val buf = ByteArray(64)
            val n = s.getInputStream().read(buf)
            assertEquals("hello phone", String(buf, 0, n))
        }

        // One socket per tunnel: closing it closes the tunnel from this end.
        val end = awaitTerminal(t)
        assertTrue(end is RelayTunnel.RelayState.Closed)
        assertEquals(RelayTunnel.CloseReason.Local, (end as RelayTunnel.RelayState.Closed).reason)
    }

    @Test
    fun `a 40 KiB sendFrame goes out as two frames of 32 KiB and 8 KiB`() {
        val hub = HubEnd()
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val received = LinkedBlockingQueue<ByteArray>()
        val t = tunnel(frames = { received.add(it) })
        t.open()
        awaitState(t, RelayTunnel.RelayState.Open::class.java)

        val payload = ByteArray(40 * 1024) { (it % 251).toByte() }
        assertTrue(t.sendFrame(payload))
        val first = hub.frames.poll(5, TimeUnit.SECONDS)!!
        val second = hub.frames.poll(5, TimeUnit.SECONDS)!!
        assertEquals(32 * 1024, first.size)
        assertEquals(8 * 1024, second.size)
        assertArrayEquals(payload, first.toByteArray() + second.toByteArray())
        assertTrue("no third frame", hub.frames.poll(300, TimeUnit.MILLISECONDS) == null)
    }

    @Test
    fun `a 40 KiB write to the local socket never produces a frame above 32 KiB and arrives whole`() {
        val hub = HubEnd()
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = tunnel()
        t.open()
        val open = awaitState(t, RelayTunnel.RelayState.Open::class.java)

        val payload = ByteArray(40 * 1024) { (it % 253).toByte() }
        Socket(InetAddress.getLoopbackAddress(), open.localPort).use { s ->
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            var got = ByteArray(0)
            var frames = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (got.size < payload.size && System.nanoTime() < deadline) {
                val f = hub.frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
                assertTrue("frame of ${f.size} B exceeds the 32 KiB chunk", f.size <= RelayTunnel.CHUNK)
                got += f.toByteArray()
                frames++
            }
            assertArrayEquals(payload, got)
            assertTrue("at least two frames for 40 KiB, got $frames", frames >= 2)
        }
    }

    // ── Hub-side endings ───────────────────────────────────────────────────

    @Test
    fun `hub close 1008 maps to Closed budget`() {
        val hub = object : HubEnd() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                webSocket.close(1008, "relay budget exhausted for this minute")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = tunnel()
        t.open()
        val end = awaitTerminal(t)
        assertTrue("got $end", end is RelayTunnel.RelayState.Closed)
        end as RelayTunnel.RelayState.Closed
        assertEquals(RelayTunnel.CloseReason.Budget, end.reason)
        assertEquals("relay budget exhausted for this minute", end.detail)
        assertFalse(t.isOpen)
        assertFalse("nothing can be sent on a closed tunnel", t.sendFrame(byteArrayOf(1)))
    }

    @Test
    fun `hub close codes 1001 and 1003 map to superseded and bad frame`() {
        assertEquals(RelayTunnel.CloseReason.Superseded, RelayTunnel.reasonFor(1001))
        assertEquals(RelayTunnel.CloseReason.BadFrame, RelayTunnel.reasonFor(1003))
        assertEquals(RelayTunnel.CloseReason.Normal, RelayTunnel.reasonFor(1000))
        assertEquals(RelayTunnel.CloseReason.Error, RelayTunnel.reasonFor(1006))
    }

    @Test
    fun `HTTP 403 before the upgrade is Refused 403`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("not a bridge of your tenant"))
        val t = tunnel(target = "somebody-elses-kit")
        t.open()
        val end = awaitTerminal(t)
        assertEquals(RelayTunnel.RelayState.Refused(403), end)
        assertFalse(t.isOpen)
    }

    @Test
    fun `HTTP 429 before the upgrade is Refused 429`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val t = tunnel()
        t.open()
        assertEquals(RelayTunnel.RelayState.Refused(429), awaitTerminal(t))
    }

    @Test
    fun `close from this end sends a normal close and reports Local`() {
        val hub = HubEnd()
        server.enqueue(MockResponse().withWebSocketUpgrade(hub))
        val t = tunnel()
        t.open()
        val open = awaitState(t, RelayTunnel.RelayState.Open::class.java)
        t.close()
        val end = awaitTerminal(t) as RelayTunnel.RelayState.Closed
        assertEquals(RelayTunnel.CloseReason.Local, end.reason)
        // the hub end saw a normal close
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (hub.closeCode == -1 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1000, hub.closeCode)
        // and the local port is gone
        val port = (open.localPort)
        assertTrue(runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure)
    }
}
