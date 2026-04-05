package com.cubeos.meshsat.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        private const val SIGNAL_TIMEOUT_MS = 2_000L
        private const val MO_TIMEOUT_MS = 120_000L
        private const val MAX_SEGMENT_SIZE = 1446
        private const val RAW_TOPIC = 244

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

    @Volatile var lastAddress: String? = null
        private set

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _signal = MutableStateFlow(0)
    val signal: StateFlow<Int> = _signal

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
    private var moMessageId: Int = 0
    private var moTopicId: Int = RAW_TOPIC
    private var moRequestRef: Int = 0
    private var nextRequestRef: Int = 1

    enum class MoStatus { Idle, Pending, Segmenting, Complete, Failed }
    private val _moStatus = MutableStateFlow(MoStatus.Idle)
    val moStatus: StateFlow<MoStatus> = _moStatus
    private var moFinalStatus: String = ""

    // --- MT state ---
    private var mtBuffer: ByteArray? = null
    private var mtReceived: Int = 0
    private var mtMessageId: Int = 0
    private var mtTopicId: Int = 0

    // --- Poll loop ---
    @Volatile private var polling = false

    // Serializes all serial I/O (sendJspr + poll loop).
    private val serialLock = Any()

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
                _state.value = State.Connected

                initialize()
            } catch (e: IOException) {
                _state.value = State.Disconnected
                _error.emit("9704 SPP connect failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        polling = false
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
        inputStream = null
        outputStream = null
        _state.value = State.Disconnected
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
                _modemInfo.value = ModemInfo(
                    imei = hwResp.optString("imei", ""),
                    serial = hwResp.optString("serial_number", ""),
                    firmwareVersion = hwResp.optString("hw_version", ""),
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

            drainInput()

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

                // Unsolicited — dispatch and keep reading
                if (code == 299) {
                    scope.launch { handleUnsolicitedResponse(code, respTarget, json) }
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
                                scope.launch { handleUnsolicitedResponse(resp.first, resp.second, resp.third) }
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
                delay(50)
            }
        }
    }

    private suspend fun handleUnsolicitedResponse(code: Int, target: String, json: JSONObject) {
        when (target) {
            "constellationState" -> {
                _signal.value = json.optInt("signal_bars", 0)
                Log.d(TAG, "Signal: ${_signal.value}/5")
            }
            "messageOriginateSegment" -> {
                handleMoSegmentRequest(json)
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

        val deadline = System.currentTimeMillis() + MO_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (_moStatus.value) {
                MoStatus.Complete -> {
                    val status = moFinalStatus
                    resetMoStatus()
                    return status
                }
                MoStatus.Failed -> {
                    val status = moFinalStatus
                    resetMoStatus()
                    return status
                }
                else -> delay(100)
            }
        }
        resetMoStatus()
        return null
    }

    /**
     * Handle modem requesting a segment of the MO payload.
     * Modem sends: 299 messageOriginateSegment {segment_start, segment_length, ...}
     * Host responds: PUT messageOriginateSegment {data: base64, ...}
     */
    private fun handleMoSegmentRequest(json: JSONObject) {
        val payload = moPayload ?: return
        val segStart = json.optInt("segment_start", 0)
        val segLen = json.optInt("segment_length", 0)

        if (segStart + segLen > payload.size) {
            Log.w(TAG, "MO segment request out of bounds: start=$segStart len=$segLen payload=${payload.size}")
            return
        }

        val segment = payload.copyOfRange(segStart, segStart + segLen)
        val b64 = Base64.encodeToString(segment, Base64.NO_WRAP)

        val resp = JSONObject()
            .put("topic_id", moTopicId)
            .put("message_id", moMessageId)
            .put("segment_length", segLen)
            .put("segment_start", segStart)
            .put("data", b64)

        try {
            synchronized(serialLock) {
                val os = outputStream ?: return
                val frame = "PUT messageOriginateSegment ${spacedJson(resp.toString())}\r"
                os.write(frame.toByteArray(Charsets.US_ASCII))
                os.flush()
            }
            Log.d(TAG, "MO segment sent: start=$segStart len=$segLen")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send MO segment: ${e.message}")
        }
    }

    private suspend fun handleMoStatus(json: JSONObject) {
        val status = json.optString("final_mo_status", "")
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
    }

    /** Reset MO state after handling completion. */
    fun resetMoStatus() {
        _moStatus.value = MoStatus.Idle
        moPayload = null
        moFinalStatus = ""
    }

    // ═══════════════════════════════════════════════════════════════
    // Receive MT message (Mobile Terminated)
    // ═══════════════════════════════════════════════════════════════

    private fun handleMtNotification(json: JSONObject) {
        mtTopicId = json.optInt("topic_id", 0)
        mtMessageId = json.optInt("message_id", 0)
        val maxLen = json.optInt("message_length_max", 0)
        mtBuffer = ByteArray(maxLen)
        mtReceived = 0
        Log.d(TAG, "MT incoming: id=$mtMessageId topic=$mtTopicId maxLen=$maxLen")
    }

    private fun handleMtSegment(json: JSONObject) {
        val buf = mtBuffer ?: return
        val segStart = json.optInt("segment_start", 0)
        val b64Data = json.optString("data", "")

        val decoded = try {
            Base64.decode(b64Data, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "MT segment base64 decode failed: ${e.message}")
            return
        }

        if (segStart + decoded.size <= buf.size) {
            decoded.copyInto(buf, segStart)
            mtReceived += decoded.size
            Log.d(TAG, "MT segment: start=$segStart len=${decoded.size}")
        } else {
            Log.w(TAG, "MT segment out of bounds: start=$segStart len=${decoded.size} buf=${buf.size}")
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

    /** Poll signal strength. Returns 0-5. */
    suspend fun pollSignal(): Int {
        if (!isWireReady()) return 0
        return try {
            val resp = sendJspr("GET", "constellationState", JSONObject(), SIGNAL_TIMEOUT_MS)
            val bars = resp?.optInt("signal_bars", 0) ?: 0
            _signal.value = bars
            bars
        } catch (e: Exception) {
            _error.emit("9704 signal poll failed: ${e.message}")
            0
        }
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
