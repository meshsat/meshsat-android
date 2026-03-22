package com.cubeos.meshsat.reticulum

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Reticulum interface over Tor (SOCKS5 proxy).
 *
 * Connects to a remote Reticulum node through a Tor SOCKS5 proxy (e.g.,
 * Orbot running on the device at 127.0.0.1:9050). Uses HDLC framing
 * over the Tor-proxied TCP connection — identical wire format to
 * [RnsTcpInterface].
 *
 * The Tor proxy provides:
 * - Anonymity (IP hidden via onion routing)
 * - Censorship resistance (encrypted tunnel)
 * - .onion hidden service support (connect to RNS nodes as hidden services)
 *
 * Requirements:
 * - Orbot app installed and running, OR
 * - tor-android library embedded (future enhancement)
 *
 * [MESHSAT-270]
 */
class RnsTorInterface(
    private val scope: CoroutineScope,
    override val interfaceId: String = "tor_rns_0",
) : RnsInterface {

    companion object {
        private const val TAG = "RnsTorInterface"
        const val DEFAULT_SOCKS_PORT = 9050
        const val DEFAULT_TARGET_PORT = 4242
        const val RECONNECT_WAIT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 30_000 // Tor is slow
    }

    override val name: String = "Tor"
    override val mtu: Int = RnsConstants.MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 3000 // Tor adds ~2-5s RTT

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var receiveCallback: RnsReceiveCallback? = null
    @Volatile private var running = false
    @Volatile private var _isOnline = false

    @Volatile var socksHost: String = "127.0.0.1"
        private set
    @Volatile var socksPort: Int = DEFAULT_SOCKS_PORT
        private set
    @Volatile var targetHost: String = ""
        private set
    @Volatile var targetPort: Int = DEFAULT_TARGET_PORT
        private set

    override val isOnline: Boolean get() = _isOnline

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    /**
     * Connect to a Reticulum node through Tor.
     *
     * @param targetHost Remote RNS node hostname or .onion address
     * @param targetPort Remote RNS node port
     * @param socksHost Tor SOCKS5 proxy host (default: 127.0.0.1)
     * @param socksPort Tor SOCKS5 proxy port (default: 9050 for Orbot)
     */
    fun connect(
        targetHost: String,
        targetPort: Int = DEFAULT_TARGET_PORT,
        socksHost: String = "127.0.0.1",
        socksPort: Int = DEFAULT_SOCKS_PORT,
    ) {
        this.targetHost = targetHost
        this.targetPort = targetPort
        this.socksHost = socksHost
        this.socksPort = socksPort
        running = true
        scope.launch(Dispatchers.IO) { connectionLoop() }
    }

    override suspend fun start() {}

    override suspend fun stop() {
        running = false
        closeSocket()
    }

    override suspend fun send(packet: ByteArray): String? {
        if (!_isOnline) return "tor interface offline"
        val os = outputStream ?: return "tor not connected"

        return try {
            val escaped = RnsTcpInterface.hdlcEscape(packet)
            val frame = ByteArray(escaped.size + 2)
            frame[0] = RnsTcpInterface.HDLC_FLAG
            escaped.copyInto(frame, 1)
            frame[frame.size - 1] = RnsTcpInterface.HDLC_FLAG

            synchronized(os) {
                os.write(frame)
                os.flush()
            }
            null
        } catch (e: IOException) {
            _isOnline = false
            "tor send failed: ${e.message}"
        }
    }

    private suspend fun connectionLoop() {
        while (running && scope.isActive) {
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
                val s = Socket(proxy)
                s.tcpNoDelay = true
                s.connect(InetSocketAddress.createUnresolved(targetHost, targetPort), CONNECT_TIMEOUT_MS)

                socket = s
                outputStream = s.getOutputStream()
                _isOnline = true
                Log.i(TAG, "Connected via Tor to $targetHost:$targetPort")

                readLoop(s.getInputStream())
            } catch (e: IOException) {
                Log.d(TAG, "Tor connection failed: ${e.message}")
            } finally {
                _isOnline = false
                closeSocket()
            }

            if (running) {
                delay(RECONNECT_WAIT_MS)
            }
        }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(RnsTcpInterface.READ_BUFFER_SIZE)
        val frameBuffer = mutableListOf<Byte>()
        var inFrame = false

        while (running) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break

            for (i in 0 until bytesRead) {
                val b = buffer[i]
                if (b == RnsTcpInterface.HDLC_FLAG) {
                    if (inFrame && frameBuffer.isNotEmpty()) {
                        val raw = frameBuffer.toByteArray()
                        frameBuffer.clear()
                        val unescaped = RnsTcpInterface.hdlcUnescape(raw)
                        if (unescaped.size >= RnsTcpInterface.HEADER_MINSIZE) {
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
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        outputStream = null
    }

    fun disconnect() {
        running = false
        closeSocket()
        _isOnline = false
    }
}
