package net.meshsat.android.hub.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The client end of a Hub WebSocket relay tunnel (MESHSAT-1157; contract: Hub
 * docs/relay.md, MESHSAT-612).
 *
 * Opens `wss://<hub>/api/relay/connect/<targetBridgeId>` with HTTP Basic
 * `<ownBridgeId>:<hub MQTT password>`. Frames on this socket are the bare payload in
 * both directions, at most 64 KiB; anything the phone sends is chunked at 32 KiB.
 *
 * Two ways to use the bytes:
 *  - **Local port** (default): the tunnel listens on a loopback port and accepts ONE
 *    socket. Bytes written to it go up as binary frames, frames come back out of it.
 *    [RelayHttp] speaks TLS+HTTP/1.1 to the bridge over that port. When the accepted
 *    socket closes the tunnel closes with it: the bridge end holds one TLS session per
 *    tunnel, so a second socket could not be served anyway.
 *  - **Frames** ([frameListener] set before [open]): no port; frames go straight to
 *    the listener and [sendFrame] writes them. [RelayBridgeTransport] uses this to
 *    carry Reticulum packets, which are already framed.
 *
 * Pure JVM: no android.* import, so it runs unchanged in unit tests and in the live test.
 */
class RelayTunnel(
    hubBaseUrl: String,
    val targetBridgeId: String,
    private val ownBridgeId: String,
    private val password: String,
    private val frameListener: ((ByteArray) -> Unit)? = null,
    private val client: OkHttpClient = defaultClient(),
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** Largest frame the Hub accepts from either end. */
        const val MAX_FRAME = 64 * 1024

        /** Chunk size for the TLS stream inside the tunnel (contract: 32 KiB). */
        const val CHUNK = 32 * 1024

        /** Hub pings every 30 s and closes after 60 s of silence; OkHttp answers pings itself. */
        const val CONNECT_TIMEOUT_S = 15L

        /** Backpressure ceiling on OkHttp's outbound queue before the socket pump pauses. */
        const val MAX_QUEUED_BYTES = 1L shl 20

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // the Hub keeps the socket alive with pings
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /**
         * The Hub API base for a configured Hub. Settings hold the MQTT URL the Hub's
         * provisioning bundle gives out (`wss://mqtt-hub.meshsat.net/mqtt`); the relay
         * is on the API host. The hosted Hub names its broker `mqtt-<api host>`, so that
         * prefix is dropped; a self-hosted Hub whose broker is elsewhere sets the Hub
         * API URL explicitly (`hub_relay_url`), which wins whenever it is non-blank.
         */
        fun deriveHubApiBase(mqttUrl: String, explicitApiUrl: String = ""): String {
            if (explicitApiUrl.isNotBlank()) return normalizeBase(explicitApiUrl)
            val uri = runCatching { URI(mqttUrl.trim()) }.getOrNull() ?: return ""
            val host = uri.host ?: return ""
            val apiHost = if (host.startsWith("mqtt-")) host.removePrefix("mqtt-") else host
            val scheme = when (uri.scheme?.lowercase()) {
                "ws", "tcp", "http" -> "http"
                else -> "https"
            }
            return "$scheme://$apiHost"
        }

        /** https/http base with no trailing slash; ws(s) is accepted and mapped. */
        fun normalizeBase(url: String): String {
            var u = url.trim().trimEnd('/')
            u = when {
                u.startsWith("wss://", ignoreCase = true) -> "https://" + u.substring(6)
                u.startsWith("ws://", ignoreCase = true) -> "http://" + u.substring(5)
                u.contains("://") -> u
                else -> "https://$u"
            }
            return u
        }

        fun connectUrl(hubBaseUrl: String, targetBridgeId: String): String =
            normalizeBase(hubBaseUrl) + "/api/relay/connect/" +
                URLEncoder.encode(targetBridgeId, StandardCharsets.UTF_8.name())

        fun basicAuth(user: String, password: String): String =
            "Basic " + Base64.getEncoder().encodeToString(
                "$user:$password".toByteArray(StandardCharsets.UTF_8),
            )

        /** Map a Hub close code to a reason (docs/relay.md, "Close codes"). */
        fun reasonFor(code: Int): CloseReason = when (code) {
            1000 -> CloseReason.Normal
            1001 -> CloseReason.Superseded
            1003 -> CloseReason.BadFrame
            1008 -> CloseReason.Budget
            else -> CloseReason.Error
        }
    }

    enum class CloseReason {
        /** 1000: the Hub closed normally (restart, read loop ended). */
        Normal,
        /** 1001: the same identity opened a newer socket. */
        Superseded,
        /** 1003: the Hub refused a frame (not binary, too large). */
        BadFrame,
        /** 1008: 100 frames/minute spent for this client id. */
        Budget,
        /** The Hub went silent or the transport failed. */
        Error,
        /** This end closed: [close] or the local socket went away. */
        Local,
    }

    sealed class RelayState {
        object Connecting : RelayState() {
            override fun toString() = "Connecting"
        }

        /** The socket is up. [localPort] is 0 in frames mode. */
        data class Open(val localPort: Int) : RelayState()

        data class Closed(val reason: CloseReason, val detail: String = "") : RelayState()

        /** The Hub answered before the upgrade: 401, 403, 429 (docs/relay.md, "Endpoints"). */
        data class Refused(val httpCode: Int) : RelayState()
    }

    val connectUrl: String = connectUrl(hubBaseUrl, targetBridgeId)

    private val _state = MutableStateFlow<RelayState>(RelayState.Connecting)
    val state: StateFlow<RelayState> = _state

    /** Optional callback fired on every state change, for callers without a coroutine scope. */
    @Volatile var onState: ((RelayState) -> Unit)? = null

    private val opened = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var accepted: Socket? = null
    @Volatile private var acceptedOut: OutputStream? = null
    private val outLock = Any()

    /** The loopback port, or 0 before [open] / in frames mode. */
    val localPort: Int get() = server?.localPort ?: 0

    val isOpen: Boolean get() = _state.value is RelayState.Open

    /** Open the WebSocket (and the loopback port unless a [frameListener] is set). Idempotent. */
    fun open() {
        if (!opened.compareAndSet(false, true)) return
        if (frameListener == null) {
            val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            server = ss
        }
        val request = Request.Builder()
            .url(connectUrl)
            .header("Authorization", basicAuth(ownBridgeId, password))
            .build()
        log("relay: connecting to $connectUrl as $ownBridgeId")
        ws = client.newWebSocket(request, Listener())
    }

    /** Close from this end. Safe to call any number of times. */
    fun close() = finish(RelayState.Closed(CloseReason.Local, "closed by client"), 1000, "client closing")

    /**
     * Write [payload] into the tunnel, chunked at [CHUNK]. Returns false when the tunnel
     * is not open or OkHttp refused to queue (its socket is closing).
     */
    fun sendFrame(payload: ByteArray): Boolean {
        val socket = ws ?: return false
        if (!isOpen) return false
        if (payload.isEmpty()) return true
        var off = 0
        while (off < payload.size) {
            val n = minOf(CHUNK, payload.size - off)
            if (!socket.send(payload.toByteString(off, n))) return false
            off += n
        }
        return true
    }

    // ── WebSocket side ──────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val ss = server
            if (ss != null) {
                Thread({ acceptLoop(ss) }, "relay-accept-$targetBridgeId").apply { isDaemon = true }.start()
            }
            log("relay: open to $targetBridgeId (local port ${ss?.localPort ?: 0})")
            setState(RelayState.Open(ss?.localPort ?: 0))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            val fl = frameListener
            if (fl != null) {
                fl(data)
                return
            }
            val out = acceptedOut
            if (out == null) {
                log("relay: ${data.size} B from $targetBridgeId with no local socket, dropped")
                return
            }
            try {
                synchronized(outLock) {
                    out.write(data)
                    out.flush()
                }
            } catch (e: IOException) {
                log("relay: local socket write failed: ${e.message}")
                finish(RelayState.Closed(CloseReason.Local, "local socket closed"), 1000, "local socket closed")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // The Hub never sends text; a text frame is a protocol error on either side.
            log("relay: text frame from Hub ignored (${text.length} chars)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            log("relay: Hub closing $code ${reason.ifBlank { "(no reason)" }}")
            // A close frame with no status reads as 1005, which OkHttp refuses to echo.
            runCatching { webSocket.close(code, null) }.onFailure { webSocket.close(1000, null) }
            finish(RelayState.Closed(reasonFor(code), reason), 1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(RelayState.Closed(reasonFor(code), reason), code, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            if (code != null && code != 101) {
                log("relay: refused before upgrade with HTTP $code")
                finish(RelayState.Refused(code), 1000, null)
            } else {
                log("relay: failed: ${t.message}")
                finish(RelayState.Closed(CloseReason.Error, t.message ?: t.javaClass.simpleName), 1000, null)
            }
        }
    }

    // ── Local port side ─────────────────────────────────────────────────────

    private fun acceptLoop(ss: ServerSocket) {
        val s = try {
            ss.accept()
        } catch (_: IOException) {
            return // server closed by finish()
        }
        if (terminal.get()) {
            runCatching { s.close() }
            return
        }
        s.tcpNoDelay = true
        accepted = s
        acceptedOut = s.getOutputStream()
        // One socket per tunnel: a second connection would collide with the bridge end's
        // single TLS session, so the listener stops accepting once it has its socket.
        runCatching { ss.close() }
        try {
            val input = s.getInputStream()
            val buf = ByteArray(CHUNK)
            while (!terminal.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val socket = ws ?: break
                while (socket.queueSize() > MAX_QUEUED_BYTES && !terminal.get()) {
                    Thread.sleep(5)
                }
                if (!socket.send(buf.toByteString(0, n))) break
            }
        } catch (_: IOException) {
            // socket closed underneath us
        }
        finish(RelayState.Closed(CloseReason.Local, "local socket closed"), 1000, "local socket closed")
    }

    // ── State ───────────────────────────────────────────────────────────────

    private fun setState(s: RelayState) {
        _state.value = s
        onState?.invoke(s)
    }

    /**
     * Enter a terminal state exactly once, releasing the socket, the local port and
     * the accepted connection. Later calls (OkHttp's onClosed after our own close) are
     * ignored so the first reason is the one reported.
     */
    private fun finish(s: RelayState, wsCode: Int, wsReason: String?) {
        if (!terminal.compareAndSet(false, true)) return
        runCatching { ws?.close(wsCode, wsReason) }
        runCatching { server?.close() }
        runCatching { accepted?.close() }
        acceptedOut = null
        setState(s)
    }
}
