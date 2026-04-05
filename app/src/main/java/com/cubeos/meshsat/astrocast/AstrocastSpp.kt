package com.cubeos.meshsat.astrocast

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Astrocast Astronode S driver over Bluetooth SPP (HC-05/HC-06).
 *
 * Connects to an HC-05 module bridging UART to the Astronode S module.
 * Uses the binary serial protocol (not AT commands): framed with STX/ETX,
 * hex-encoded, CRC-16 protected.
 *
 * Max uplink payload: 160 bytes. LEO constellation — store-and-forward.
 *
 * Reference: https://github.com/Astrocast/astronode-c-library
 */
@SuppressLint("MissingPermission")
class AstrocastSpp(private val context: Context) {

    companion object {
        private const val TAG = "AstrocastSpp"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RESPONSE_TIMEOUT_MS = 3000L
    }

    enum class State { Disconnected, Connecting, Connected, Error }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _inboundCommands = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    /** Downlink commands from satellite. */
    val inboundCommands: SharedFlow<ByteArray> = _inboundCommands

    /** Device GUID (populated after connect). */
    var guid: String = ""
        private set

    /** Device serial number (populated after connect). */
    var serialNumber: String = ""
        private set

    /** Pending message counter (from last payload write ACK). */
    var lastAckCounter: Int = -1
        private set

    /** Next predicted satellite pass (epoch seconds, 0 if unknown). */
    var nextPassEpoch: Long = 0
        private set

    /**
     * Connect to the HC-05 Bluetooth module bridging the Astronode S.
     */
    fun connect(address: String) {
        scope.launch {
            _state.value = State.Connecting
            try {
                val device: BluetoothDevice = adapter?.getRemoteDevice(address)
                    ?: throw IOException("Bluetooth adapter unavailable")

                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                sock.connect()
                socket = sock
                inputStream = sock.inputStream
                outputStream = sock.outputStream
                _state.value = State.Connected
                Log.i(TAG, "Connected to Astronode via SPP: $address")

                // Read device info
                initDevice()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                _state.value = State.Error
                disconnect()
            }
        }
    }

    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        if (_state.value != State.Error) _state.value = State.Disconnected
    }

    private var nextFragMsgID = 0
    private val reassemblyBuffer = AstrocastProtocol.ReassemblyBuffer()

    /**
     * Queue an uplink message. Supports auto-fragmentation for messages > 160 bytes
     * (up to 636 bytes via 4 fragments). Returns the ACK counter or -1 on failure.
     */
    suspend fun sendPayload(counter: Int, data: ByteArray): Int {
        val fragments = AstrocastProtocol.fragmentMessage(nextFragMsgID, data)
        if (fragments != null) {
            // Fragmented send — each fragment is a separate uplink
            nextFragMsgID = (nextFragMsgID + 1) and 0x0F
            var lastCounter = -1
            for ((i, frag) in fragments.withIndex()) {
                val frame = AstrocastProtocol.payloadWrite(counter + i, frag)
                val rsp = sendAndReceive(frame) ?: return -1
                if (rsp.isError) {
                    Log.w(TAG, "payload_w error (frag $i/${fragments.size}): ${AstrocastProtocol.ErrorCode.name(rsp.errorCode)}")
                    return -1
                }
                lastCounter = AstrocastProtocol.parsePayloadWriteCounter(rsp)
                Log.d(TAG, "Fragment $i/${fragments.size} queued (${frag.size}B)")
            }
            lastAckCounter = lastCounter
            return lastAckCounter
        }

        // Single uplink — no fragmentation needed
        val frame = AstrocastProtocol.payloadWrite(counter, data)
        val rsp = sendAndReceive(frame) ?: return -1
        if (rsp.isError) {
            Log.w(TAG, "payload_w error: ${AstrocastProtocol.ErrorCode.name(rsp.errorCode)}")
            return -1
        }
        lastAckCounter = AstrocastProtocol.parsePayloadWriteCounter(rsp)
        return lastAckCounter
    }

    /**
     * Process an inbound payload that may be fragmented.
     * Returns the complete message (reassembled if fragmented), or null if more fragments needed.
     */
    fun processInboundPayload(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val header = AstrocastProtocol.decodeFragmentHeader(data[0])
        if (header.fragTotal == 1 && header.fragNum == 0 && header.msgID == 0) {
            // Could be unfragmented — return as-is (no header stripping for plain messages)
            return data
        }
        // Fragmented — strip header and feed to reassembly buffer
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else byteArrayOf()
        return reassemblyBuffer.addFragment(header, payload, System.currentTimeMillis() / 1000)
    }

    /** Dequeue the oldest sent message. */
    suspend fun dequeuePayload(): Boolean {
        val rsp = sendAndReceive(AstrocastProtocol.payloadDequeue()) ?: return false
        return !rsp.isError
    }

    /** Clear all queued messages. */
    suspend fun clearPayloads(): Boolean {
        val rsp = sendAndReceive(AstrocastProtocol.payloadClear()) ?: return false
        return !rsp.isError
    }

    /** Set device geolocation (decimal degrees). */
    suspend fun setGeolocation(lat: Double, lon: Double): Boolean {
        val latMicro = (lat * 1_000_000).toInt()
        val lonMicro = (lon * 1_000_000).toInt()
        val rsp = sendAndReceive(AstrocastProtocol.geolocationWrite(latMicro, lonMicro)) ?: return false
        return !rsp.isError
    }

    /** Poll events and handle SAK + downlink commands. Returns event flags. */
    suspend fun pollEvents(): Int {
        val rsp = sendAndReceive(AstrocastProtocol.eventRead()) ?: return 0
        if (rsp.isError) return 0
        val flags = AstrocastProtocol.parseEventFlags(rsp)

        // Handle satellite acknowledgment
        if (flags and AstrocastProtocol.EventFlag.SAK_AVAILABLE != 0) {
            val sakRsp = sendAndReceive(AstrocastProtocol.sakRead())
            if (sakRsp != null && !sakRsp.isError) {
                val counter = AstrocastProtocol.parseSakCounter(sakRsp)
                Log.i(TAG, "SAK received for message $counter")
                // Dequeue the acknowledged message
                dequeuePayload()
            }
            sendAndReceive(AstrocastProtocol.sakClear())
        }

        // Handle downlink command
        if (flags and AstrocastProtocol.EventFlag.CMD_AVAILABLE != 0) {
            val cmdRsp = sendAndReceive(AstrocastProtocol.commandRead())
            if (cmdRsp != null && !cmdRsp.isError) {
                val (_, cmdData) = AstrocastProtocol.parseCommand(cmdRsp)
                if (cmdData.isNotEmpty()) {
                    _inboundCommands.tryEmit(cmdData)
                    Log.i(TAG, "Downlink command received: ${cmdData.size} bytes")
                }
            }
            sendAndReceive(AstrocastProtocol.commandClear())
        }

        // Handle reset notification
        if (flags and AstrocastProtocol.EventFlag.RESET_EVENT != 0) {
            Log.w(TAG, "Module reset detected")
            sendAndReceive(AstrocastProtocol.resetClear())
        }

        return flags
    }

    /** Read next satellite pass prediction. Returns epoch seconds or 0. */
    suspend fun readNextPass(): Long {
        val rsp = sendAndReceive(AstrocastProtocol.ephemerisRead()) ?: return 0
        if (rsp.isError) return 0
        nextPassEpoch = AstrocastProtocol.parseNextPass(rsp)
        return nextPassEpoch
    }

    /** Read module RTC. Returns epoch seconds. */
    suspend fun readRtc(): Long {
        val rsp = sendAndReceive(AstrocastProtocol.rtcRead()) ?: return 0
        if (rsp.isError) return 0
        return AstrocastProtocol.parseRtcEpoch(rsp)
    }

    // --- Internal ---

    private suspend fun initDevice() {
        try {
            // Clear any pending reset notification
            sendAndReceive(AstrocastProtocol.resetClear())

            // Read device info
            val guidRsp = sendAndReceive(AstrocastProtocol.guidRead())
            if (guidRsp != null && !guidRsp.isError) {
                guid = AstrocastProtocol.parseGuid(guidRsp)
                Log.i(TAG, "Astronode GUID: $guid")
            }

            val snRsp = sendAndReceive(AstrocastProtocol.serialNumberRead())
            if (snRsp != null && !snRsp.isError) {
                serialNumber = AstrocastProtocol.parseSerialNumber(snRsp)
                Log.i(TAG, "Astronode S/N: $serialNumber")
            }

            // Configure: enable SAK, geolocation, ephemeris; mask SAK+reset+cmd events
            sendAndReceive(AstrocastProtocol.configurationWrite(
                satAck = true, geolocation = true, ephemeris = true, deepSleep = false,
                satAckMask = true, resetMask = true, cmdMask = true, busyMask = false,
            ))
            sendAndReceive(AstrocastProtocol.configurationSave())

            Log.i(TAG, "Astronode S initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Device init error: ${e.message}")
        }
    }

    private suspend fun sendAndReceive(frame: ByteArray): AstrocastProtocol.Response? {
        // Silent no-op when not connected — state, not error (MESHSAT-499).
        // Covers all higher-level callers (sendPayload, pollEvents, dequeuePayload,
        // clearPayloads, setGeolocation, readNextPass, readRtc, initDevice).
        if (_state.value != State.Connected) return null
        val os = outputStream ?: return null
        val iStream = inputStream ?: return null

        return try {
            os.write(frame)
            os.flush()

            withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
                readResponse(iStream)
            }
        } catch (e: IOException) {
            Log.w(TAG, "I/O error: ${e.message}")
            _state.value = State.Error
            null
        }
    }

    /**
     * Read a complete response frame from the input stream.
     * Scans for STX, reads until ETX, then decodes.
     */
    private suspend fun readResponse(input: InputStream): AstrocastProtocol.Response? {
        val buf = ByteArray(512)
        var pos = 0
        var foundStx = false

        while (true) {
            val available = input.available()
            if (available <= 0) {
                delay(10)
                continue
            }

            val b = input.read()
            if (b < 0) return null

            if (!foundStx) {
                if (b.toByte() == AstrocastProtocol.STX) {
                    buf[0] = AstrocastProtocol.STX
                    pos = 1
                    foundStx = true
                }
                continue
            }

            if (pos >= buf.size) return null // overflow

            buf[pos++] = b.toByte()

            if (b.toByte() == AstrocastProtocol.ETX) {
                return AstrocastProtocol.decodeFrame(buf.copyOfRange(0, pos))
            }
        }
    }
}
