package net.meshsat.android.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import net.meshsat.android.ble.ModemLink
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Iridium RockBLOCK 9704 IMT transport over Bluetooth SPP (HC-05).
 *
 * Uses the JSPR (JSON Serial Protocol for REST) protocol at 230400 baud.
 * The HC-05 module bridges between Android Bluetooth SPP and the 9704's
 * 16-pin UART (pins 5=TX, 6=RX, 9=GND).
 *
 * Key differences from 9603:
 * - JSPR protocol (text JSON over serial) instead of AT commands
 * - 230400 baud instead of 19200
 * - Up to 100KB messages instead of 340 bytes
 * - Segmented transfer with base64 encoding
 * - CRC-16/CCITT-FALSE instead of simple checksum
 * - Topic-based message routing
 * - Modem-driven segmentation (modem requests segments)
 *
 * MESHSAT-245
 */
@SuppressLint("MissingPermission")
class Iridium9704Spp(private val context: Context) {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "Iridium9704"

        private const val JSPR_TIMEOUT_MS = 10_000L
        // Through the node's Bluetooth pipe a constellationState answer can take longer than 2 s,
        // and a missed one used to read as 0 bars (MESHSAT-1670).
        private const val SIGNAL_TIMEOUT_MS = SignalFreshness.POLL_TIMEOUT_MS

        // How often the driver looks whether a signal reading is due (MESHSAT-1670): it asks the
        // modem only when no reading came for SignalFreshness.POLL_INTERVAL_MS and no send is under way.
        private const val SIGNAL_CHECK_MS = 5_000L

        // The poll loop's pause between looks at the link; shorter while a send is under way, as the
        // modem gives up on its segment request after about 360 ms (MESHSAT-1674).
        private const val POLL_PAUSE_MS = 50L
        private const val POLL_PAUSE_SENDING_MS = 10L

        // Before a command, a half-arrived line is given this long to finish (MESHSAT-1674).
        private const val PARTIAL_LINE_GRACE_MS = 20L
        // An accepted message stays in the modem until it goes or is cancelled, so a send that
        // walks away on a timeout leaves it there and the retry queues a second copy (the Bridge's
        // 20 message soak of 21 Sep 2026: 7 duplicates, MESHSAT-1282). As the Bridge does
        // (internal/transport/jspr.go, cmd/jspr-helper), a send with no final status after 150 s
        // is cancelled by its message_id with Ground Control's PUT messageOriginateStatus
        // {"action": "cancel"}, and the modem's one answer for that id settles it within 20 s:
        // cancelled (a retry is safe) or mo_ack_received (it went).
        private const val MO_BUDGET_MS = 150_000L
        private const val MO_CANCEL_SETTLE_MS = 20_000L

        /** A send's answer when the modem never settled its cancel: the message may still go out. */
        const val MO_CANCEL_UNANSWERED = "cancel_unanswered"

        /**
         * The bars after a signal read: the modem's answer, or the last reading when it did not
         * answer in time. An unanswered read used to set 0, so the app showed 0/5 while the modem
         * was sending (MSC-2 unit 0001, 24 Oct 2026, MESHSAT-1670); the Bridge keeps its last
         * reading the same way (internal/transport/direct_imt.go GetSignal and GetSignalFast).
         */
        fun signalAfterPoll(previous: Int, answeredBars: Int?): Int = answeredBars?.coerceIn(0, 5) ?: previous

        /**
         * True when a messageOriginateStatus naming [statusId] settles the send in flight as
         * [inFlightId]. The modem reports every message it holds, including those the node or
         * another client queued on it (MESHSAT-1507), so another id never settles this send.
         * A status without an id (-1) still does, as before.
         */
        internal fun moStatusSettles(inFlightId: Int, statusId: Int): Boolean = statusId < 0 || statusId == inFlightId

        /**
         * The modem's firmware version as the official RockBLOCK 9704 C library makes it
         * (`rbGetFirmwareVersion`): "v" major.minor.patch from `GET firmware {"slot": "primary"}`,
         * or "" when the modem did not answer. hwInfo carries only the hardware version; the
         * firmware field used to repeat it. [MESHSAT-1630]
         */
        internal fun firmwareVersion(resp: JSONObject?): String {
            val v = resp?.optJSONObject("version") ?: return ""
            if (!v.has("major") || !v.has("minor") || !v.has("patch")) return ""
            return "v${v.optInt("major")}.${v.optInt("minor")}.${v.optInt("patch")}"
        }
        private const val MAX_SEGMENT_SIZE = 1446
        const val RAW_TOPIC = 244   // IMT topic for raw payloads; the Reticulum interface sends on it too (MESHSAT-1553)

        /** Max IMT message size (100KB). */
        const val IMT_MAX_SIZE = 100_000

        // CRC-16/CCITT-FALSE lookup table (polynomial 0x1021, init 0x0000)
        private val CRC16_TABLE = IntArray(256).also { table ->
            for (i in 0..255) {
                var crc = i shl 8
                for (bit in 0..7) {
                    crc = if (crc and 0x8000 != 0) {
                        (crc shl 1) xor 0x1021
                    } else {
                        crc shl 1
                    }
                }
                table[i] = crc and 0xFFFF
            }
        }

        /**
         * One JSPR segment's data field: standard base64, no line breaks. java.util.Base64, not
         * android.util.Base64, so the JVM tests run the bytes a sealed message travels as.
         */
        fun jsprSegmentData(segment: ByteArray): String = java.util.Base64.getEncoder().encodeToString(segment)

        /** A JSPR segment's data field back to its bytes, lenient as android.util.Base64.DEFAULT was. */
        fun jsprSegmentBytes(data: String): ByteArray = java.util.Base64.getMimeDecoder().decode(data)

        /** Compute CRC-16/CCITT-FALSE over [data]. */
        fun crc16(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                val tableIndex = ((crc shr 8) xor (b.toInt() and 0xFF)) and 0xFF
                crc = ((crc shl 8) xor CRC16_TABLE[tableIndex]) and 0xFFFF
            }
            return crc
        }

        /**
         * Add spaces after colons and commas in JSON to match the official
         * RockBLOCK-9704 C library format. The modem firmware's parser rejects
         * compact JSON with error 407 BAD_JSON.
         */
        fun spacedJson(json: String): String {
            if (json == "{}") return json
            val sb = StringBuilder(json.length + 16)
            var inString = false
            for (i in json.indices) {
                val ch = json[i]
                if (ch == '"' && (i == 0 || json[i - 1] != '\\')) {
                    inString = !inString
                }
                sb.append(ch)
                if (!inString && (ch == ':' || ch == ',')) {
                    sb.append(' ')
                }
            }
            return sb.toString()
        }
    }

    enum class State { Disconnected, Connecting, Connected, Initializing, Ready }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // The MeshSat node's Iridium pipe when the 9704 sits behind it (MESHSAT-1507); null on an HC-05.
    private var link: ModemLink? = null

    @Volatile var lastAddress: String? = null
        private set

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _signal = MutableStateFlow(0)
    val signal: StateFlow<Int> = _signal

    private val _reading = MutableStateFlow<SignalReading?>(null)

    /** The last signal reading and its time, null until the modem gave one on this link (MESHSAT-1670). */
    val reading: StateFlow<SignalReading?> = _reading

    private val pollRoundTrips = ArrayDeque<Long>()
    private var pollsAnswered = 0
    private var pollsMissed = 0
    private val _pollStats = MutableStateFlow(SignalPollStats())

    /** The signal reads' round trips on this link, for the bench (MESHSAT-1670). */
    val pollStats: StateFlow<SignalPollStats> = _pollStats

    private val _segmentTiming = MutableStateFlow<SegmentTiming?>(null)

    /** The last MO segment request and how fast this app answered it (MESHSAT-1674). */
    val segmentTiming: StateFlow<SegmentTiming?> = _segmentTiming

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error

    private val _receivedMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val receivedMessages: SharedFlow<ByteArray> = _receivedMessages

    private val _modemInfo = MutableStateFlow(ModemInfo())
    val modemInfo: StateFlow<ModemInfo> = _modemInfo

    data class ModemInfo(
        val imei: String = "",
        val serial: String = "",
        val firmwareVersion: String = "",
        val hardwareVersion: String = "",
        val boardTemp: Int = 0,
    )

    // --- MO state ---
    private var moPayload: ByteArray? = null
    // The whole payload as one segment's data, encoded when the send starts, so the answer to the
    // modem's usual single segment request is ready at once (MESHSAT-1674).
    @Volatile private var moPayloadB64: String? = null
    @Volatile private var moStartedAtNs: Long = 0
    private var moMessageId: Int = 0
    private var moTopicId: Int = RAW_TOPIC
    private var moRequestRef: Int = 0
    private var nextRequestRef: Int = 1

    enum class MoStatus { Idle, Pending, Segmenting, Complete, Failed }
    private val _moStatus = MutableStateFlow(MoStatus.Idle)
    val moStatus: StateFlow<MoStatus> = _moStatus
    private var moFinalStatus: String = ""

    // --- MT state ---
    // The MT buffer grows as segments land, zero-filled up to the furthest byte written, never past
    // message_length_max: the announced length is not allocated up front, so a negative one cannot
    // throw and a huge one cannot exhaust memory. [MESHSAT-1630]
    private var mtBuffer: ByteArray? = null
    private var mtLengthMax: Int = 0
    private var mtReceived: Int = 0
    private var mtMessageId: Int = 0
    private var mtTopicId: Int = 0

    // --- Poll loop ---
    @Volatile private var polling = false

    // Serializes all serial I/O (sendJspr + poll loop).
    private val serialLock = Any()

    // The link's priority writer, when it has one: the node's pipe puts these writes ahead of
    // the other Bluetooth operations of the connection (MESHSAT-1674).
    private var urgentOutputStream: OutputStream? = null

    // Unsolicited frames other than segment requests, handled one at a time in the order they
    // came: an MT's notice, segments and status used to run as separate coroutines.
    private val unsolicited = Channel<Triple<Int, String, JSONObject>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((code, target, json) in unsolicited) {
                try {
                    handleUnsolicitedResponse(code, target, json)
                } catch (e: Exception) {
                    Log.e(TAG, "Unsolicited $target: ${e.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Connect / Disconnect
    // ═══════════════════════════════════════════════════════════════

    fun connect(address: String) {
        lastAddress = address
        val device = adapter?.getRemoteDevice(address) ?: run {
            scope.launch { _error.emit("Invalid Bluetooth address: $address") }
            return
        }
        connect(device)
    }

    fun reconnect() {
        val addr = lastAddress
        if (addr != null) {
            connect(addr)
        } else {
            scope.launch { _error.emit("No previous SPP address for reconnect") }
        }
    }

    fun connect(device: BluetoothDevice) {
        scope.launch {
            _state.value = State.Connecting
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter?.cancelDiscovery()
                s.connect()
                socket = s
                inputStream = s.inputStream
                outputStream = s.outputStream
                urgentOutputStream = null
                forgetReadings()
                _state.value = State.Connected

                initialize()
            } catch (e: IOException) {
                _state.value = State.Disconnected
                _error.emit("9704 SPP connect failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        if (link != null) {
            detach()
            return
        }
        polling = false
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
        inputStream = null
        outputStream = null
        urgentOutputStream = null
        _state.value = State.Disconnected
    }

    /**
     * Use the node's Iridium pipe as the serial link, as the 9603 driver does: the node has given
     * this phone the modem, the bytes are JSPR at 230400 on the node's UART. The init runs as
     * after an SPP connect; its first request may be answered MALFORMED because the node's reset
     * text sits in the modem's parser, and the init retries after a drain.
     */
    fun attach(newLink: ModemLink) {
        detach()
        link = newLink
        inputStream = newLink.input
        outputStream = newLink.output
        urgentOutputStream = newLink.urgentOutput
        forgetReadings()
        _state.value = State.Connected
        scope.launch { initialize() }
    }

    /**
     * [attach] only while the driver is idle, as one step: the pipe observer and its retry can
     * both find the 9704 down at the same moment, and two inits on one link would read each
     * other's replies.
     */
    @Synchronized
    fun attachIfIdle(newLink: ModemLink): Boolean {
        if (_state.value != State.Disconnected) return false
        attach(newLink)
        return true
    }

    /** True while the driver rides the node's pipe, including after an init over it failed. */
    val onNodePipe: Boolean get() = link != null

    /** Stop using the node's pipe without closing it; the node keeps the modem powered. No-op on an HC-05. */
    fun detach() {
        if (link == null) return
        polling = false
        link = null
        inputStream = null
        outputStream = null
        urgentOutputStream = null
        _state.value = State.Disconnected
    }

    /** A new link starts with no reading and no round trips (MESHSAT-1670). */
    private fun forgetReadings() {
        _reading.value = null
        synchronized(pollRoundTrips) {
            pollRoundTrips.clear()
            pollsAnswered = 0
            pollsMissed = 0
        }
        _pollStats.value = SignalPollStats()
    }

    // ═══════════════════════════════════════════════════════════════
    // JSPR initialization sequence
    // ═══════════════════════════════════════════════════════════════

    private suspend fun initialize() {
        _state.value = State.Initializing
        try {
            drainInput()
            delay(5) // settling time after drain (per official library)

            // Step 1: Negotiate API version (retry up to 3 times, matching Go impl)
            var apiVersionOk = false
            for (attempt in 1..3) {
                if (attempt > 1) {
                    delay(5)
                    drainInput()
                }
                val versionResp = sendJspr("GET", "apiVersion", JSONObject())
                if (versionResp == null) continue

                val activeVersion = versionResp.optJSONObject("active_version")
                if (activeVersion != null) {
                    apiVersionOk = true
                    break
                }

                // Select first supported version (matching official C library)
                val versions = versionResp.optJSONArray("supported_versions")
                if (versions != null && versions.length() > 0) {
                    val first = versions.getJSONObject(0)
                    val setVersion = JSONObject().put("active_version", first)
                    sendJspr("PUT", "apiVersion", setVersion)
                    apiVersionOk = true
                    break
                }
            }
            if (!apiVersionOk) {
                throw IOException("Failed to negotiate API version after 3 attempts")
            }

            // Step 2: Configure SIM
            val simResp = sendJspr("GET", "simConfig", JSONObject())
            val currentInterface = simResp?.optString("interface", "")
            if (currentInterface != "internal") {
                sendJspr("PUT", "simConfig", JSONObject().put("interface", "internal"))
                delay(500)
                // Drain simStatus unsolicited
                synchronized(serialLock) { drainInput() }
            }

            // Step 3: Set operational state to active
            val stateResp = sendJspr("GET", "operationalState", JSONObject())
            val currentState = stateResp?.optString("state", "")
            if (currentState != "active") {
                if (currentState != "inactive") {
                    sendJspr("PUT", "operationalState", JSONObject().put("state", "inactive"))
                    delay(200)
                }
                sendJspr("PUT", "operationalState", JSONObject().put("state", "active"))
            }

            // Step 4: Query hardware info
            val hwResp = sendJspr("GET", "hwInfo", JSONObject())
            if (hwResp != null) {
                val firmware = firmwareVersion(sendJspr("GET", "firmware", JSONObject().put("slot", "primary")))
                _modemInfo.value = ModemInfo(
                    imei = hwResp.optString("imei", ""),
                    serial = hwResp.optString("serial_number", ""),
                    firmwareVersion = firmware,
                    hardwareVersion = hwResp.optString("hw_version", ""),
                    boardTemp = hwResp.optInt("board_temp", 0),
                )
            }

            // Step 5: Check signal
            pollSignal()

            delay(100) // modem needs settling time after begin

            _state.value = State.Ready
            Log.i(TAG, "9704 initialized: ${_modemInfo.value}")

            // Start poll loop
            startPollLoop()
            startSignalWatch()

        } catch (e: Exception) {
            _error.emit("9704 init failed: ${e.message}")
            _state.value = State.Disconnected
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // JSPR serial I/O
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a JSPR request and wait for the matching response.
     * Format: "METHOD target {json}\r"
     *
     * Unsolicited (299) responses received while waiting are dispatched
     * to [handleUnsolicitedResponse].
     */
    private fun sendJspr(method: String, target: String, payload: JSONObject, timeoutMs: Long = JSPR_TIMEOUT_MS): JSONObject? {
        synchronized(serialLock) {
            val os = outputStream ?: throw IOException("Not connected")

            clearBeforeCommand()

            val jsonStr = spacedJson(payload.toString())
            val frame = "$method $target $jsonStr\r"
            os.write(frame.toByteArray(Charsets.US_ASCII))
            os.flush()

            // Read responses until we get one matching our target (or timeout)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val resp = readJsprResponse(remaining) ?: continue

                val code = resp.first
                val respTarget = resp.second
                val json = resp.third

                // Unsolicited: a segment request is answered here and now, the rest handed on
                if (code == 299) {
                    dispatchUnsolicited(code, respTarget, json)
                    continue
                }

                // Matching target or error response (modem may use different target on errors)
                if (respTarget == target || code >= 400) {
                    if (code != 200) {
                        Log.w(TAG, "JSPR $method $target -> $code")
                    }
                    return json
                }

                // Mismatched — discard and keep reading
                Log.d(TAG, "JSPR discard: expected=$target got=$respTarget")
            }

            Log.w(TAG, "JSPR timeout: $method $target")
            return null
        }
    }

    /**
     * Read a single JSPR response line.
     * Returns (code, target, json) or null on timeout.
     */
    private fun readJsprResponse(timeoutMs: Long = JSPR_TIMEOUT_MS): Triple<Int, String, JSONObject>? {
        val input = inputStream ?: return null
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break

                // Strip non-printable before response code (modem may send DC1=0x11 on boot)
                if (buf.isEmpty() && b != 0x0D && (b < 0x20 || b > 0x7E)) continue

                if (b == 0x0D) { // CR = end of frame
                    if (buf.isEmpty()) continue // empty line
                    return parseJsprLine(buf.toString())
                }
                buf.append(b.toChar())
            } else {
                Thread.sleep(5)
            }
        }

        // Try to parse whatever we got
        if (buf.isNotEmpty()) {
            return parseJsprLine(buf.toString())
        }
        return null
    }

    /**
     * Parse a JSPR response line: "CODE target {json}"
     */
    internal fun parseJsprLine(line: String): Triple<Int, String, JSONObject>? {
        if (line.length < 3) return null

        val codeStr = line.substring(0, 3)
        val code = codeStr.toIntOrNull() ?: return null
        if (code !in 200..500) return null

        // After code, skip space, read target
        var pos = 3
        while (pos < line.length && line[pos] == ' ') pos++

        val targetEnd = line.indexOf(' ', pos)
        if (targetEnd == -1) {
            // Target only, no JSON
            return Triple(code, line.substring(pos).trim(), JSONObject())
        }

        val target = line.substring(pos, targetEnd)
        val jsonStr = line.substring(targetEnd + 1).trim()

        val json = try {
            if (jsonStr.startsWith("{")) JSONObject(jsonStr) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }

        return Triple(code, target, json)
    }

    private fun drainInput() {
        val input = inputStream ?: return
        try {
            while (input.available() > 0) input.read()
        } catch (_: IOException) {}
    }

    /**
     * Before a command (MESHSAT-1674): whatever the link holds is read, every complete unsolicited
     * frame is dispatched and the rest dropped (an answer that came too late, the node's reset
     * text). A plain drain threw away a segment request that had just arrived, and the modem
     * failed the send with segment_not_supplied. A half-arrived line gets [PARTIAL_LINE_GRACE_MS]
     * to finish. The caller holds [serialLock].
     */
    private fun clearBeforeCommand() {
        val input = inputStream ?: return
        val buf = StringBuilder()
        try {
            var quietUntil = System.currentTimeMillis() + PARTIAL_LINE_GRACE_MS
            while (true) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b == -1) break
                    quietUntil = System.currentTimeMillis() + PARTIAL_LINE_GRACE_MS
                    if (b == 0x0D) {
                        if (buf.isNotEmpty()) {
                            val frame = parseJsprLine(buf.toString())
                            if (frame != null && frame.first == 299) {
                                dispatchUnsolicited(frame.first, frame.second, frame.third)
                            } else {
                                Log.d(TAG, "JSPR dropped before a command: $buf")
                            }
                            buf.setLength(0)
                        }
                        continue
                    }
                    if (buf.isEmpty() && (b < 0x20 || b > 0x7E)) continue
                    buf.append(b.toChar())
                } else {
                    if (buf.isEmpty() || System.currentTimeMillis() >= quietUntil) break
                    Thread.sleep(2)
                }
            }
        } catch (_: IOException) {}
    }

    /**
     * An unsolicited frame, from whichever reader holds [serialLock]: the modem's request for an MO
     * segment is answered right here, ahead of anything else (MESHSAT-1674); every other frame is
     * queued for [handleUnsolicitedResponse] in the order it came.
     */
    private fun dispatchUnsolicited(code: Int, target: String, json: JSONObject) {
        if (target == "messageOriginateSegment") {
            handleMoSegmentRequest(json, System.nanoTime())
            return
        }
        unsolicited.trySend(Triple(code, target, json))
    }

    // ═══════════════════════════════════════════════════════════════
    // Poll loop — process unsolicited 299 messages
    // ═══════════════════════════════════════════════════════════════

    private fun startPollLoop() {
        polling = true
        scope.launch {
            while (polling && isActive) {
                try {
                    synchronized(serialLock) {
                        val input = inputStream
                        if (input != null && input.available() > 0) {
                            val resp = readJsprResponse(1000)
                            if (resp != null && resp.first == 299) {
                                dispatchUnsolicited(resp.first, resp.second, resp.third)
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (polling) {
                        _error.emit("9704 poll error: ${e.message}")
                        disconnect()
                    }
                    break
                }
                delay(if (_moStatus.value == MoStatus.Idle) POLL_PAUSE_MS else POLL_PAUSE_SENDING_MS)
            }
        }
    }

    /**
     * Fill the gaps between the modem's own constellationState events (MESHSAT-1670): ask when no
     * reading came for [SignalFreshness.POLL_INTERVAL_MS], never while a send is under way.
     */
    private fun startSignalWatch() {
        scope.launch {
            while (polling && isActive) {
                delay(SIGNAL_CHECK_MS)
                if (_state.value != State.Ready || _moStatus.value != MoStatus.Idle) continue
                val last = _reading.value
                if (last == null || System.currentTimeMillis() - last.atMs >= SignalFreshness.POLL_INTERVAL_MS) pollSignal()
            }
        }
    }

    /** A reading the modem gave, by answer or by event (MESHSAT-1670). */
    private fun recordReading(bars: Int) {
        val b = bars.coerceIn(0, 5)
        _reading.value = SignalReading(b, System.currentTimeMillis())
        _signal.value = b
    }

    private suspend fun handleUnsolicitedResponse(code: Int, target: String, json: JSONObject) {
        when (target) {
            "constellationState" -> {
                // An event without the field is no reading, never 0 bars (MESHSAT-1670).
                if (json.has("signal_bars")) {
                    recordReading(json.optInt("signal_bars", 0))
                    Log.d(TAG, "Signal: ${_signal.value}/5")
                }
            }
            "messageOriginateStatus" -> {
                handleMoStatus(json)
            }
            "messageTerminate" -> {
                handleMtNotification(json)
            }
            "messageTerminateSegment" -> {
                handleMtSegment(json)
            }
            "messageTerminateStatus" -> {
                handleMtStatus(json)
            }
            else -> {
                Log.d(TAG, "Unsolicited: $code $target $json")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Send MO message (Mobile Originated)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a message via Iridium IMT. Non-blocking — starts the send and
     * returns immediately. Monitor [moStatus] for completion.
     *
     * @param data Raw payload bytes (max 100KB)
     * @param topicId Topic ID (default 244 = RAW)
     * @return true if send was initiated, false if busy or not ready
     */
    fun sendMessage(data: ByteArray, topicId: Int = RAW_TOPIC): Boolean {
        if (_state.value != State.Ready) return false
        if (_moStatus.value != MoStatus.Idle) return false
        if (data.size > IMT_MAX_SIZE) return false

        // Append CRC-16 to payload
        val crc = crc16(data)
        val payloadWithCrc = ByteArray(data.size + 2)
        data.copyInto(payloadWithCrc)
        payloadWithCrc[data.size] = (crc shr 8).toByte()
        payloadWithCrc[data.size + 1] = (crc and 0xFF).toByte()

        moPayload = payloadWithCrc
        moPayloadB64 = jsprSegmentData(payloadWithCrc)
        moStartedAtNs = System.nanoTime()
        moTopicId = topicId
        moRequestRef = nextRequestRef
        nextRequestRef = (nextRequestRef % 100) + 1
        _moStatus.value = MoStatus.Pending

        scope.launch {
            try {
                val json = JSONObject()
                    .put("topic_id", moTopicId)
                    .put("message_length", payloadWithCrc.size)
                    .put("request_reference", moRequestRef)

                val resp = sendJspr("PUT", "messageOriginate", json)
                val messageResponse = resp?.optString("message_response", "") ?: ""

                if (messageResponse == "message_accepted") {
                    moMessageId = resp?.optInt("message_id", 0) ?: 0
                    _moStatus.value = MoStatus.Segmenting
                    Log.d(TAG, "MO accepted: id=$moMessageId")
                } else {
                    Log.w(TAG, "MO rejected: $messageResponse")
                    _moStatus.value = MoStatus.Failed
                    moFinalStatus = messageResponse
                    _error.emit("MO rejected: $messageResponse")
                }
            } catch (e: Exception) {
                _moStatus.value = MoStatus.Failed
                _error.emit("MO send failed: ${e.message}")
            }
        }
        return true
    }

    /**
     * Blocking send — initiates MO and waits for final status.
     * Returns the final MO status string, or null on error.
     */
    suspend fun sendMessageBlocking(data: ByteArray, topicId: Int = RAW_TOPIC): String? {
        if (!sendMessage(data, topicId)) return null
        awaitMoFinal(MO_BUDGET_MS)?.let { return it }
        when (_moStatus.value) {
            // The answer landed as the budget ran out.
            MoStatus.Complete, MoStatus.Failed -> {
                val status = moFinalStatus
                resetMoStatus()
                return status
            }
            MoStatus.Segmenting -> {}
            else -> {
                // Never accepted: nothing of this send is in the modem.
                resetMoStatus()
                return null
            }
        }
        val id = moMessageId
        Log.w(TAG, "MO $id: no final status in ${MO_BUDGET_MS / 1000} s; cancelling it in the modem")
        val answer = try {
            sendJspr(
                "PUT", "messageOriginateStatus",
                JSONObject().put("topic_id", moTopicId).put("message_id", id).put("action", "cancel"),
            )
        } catch (e: IOException) {
            null
        }
        if (answer != null) awaitMoFinal(MO_CANCEL_SETTLE_MS)?.let { return it }
        Log.w(TAG, "MO $id: the modem did not settle the cancel; the message may still go out")
        resetMoStatus()
        return MO_CANCEL_UNANSWERED
    }

    /** The final status of the send in flight, once [handleMoStatus] has it, or null after [budgetMs]. */
    private suspend fun awaitMoFinal(budgetMs: Long): String? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            when (_moStatus.value) {
                MoStatus.Complete, MoStatus.Failed -> {
                    val status = moFinalStatus
                    resetMoStatus()
                    return status
                }
                else -> delay(100)
            }
        }
        return null
    }

    /**
     * Handle modem requesting a segment of the MO payload.
     * Modem sends: 299 messageOriginateSegment {segment_start, segment_length, ...}
     * Host responds: PUT messageOriginateSegment {data: base64, ...}
     *
     * Called by the reader that holds [serialLock], the moment the request is read (MESHSAT-1674):
     * the 9704 gives up after about 360 ms, and behind the node's pipe a request that waited for a
     * signal read or an MT's handling failed the send with segment_not_supplied. The answer echoes
     * the request's own topic and message id, as Ground Control's library does, so it never waits
     * for the accept to be booked; it goes out on the link's priority writer. [readAtNs] is when
     * the request was read.
     */
    private fun handleMoSegmentRequest(json: JSONObject, readAtNs: Long) {
        val payload = moPayload ?: return
        val segStart = json.optInt("segment_start", 0)
        val segLen = json.optInt("segment_length", 0)

        // A negative field from the modem reached copyOfRange and threw in the handler. [MESHSAT-1630]
        if (segStart < 0 || segLen < 0 || segStart > payload.size - segLen) {
            Log.w(TAG, "MO segment request out of bounds: start=$segStart len=$segLen payload=${payload.size}")
            return
        }

        val whole = moPayloadB64
        val b64 = if (segStart == 0 && segLen == payload.size && whole != null) {
            whole
        } else {
            jsprSegmentData(payload.copyOfRange(segStart, segStart + segLen))
        }

        val resp = JSONObject()
            .put("topic_id", if (json.has("topic_id")) json.optInt("topic_id") else moTopicId)
            .put("message_id", if (json.has("message_id")) json.optInt("message_id") else moMessageId)
            .put("segment_length", segLen)
            .put("segment_start", segStart)
            .put("data", b64)

        try {
            synchronized(serialLock) {
                val os = urgentOutputStream ?: outputStream ?: return
                val frame = "PUT messageOriginateSegment ${spacedJson(resp.toString())}\r"
                os.write(frame.toByteArray(Charsets.US_ASCII))
                os.flush()
            }
            val timing = SegmentTiming(
                askedAfterMs = (readAtNs - moStartedAtNs) / 1_000_000,
                answeredInMs = (System.nanoTime() - readAtNs) / 1_000_000,
                segmentLength = segLen,
            )
            _segmentTiming.value = timing
            Log.d(
                TAG,
                "MO segment sent: start=$segStart len=$segLen, asked ${timing.askedAfterMs} ms after the send, " +
                    "answered in ${timing.answeredInMs} ms",
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send MO segment: ${e.message}")
        }
    }

    private suspend fun handleMoStatus(json: JSONObject) {
        val status = json.optString("final_mo_status", "")
        val id = json.optInt("message_id", -1)
        if (_moStatus.value != MoStatus.Segmenting || !moStatusSettles(moMessageId, id)) {
            // A message this phone is not waiting for: it neither settles nor blocks the next send.
            Log.i(TAG, "MO status for message $id, not this phone's send: $status")
            return
        }
        moFinalStatus = status
        if (status == "mo_ack_received") {
            _moStatus.value = MoStatus.Complete
            Log.d(TAG, "MO complete: ACK received")
        } else {
            _moStatus.value = MoStatus.Failed
            Log.w(TAG, "MO failed: $status")
            _error.emit("MO failed: $status")
        }
        moPayload = null
        moPayloadB64 = null
    }

    /** Reset MO state after handling completion. */
    fun resetMoStatus() {
        _moStatus.value = MoStatus.Idle
        moPayload = null
        moPayloadB64 = null
        moFinalStatus = ""
    }

    // ═══════════════════════════════════════════════════════════════
    // Receive MT message (Mobile Terminated)
    // ═══════════════════════════════════════════════════════════════

    private fun handleMtNotification(json: JSONObject) {
        mtTopicId = json.optInt("topic_id", 0)
        mtMessageId = json.optInt("message_id", 0)
        val maxLen = json.optInt("message_length_max", 0)
        mtLengthMax = maxOf(0, maxLen)
        mtBuffer = ByteArray(0)
        mtReceived = 0
        Log.d(TAG, "MT incoming: id=$mtMessageId topic=$mtTopicId maxLen=$maxLen")
    }

    private fun handleMtSegment(json: JSONObject) {
        val buf = mtBuffer ?: return
        val segStart = json.optInt("segment_start", 0)
        val b64Data = json.optString("data", "")

        val decoded = try {
            jsprSegmentBytes(b64Data)
        } catch (e: Exception) {
            Log.e(TAG, "MT segment base64 decode failed: ${e.message}")
            return
        }

        if (segStart >= 0 && decoded.size <= mtLengthMax && segStart <= mtLengthMax - decoded.size) {
            val end = segStart + decoded.size
            val grown = if (buf.size < end) buf.copyOf(end) else buf
            decoded.copyInto(grown, segStart)
            mtBuffer = grown
            mtReceived += decoded.size
            Log.d(TAG, "MT segment: start=$segStart len=${decoded.size}")
        } else {
            Log.w(TAG, "MT segment out of bounds: start=$segStart len=${decoded.size} max=$mtLengthMax")
        }
    }

    private suspend fun handleMtStatus(json: JSONObject) {
        val status = json.optString("final_mt_status", "")
        if (status == "complete") {
            val buf = mtBuffer
            val received = mtReceived
            if (buf != null && received >= 2) {
                // Verify and strip CRC-16
                val payload = buf.copyOfRange(0, received - 2)
                val expectedCrc = ((buf[received - 2].toInt() and 0xFF) shl 8) or
                        (buf[received - 1].toInt() and 0xFF)
                val actualCrc = crc16(payload)

                if (actualCrc == expectedCrc) {
                    Log.d(TAG, "MT complete: ${payload.size} bytes, CRC OK")
                    _receivedMessages.emit(payload)
                } else {
                    Log.w(TAG, "MT CRC mismatch: expected=0x${expectedCrc.toString(16)} actual=0x${actualCrc.toString(16)}")
                    // Emit anyway — the application layer can decide
                    _receivedMessages.emit(payload)
                    _error.emit("MT message CRC mismatch")
                }
            }
        } else {
            Log.w(TAG, "MT failed: $status")
            _error.emit("MT failed: $status")
        }
        mtBuffer = null
        mtReceived = 0
    }

    // ═══════════════════════════════════════════════════════════════
    // Signal / Status
    // ═══════════════════════════════════════════════════════════════

    /**
     * True when the JSPR link is usable for commands. Gates silent no-ops when
     * no HC-05 is paired so disconnected state doesn't spam the error flow (MESHSAT-499).
     * Accepts Connected/Initializing/Ready because sendJspr is used during init.
     */
    private fun isWireReady(): Boolean =
        _state.value in setOf(State.Connected, State.Initializing, State.Ready)

    /**
     * Poll signal strength. Returns 0-5: the answer, or the last reading when the modem did not
     * answer (which then stays as old as it is, MESHSAT-1670). Each answered read's round trip
     * goes into [pollStats].
     */
    suspend fun pollSignal(): Int {
        if (!isWireReady()) return 0
        return try {
            val startNs = System.nanoTime()
            val resp = sendJspr("GET", "constellationState", JSONObject(), SIGNAL_TIMEOUT_MS)
            val answered = resp?.takeIf { it.has("signal_bars") }?.optInt("signal_bars", 0)
            notePoll(if (answered == null) null else (System.nanoTime() - startNs) / 1_000_000)
            if (answered == null) {
                Log.d(TAG, "Signal: no answer in ${SIGNAL_TIMEOUT_MS / 1000} s, keeping ${_signal.value}/5")
            } else {
                recordReading(answered)
            }
            signalAfterPoll(_signal.value, answered)
        } catch (e: Exception) {
            _error.emit("9704 signal poll failed: ${e.message}")
            _signal.value
        }
    }

    private fun notePoll(roundTripMs: Long?) {
        val stats = synchronized(pollRoundTrips) {
            if (roundTripMs == null) {
                pollsMissed++
            } else {
                pollsAnswered++
                pollRoundTrips.addLast(roundTripMs)
                while (pollRoundTrips.size > SignalPollStats.KEEP) pollRoundTrips.removeFirst()
            }
            SignalPollStats.of(pollRoundTrips.toList(), pollsAnswered, pollsMissed)
        }
        _pollStats.value = stats
        if (roundTripMs != null) Log.d(TAG, "Signal read round trip $roundTripMs ms (${stats.describe()})")
    }

    /** Get SIM status. */
    suspend fun getSimStatus(): JSONObject? {
        if (!isWireReady()) return null
        return try {
            sendJspr("GET", "simStatus", JSONObject())
        } catch (e: Exception) {
            _error.emit("9704 simStatus failed: ${e.message}")
            null
        }
    }

    /** Get paired Bluetooth devices that might be HC-05 modules for 9704. */
    fun getPairedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.filter { device ->
            val name = device.name?.lowercase() ?: ""
            name.contains("hc-05") || name.contains("hc-06") ||
                    name.contains("hc05") || name.contains("hc06") ||
                    name.contains("iridium") || name.contains("rockblock") ||
                    name.contains("9704") || name.contains("imt")
        }?.toList() ?: emptyList()
    }
}
