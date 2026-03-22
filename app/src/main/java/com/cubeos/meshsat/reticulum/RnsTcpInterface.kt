package com.cubeos.meshsat.reticulum

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reticulum interface over TCP with HDLC framing.
 *
 * Wire-compatible with Python RNS TCPClientInterface. Connects to a remote
 * Reticulum node and exchanges HDLC-framed packets. No handshake — packets
 * flow immediately after TCP connect.
 *
 * HDLC framing:
 *   [0x7E] [escaped payload] [0x7E]
 *   Escape 0x7D → 0x7D 0x5D, then 0x7E → 0x7D 0x5E
 *
 * Enables interop with stock Reticulum (Python RNS) nodes over LAN or internet.
 *
 * [MESHSAT-268]
 */
class RnsTcpInterface(
    private val scope: CoroutineScope,
    override val interfaceId: String = "tcp_rns_0",
) : RnsInterface {

    companion object {
        private const val TAG = "RnsTcpInterface"
        const val DEFAULT_PORT = 4242

        // HDLC constants
        const val HDLC_FLAG: Byte = 0x7E
        const val HDLC_ESC: Byte = 0x7D
        const val HDLC_ESC_MASK: Int = 0x20

        // Reconnect
        const val RECONNECT_WAIT_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_BUFFER_SIZE = 16384

        // Minimum valid RNS packet size (2 header bytes + 1 hop + 16 dest hash)
        const val HEADER_MINSIZE = 19

        /**
         * HDLC-escape a raw packet for transmission.
         * Escapes 0x7D first (the escape byte), then 0x7E (the flag byte).
         */
        fun hdlcEscape(data: ByteArray): ByteArray {
            val out = mutableListOf<Byte>()
            for (b in data) {
                when (b) {
                    HDLC_ESC -> {
                        out.add(HDLC_ESC)
                        out.add((HDLC_ESC.toInt() xor HDLC_ESC_MASK).toByte())
                    }
                    HDLC_FLAG -> {
                        out.add(HDLC_ESC)
                        out.add((HDLC_FLAG.toInt() xor HDLC_ESC_MASK).toByte())
                    }
                    else -> out.add(b)
                }
            }
            return out.toByteArray()
        }

        /**
         * HDLC-unescape a received frame.
         * Reverses the escape sequences applied by [hdlcEscape].
         */
        fun hdlcUnescape(data: ByteArray): ByteArray {
            val out = mutableListOf<Byte>()
            var i = 0
            while (i < data.size) {
                if (data[i] == HDLC_ESC && i + 1 < data.size) {
                    out.add((data[i + 1].toInt() xor HDLC_ESC_MASK).toByte())
                    i += 2
                } else {
                    out.add(data[i])
                    i++
                }
            }
            return out.toByteArray()
        }
    }

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    override val name: String = "TCP"
    override val mtu: Int = RnsConstants.MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 50

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var receiveCallback: RnsReceiveCallback? = null
    @Volatile private var running = false
    @Volatile var host: String = ""
        private set
    @Volatile var port: Int = DEFAULT_PORT
        private set

    override val isOnline: Boolean get() = _state.value == State.Connected

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    /**
     * Connect to a remote Reticulum node.
     * Starts a background read loop with auto-reconnect.
     */
    fun connect(host: String, port: Int = DEFAULT_PORT) {
        this.host = host
        this.port = port
        running = true
        _error.value = ""
        scope.launch(Dispatchers.IO) { connectionLoop() }
    }

    override suspend fun start() {
        // Connection managed via connect()
    }

    override suspend fun stop() {
        running = false
        closeSocket()
    }

    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "tcp interface offline"
        val os = outputStream ?: return "tcp not connected"

        return try {
            val escaped = hdlcEscape(packet)
            val frame = ByteArray(escaped.size + 2)
            frame[0] = HDLC_FLAG
            escaped.copyInto(frame, 1)
            frame[frame.size - 1] = HDLC_FLAG

            synchronized(os) {
                os.write(frame)
                os.flush()
            }
            null
        } catch (e: IOException) {
            _error.value = e.message ?: "send failed"
            _state.value = State.Error
            "tcp send failed: ${e.message}"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Connection loop with auto-reconnect
    // ═══════════════════════════════════════════════════════════════

    private suspend fun connectionLoop() {
        while (running && scope.isActive) {
            try {
                _state.value = State.Connecting
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                socket = s
                outputStream = s.getOutputStream()
                _state.value = State.Connected
                Log.i(TAG, "Connected to $host:$port")

                readLoop(s.getInputStream())
            } catch (e: IOException) {
                Log.d(TAG, "TCP connection failed: ${e.message}")
                _error.value = e.message ?: "connection failed"
            } finally {
                _state.value = State.Disconnected
                closeSocket()
            }

            if (running) {
                Log.d(TAG, "Reconnecting in ${RECONNECT_WAIT_MS / 1000}s...")
                delay(RECONNECT_WAIT_MS)
            }
        }
    }

    /**
     * Read HDLC-framed packets from the TCP stream.
     * Scans for 0x7E delimiter pairs and delivers complete frames.
     */
    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val frameBuffer = mutableListOf<Byte>()
        var inFrame = false

        while (running) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break

            for (i in 0 until bytesRead) {
                val b = buffer[i]
                if (b == HDLC_FLAG) {
                    if (inFrame && frameBuffer.isNotEmpty()) {
                        // End of frame — process it
                        val raw = frameBuffer.toByteArray()
                        frameBuffer.clear()
                        val unescaped = hdlcUnescape(raw)
                        if (unescaped.size >= HEADER_MINSIZE) {
                            receiveCallback?.onReceive(interfaceId, unescaped)
                        }
                    } else {
                        frameBuffer.clear()
                    }
                    inFrame = true
                } else if (inFrame) {
                    frameBuffer.add(b)
                }
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
        outputStream = null
    }

    fun disconnect() {
        running = false
        closeSocket()
        _state.value = State.Disconnected
    }
}
