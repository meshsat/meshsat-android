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
import java.net.Socket

/**
 * Reticulum interface over WireGuard tunnel.
 *
 * Connects to a remote Reticulum node through an active WireGuard VPN tunnel.
 * Uses HDLC framing over TCP — identical wire format to [RnsTcpInterface] —
 * but the TCP connection is routed through the WireGuard tunnel.
 *
 * The WireGuard tunnel itself must be established separately via:
 * - Android's built-in WireGuard support (Android 12+)
 * - The official WireGuard app
 * - wireguard-android library with VpnService
 *
 * This interface connects to the remote RNS node's IP address inside the
 * WireGuard network (e.g., 10.0.0.2:4242), which is reachable only when
 * the tunnel is up.
 *
 * [MESHSAT-271]
 */
class RnsWireGuardInterface(
    private val scope: CoroutineScope,
    override val interfaceId: String = "wg_rns_0",
) : RnsInterface {

    companion object {
        private const val TAG = "RnsWireGuardInterface"
        const val DEFAULT_PORT = 4242
        const val RECONNECT_WAIT_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 10_000
    }

    override val name: String = "WireGuard"
    override val mtu: Int = RnsConstants.MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 20 // WireGuard adds minimal latency

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var receiveCallback: RnsReceiveCallback? = null
    @Volatile private var running = false
    @Volatile private var _isOnline = false

    @Volatile var host: String = ""
        private set
    @Volatile var port: Int = DEFAULT_PORT
        private set

    override val isOnline: Boolean get() = _isOnline

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    /**
     * Connect to a Reticulum node inside the WireGuard tunnel.
     *
     * @param host Remote RNS node IP inside the WG network (e.g., 10.0.0.2)
     * @param port Remote RNS node port (default: 4242)
     */
    fun connect(host: String, port: Int = DEFAULT_PORT) {
        this.host = host
        this.port = port
        running = true
        scope.launch(Dispatchers.IO) { connectionLoop() }
    }

    override suspend fun start() {}

    override suspend fun stop() {
        running = false
        closeSocket()
    }

    override suspend fun send(packet: ByteArray): String? {
        if (!_isOnline) return "wireguard interface offline"
        val os = outputStream ?: return "wireguard not connected"

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
            "wireguard send failed: ${e.message}"
        }
    }

    private suspend fun connectionLoop() {
        while (running && scope.isActive) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                socket = s
                outputStream = s.getOutputStream()
                _isOnline = true
                Log.i(TAG, "Connected via WireGuard to $host:$port")

                readLoop(s.getInputStream())
            } catch (e: IOException) {
                Log.d(TAG, "WireGuard TCP connection failed: ${e.message}")
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
