package com.cubeos.meshsat.aprs

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * APRS-IS client — direct TCP connection to the APRS Internet Service.
 *
 * Replaces the need for APRSDroid. The phone connects directly to an APRS-IS
 * server (e.g., rotate.aprs2.net:14580), authenticates with callsign + passcode,
 * and sends/receives APRS packets as TNC-2 text lines.
 *
 * Protocol:
 * - Login: `user CALL-SSID pass PASSCODE vers MeshSat 1.0 filter r/LAT/LON/RANGE`
 * - TX: `SOURCE>DEST,PATH:payload\r\n`
 * - RX: text lines, '#' prefix = server comment
 *
 * Reference: http://www.aprs-is.net/javAPRSFilter.aspx
 */
class AprsIsClient(
    private val scope: CoroutineScope,
) {
    private object Const {
        const val TAG = "AprsIsClient"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val VERSION = "MeshSat 1.0"
    }

    enum class State { Disconnected, Connecting, Connected, Error }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var running = false
    private var onPacket: ((AprsPacket) -> Unit)? = null

    /** Server verification message (populated after login). */
    var serverBanner: String = ""
        private set

    /** Whether we're authenticated (passcode verified by server). */
    var verified: Boolean = false
        private set

    /** Set callback for received APRS packets. */
    fun setPacketCallback(cb: (AprsPacket) -> Unit) {
        onPacket = cb
    }

    /**
     * Connect to an APRS-IS server.
     *
     * @param server APRS-IS server hostname (e.g., "rotate.aprs2.net")
     * @param port APRS-IS port (default 14580)
     * @param callsign Station callsign with SSID (e.g., "PA3XYZ-10")
     * @param passcode APRS-IS passcode (or "-1" for receive-only)
     * @param filterLat Filter center latitude (for server-side filtering)
     * @param filterLon Filter center longitude
     * @param filterRange Filter radius in km (default 100)
     */
    fun connect(
        server: String,
        port: Int = 14580,
        callsign: String,
        passcode: String = "-1",
        filterLat: Double = 0.0,
        filterLon: Double = 0.0,
        filterRange: Int = 100,
    ) {
        _state.value = State.Connecting
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(server, port), Const.CONNECT_TIMEOUT_MS)
                sock.soTimeout = 90_000 // APRS-IS keepalive is ~every 30s
                socket = sock

                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val pw = PrintWriter(sock.getOutputStream(), true)
                writer = pw

                // Read server banner
                val banner = reader.readLine() ?: throw Exception("No server banner")
                serverBanner = banner
                Log.i(Const.TAG, "Server: $banner")

                // Send login
                val filter = if (filterLat != 0.0 || filterLon != 0.0) {
                    " filter r/${"%.1f".format(filterLat)}/${"%.1f".format(filterLon)}/$filterRange"
                } else ""
                val loginLine = "user $callsign pass $passcode vers ${Const.VERSION}$filter"
                pw.println(loginLine)
                Log.i(Const.TAG, "Login sent: user $callsign pass *** vers ${Const.VERSION}$filter")

                // Read login response
                val loginResp = reader.readLine() ?: throw Exception("No login response")
                verified = loginResp.contains("verified", ignoreCase = true) &&
                        !loginResp.contains("unverified", ignoreCase = true)
                Log.i(Const.TAG, "Login response: $loginResp (verified=$verified)")

                _state.value = State.Connected

                // Read loop
                readLoop(reader)
            } catch (e: Exception) {
                Log.e(Const.TAG, "Connect failed: ${e.message}")
                _state.value = State.Error
            } finally {
                cleanup()
            }
        }
    }

    /** Disconnect from APRS-IS. */
    fun disconnect() {
        running = false
        cleanup()
        _state.value = State.Disconnected
    }

    /**
     * Send a raw APRS-IS packet line.
     * Format: SOURCE>DEST,PATH:payload
     *
     * Silent drop if disconnected — state, not error (MESHSAT-499).
     */
    suspend fun sendRaw(line: String) = withContext(Dispatchers.IO) {
        if (_state.value != State.Connected) return@withContext
        val pw = writer ?: return@withContext
        pw.println(line)
        Log.d(Const.TAG, "TX: $line")
    }

    /**
     * Send a position report.
     */
    suspend fun sendPosition(
        callsign: String,
        lat: Double,
        lon: Double,
        symbolTable: Char = '/',
        symbolCode: Char = '-',
        comment: String = "",
    ) {
        val posData = String(AprsCodec.encodePosition(lat, lon, symbolTable, symbolCode, comment))
        sendRaw("$callsign>APMSHT,TCPIP*:$posData")
    }

    /**
     * Send a directed message.
     */
    suspend fun sendMessage(callsign: String, to: String, text: String, msgId: String = "") {
        val msgData = String(AprsCodec.encodeMessage(to, text, msgId))
        sendRaw("$callsign>APMSHT,TCPIP*:$msgData")
    }

    /**
     * Send a message ACK.
     */
    suspend fun sendAck(callsign: String, to: String, msgId: String) {
        val padded = to.padEnd(9)
        sendRaw("$callsign>APMSHT,TCPIP*::$padded:ack$msgId")
    }

    val isConnected: Boolean
        get() = socket?.isConnected == true && _state.value == State.Connected

    private fun readLoop(reader: BufferedReader) {
        try {
            while (running) {
                val line = reader.readLine()
                if (line == null) {
                    if (running) {
                        Log.w(Const.TAG, "Connection closed by server")
                        _state.value = State.Disconnected
                    }
                    return
                }

                // Skip server comments
                if (line.startsWith("#")) {
                    Log.d(Const.TAG, "Server: $line")
                    continue
                }

                // Parse TNC-2 line: SOURCE>DEST,PATH:payload
                val pkt = parseTnc2Line(line) ?: continue
                onPacket?.invoke(pkt)
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(Const.TAG, "Read error: ${e.message}")
                _state.value = State.Error
            }
        }
    }

    private fun cleanup() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }

    companion object Parser {
        /**
         * Parse a TNC-2 format line into an AprsPacket.
         * Format: SOURCE>DEST,PATH1,PATH2:payload
         */
        fun parseTnc2Line(line: String): AprsPacket? {
            // Split at '>' to get source
            val gtIdx = line.indexOf('>')
            if (gtIdx < 1) return null
            val source = line.substring(0, gtIdx)

            // Split remainder at ':' to get dest+path and payload
            val rest = line.substring(gtIdx + 1)
            val colonIdx = rest.indexOf(':')
            if (colonIdx < 1 || colonIdx >= rest.length - 1) return null

            val destPath = rest.substring(0, colonIdx)
            val payload = rest.substring(colonIdx + 1)

            // Parse dest and path
            val parts = destPath.split(",")
            val dest = parts[0]
            val path = if (parts.size > 1) parts.subList(1, parts.size).joinToString(",") else ""

            if (payload.isEmpty()) return null

            val dataType = payload[0]
            var pkt = AprsPacket(
                source = source,
                dest = dest,
                path = path,
                dataType = dataType,
                raw = payload,
            )

            // Reuse AprsCodec parsing for position and message types
            when (dataType) {
                '!', '=' -> {
                    val posStr = payload.substring(1)
                    pkt = parsePositionInline(pkt, posStr)
                }
                '/', '@' -> {
                    if (payload.length > 8) {
                        pkt = parsePositionInline(pkt, payload.substring(8))
                    }
                }
                ':' -> {
                    if (payload.length > 1) {
                        pkt = parseMessageInline(pkt, payload.substring(1))
                    }
                }
            }

            return pkt
        }

        private fun parsePositionInline(pkt: AprsPacket, s: String): AprsPacket {
            if (s.length < 19) return pkt.copy(comment = s)
            val lat = parseAprsLat(s.substring(0, 8)) ?: return pkt.copy(comment = s)
            val lon = parseAprsLon(s.substring(9, 18)) ?: return pkt.copy(comment = s)
            val symbol = "${s[8]}${s[18]}"
            val comment = if (s.length > 19) s.substring(19) else ""
            return pkt.copy(lat = lat, lon = lon, symbol = symbol, comment = comment)
        }

        private fun parseMessageInline(pkt: AprsPacket, s: String): AprsPacket {
            if (s.length < 11) return pkt
            val msgTo = s.substring(0, 9).trim()
            if (s.length < 11 || s[9] != ':') return pkt.copy(msgTo = msgTo)
            var msg = s.substring(10)
            var msgId = ""
            val braceIdx = msg.lastIndexOf('{')
            if (braceIdx >= 0) {
                msgId = msg.substring(braceIdx + 1)
                msg = msg.substring(0, braceIdx)
            }
            return pkt.copy(msgTo = msgTo, message = msg, msgId = msgId)
        }

        private fun parseAprsLat(s: String): Double? {
            if (s.length != 8) return null
            val deg = s.substring(0, 2).toDoubleOrNull() ?: return null
            val min = s.substring(2, 7).toDoubleOrNull() ?: return null
            val lat = deg + min / 60.0
            return if (s[7] == 'S') -lat else lat
        }

        private fun parseAprsLon(s: String): Double? {
            if (s.length != 9) return null
            val deg = s.substring(0, 3).toDoubleOrNull() ?: return null
            val min = s.substring(3, 8).toDoubleOrNull() ?: return null
            val lon = deg + min / 60.0
            return if (s[8] == 'W') -lon else lon
        }
    }
}
