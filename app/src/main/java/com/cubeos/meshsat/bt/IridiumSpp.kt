package com.cubeos.meshsat.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
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
 * Iridium 9603N AT command driver over Bluetooth SPP (HC-05/HC-06).
 *
 * Connects to the HC-05 module via Bluetooth Classic Serial Port Profile,
 * which bridges to the RockBLOCK 9603N's serial port at 19200 baud.
 *
 * Key AT commands:
 * - AT        → OK (alive check)
 * - AT+CSQ    → +CSQ:N (signal strength 0-5)
 * - AT+SBDWB  → write binary to MO buffer (340 bytes max)
 * - AT+SBDIX  → initiate SBD session (send MO, receive MT) — 10-60s
 * - AT+SBDSX  → status check (free, no RF)
 * - AT+SBDRT  → read text from MT buffer
 * - AT+CGMI   → manufacturer
 * - AT+CGMM   → model
 * - AT+CGSN   → IMEI
 */
@SuppressLint("MissingPermission")
class IridiumSpp(private val context: Context) {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val AT_TIMEOUT_MS = 5_000L
        private const val SBDIX_TIMEOUT_MS = 65_000L
        private const val MO_MAX_SIZE = 340
    }

    enum class State { Disconnected, Connecting, Connected }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _signal = MutableStateFlow(0)
    val signal: StateFlow<Int> = _signal

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error

    private val _modemInfo = MutableStateFlow(ModemInfo())
    val modemInfo: StateFlow<ModemInfo> = _modemInfo

    data class ModemInfo(
        val manufacturer: String = "",
        val model: String = "",
        val imei: String = "",
    )

    data class SbdixResult(
        val moStatus: Int,
        val moMsn: Int,
        val mtStatus: Int,
        val mtMsn: Int,
        val mtLength: Int,
        val mtQueued: Int,
    ) {
        val moSuccess get() = moStatus in 0..4
        val mtAvailable get() = mtStatus == 1
    }

    data class SbdsxResult(
        val moFlag: Boolean,
        val moMsn: Int,
        val mtFlag: Boolean,
        val mtMsn: Int,
        val raFlag: Boolean,
        val msgWaiting: Int,
    )

    // --- Connect / Disconnect ---

    fun connect(address: String) {
        val device = adapter?.getRemoteDevice(address) ?: run {
            scope.launch { _error.emit("Invalid Bluetooth address: $address") }
            return
        }
        connect(device)
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

                // Probe modem
                probeModem()
            } catch (e: IOException) {
                _state.value = State.Disconnected
                _error.emit("SPP connect failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
        inputStream = null
        outputStream = null
        _state.value = State.Disconnected
    }

    // --- AT Commands ---

    @Synchronized
    private fun sendAT(command: String, timeoutMs: Long = AT_TIMEOUT_MS): String {
        val os = outputStream ?: throw IOException("Not connected")
        val input = inputStream ?: throw IOException("Not connected")

        // Drain any pending data
        while (input.available() > 0) input.read()

        os.write("$command\r".toByteArray(Charsets.US_ASCII))
        os.flush()

        // Read response until OK, ERROR, or timeout
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                buf.append(b.toChar())

                val s = buf.toString()
                if (s.contains("OK\r") || s.contains("ERROR\r") || s.contains("READY\r")) {
                    break
                }
            } else {
                Thread.sleep(10)
            }
        }

        return buf.toString().trim()
    }

    private suspend fun probeModem() {
        try {
            sendAT("ATE0") // disable echo

            val mfr = sendAT("AT+CGMI").lines()
                .firstOrNull { !it.startsWith("AT") && it != "OK" && it.isNotBlank() } ?: ""
            val model = sendAT("AT+CGMM").lines()
                .firstOrNull { !it.startsWith("AT") && it != "OK" && it.isNotBlank() } ?: ""
            val imei = sendAT("AT+CGSN").lines()
                .firstOrNull { !it.startsWith("AT") && it != "OK" && it.isNotBlank() } ?: ""

            _modemInfo.value = ModemInfo(mfr.trim(), model.trim(), imei.trim())
            pollSignal()
        } catch (e: Exception) {
            _error.emit("Modem probe failed: ${e.message}")
        }
    }

    /** Poll signal strength (AT+CSQ). Returns 0-5. */
    suspend fun pollSignal(): Int {
        return try {
            val resp = sendAT("AT+CSQ")
            // Response: +CSQ:3 or +CSQF:3
            val match = Regex("[+]CS(?:Q|QF):\\s*(\\d)").find(resp)
            val sig = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            _signal.value = sig
            sig
        } catch (e: Exception) {
            _error.emit("Signal poll failed: ${e.message}")
            0
        }
    }

    /** Check SBD status (AT+SBDSX) — free, no RF needed. */
    suspend fun sbdStatus(): SbdsxResult? {
        return try {
            val resp = sendAT("AT+SBDSX")
            // +SBDSX: 0, 5, 0, 5, 0, 0
            val match = Regex("[+]SBDSX:\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)")
                .find(resp) ?: return null
            val vals = match.groupValues.drop(1).map { it.toInt() }
            SbdsxResult(
                moFlag = vals[0] != 0,
                moMsn = vals[1],
                mtFlag = vals[2] != 0,
                mtMsn = vals[3],
                raFlag = vals[4] != 0,
                msgWaiting = vals[5],
            )
        } catch (e: Exception) {
            _error.emit("SBDSX failed: ${e.message}")
            null
        }
    }

    /** Write binary data to MO buffer (AT+SBDWB). Max 340 bytes. */
    suspend fun writeMoBuffer(data: ByteArray): Boolean {
        if (data.size > MO_MAX_SIZE) {
            _error.emit("MO payload too large: ${data.size} > $MO_MAX_SIZE")
            return false
        }

        return try {
            val resp = sendAT("AT+SBDWB=${data.size}")
            if (!resp.contains("READY")) {
                _error.emit("SBDWB not ready: $resp")
                return false
            }

            // Write payload + 2-byte checksum
            val os = outputStream ?: throw IOException("Not connected")
            val checksum = data.sumOf { it.toInt() and 0xFF }
            os.write(data)
            os.write((checksum shr 8) and 0xFF)
            os.write(checksum and 0xFF)
            os.flush()

            // Wait for 0\r\n or error
            val result = readUntilOkOrTimeout(AT_TIMEOUT_MS)
            result.contains("0")
        } catch (e: Exception) {
            _error.emit("SBDWB failed: ${e.message}")
            false
        }
    }

    /** Initiate SBD session (AT+SBDIX). Blocks 10-60s. Returns result or null. */
    suspend fun sbdix(): SbdixResult? {
        return try {
            val resp = sendAT("AT+SBDIX", SBDIX_TIMEOUT_MS)
            // +SBDIX: 0, 12, 0, 0, 0, 0
            val match = Regex("[+]SBDIX:\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)")
                .find(resp) ?: return null
            val vals = match.groupValues.drop(1).map { it.toInt() }
            SbdixResult(
                moStatus = vals[0],
                moMsn = vals[1],
                mtStatus = vals[2],
                mtMsn = vals[3],
                mtLength = vals[4],
                mtQueued = vals[5],
            )
        } catch (e: Exception) {
            _error.emit("SBDIX failed: ${e.message}")
            null
        }
    }

    /** Read text from MT buffer (AT+SBDRT). */
    suspend fun readMtBuffer(): String? {
        return try {
            val resp = sendAT("AT+SBDRT")
            // +SBDRT:\r\nMessage text\r\nOK
            val match = Regex("[+]SBDRT:\\s*\r?\n(.+)", RegexOption.DOT_MATCHES_ALL)
                .find(resp)
            match?.groupValues?.get(1)?.trim()?.removeSuffix("OK")?.trim()
        } catch (e: Exception) {
            _error.emit("SBDRT failed: ${e.message}")
            null
        }
    }

    private fun readUntilOkOrTimeout(timeoutMs: Long): String {
        val input = inputStream ?: return ""
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                buf.append(b.toChar())
                if (buf.contains("OK") || buf.contains("ERROR")) break
            } else {
                Thread.sleep(10)
            }
        }
        return buf.toString()
    }

    /** Get paired Bluetooth devices that might be HC-05/06 modules. */
    fun getPairedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.filter { device ->
            val name = device.name?.lowercase() ?: ""
            name.contains("hc-05") || name.contains("hc-06") ||
                    name.contains("hc05") || name.contains("hc06") ||
                    name.contains("iridium") || name.contains("rockblock")
        }?.toList() ?: emptyList()
    }
}
