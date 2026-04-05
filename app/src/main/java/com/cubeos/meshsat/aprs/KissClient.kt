package com.cubeos.meshsat.aprs

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * KISS TCP client for connecting to APRSDroid or Direwolf TNC.
 * Reads AX.25 frames from KISS stream, decodes APRS packets.
 * Ported from meshsat Bridge internal/gateway/aprs_kiss.go + aprs.go.
 */
class KissClient(
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "KissClient"
    }

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private var socket: Socket? = null
    private var running = false
    private var onFrame: ((Ax25Frame) -> Unit)? = null

    /** Set callback for decoded AX.25 frames. */
    fun setFrameCallback(cb: (Ax25Frame) -> Unit) {
        onFrame = cb
    }

    /** Connect to KISS TCP server (APRSDroid or Direwolf). */
    fun connect(host: String, port: Int) {
        _state.value = State.Connecting
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 10_000)
                sock.soTimeout = 30_000
                socket = sock
                _state.value = State.Connected
                Log.i(TAG, "Connected to $host:$port")
                readLoop(sock.getInputStream())
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                _state.value = State.Error
            }
        }
    }

    /** Disconnect from the TNC. */
    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        _state.value = State.Disconnected
    }

    /**
     * Send an AX.25 frame via KISS.
     * Silent drop if disconnected — state, not error (MESHSAT-499).
     */
    suspend fun sendFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
        if (_state.value != State.Connected) return@withContext
        val sock = socket ?: return@withContext
        val kissFrame = KissCodec.encode(frame)
        sock.getOutputStream().write(kissFrame)
        sock.getOutputStream().flush()
    }

    val isConnected: Boolean
        get() = socket?.isConnected == true && _state.value == State.Connected

    private fun readLoop(input: InputStream) {
        try {
            val frameBuf = ByteArrayOutputStream()
            var inFrame = false

            while (running) {
                val b = try { input.read() } catch (_: Exception) { -1 }
                if (b == -1) {
                    if (running) {
                        Log.w(TAG, "Connection closed")
                        _state.value = State.Disconnected
                    }
                    return
                }

                val byte = b.toByte()
                if (byte == KissCodec.FEND) {
                    if (inFrame && frameBuf.size() > 0) {
                        val decoded = KissCodec.decode(frameBuf.toByteArray())
                        if (decoded != null) {
                            val ax25 = Ax25Codec.decode(decoded)
                            if (ax25 != null) {
                                onFrame?.invoke(ax25)
                            }
                        }
                        frameBuf.reset()
                    }
                    inFrame = true
                    continue
                }

                if (inFrame) {
                    frameBuf.write(b)
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "Read error: ${e.message}")
                _state.value = State.Error
            }
        }
    }
}
