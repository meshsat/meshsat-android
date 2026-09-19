package net.meshsat.android.bt

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
import net.meshsat.android.ble.LineWatcher
import net.meshsat.android.ble.ModemLink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Iridium 9603N AT command driver.
 *
 * Since MESHSAT-1236 the modem is reached through the MeshSat node's BLE Iridium pipe
 * ([net.meshsat.android.ble.IridiumBlePipe]), which replaced the HC-05 SPP bridge. The
 * driver only needs a byte link ([ModemLink]): [attach] it when the node gives this phone
 * the modem, [detach] when it takes it back.
 *
 * What costs money is decided here, once, for every caller: each AT+SBDIX that reaches the
 * gateway is billed, even with an empty MO buffer. After status 32 or 36 no SBDIX is sent
 * for [SBDIX_HOLD_MS]; after a successful MO the buffer is cleared, so a later mailbox
 * check does not send it again. An unsolicited "SBDRING" line (a waiting MT message) is
 * reported on [ringAlerts]; checking the mailbox is the caller's decision, never a timer's.
 *
 * Key AT commands:
 * - AT&K0     → flow control off (3-wire link; not every RockBLOCK stores it)
 * - AT+CSQ    → +CSQ:N (signal strength 0-5), free
 * - AT+SBDWB  → write binary to MO buffer (340 bytes max)
 * - AT+SBDIX  → SBD session (send MO, receive MT), 11-90 s, billed
 * - AT+SBDSX  → status check, free
 * - AT+SBDRB  → read MT buffer, binary: [len 2B BE][data][checksum 2B BE]
 * - AT+SBDD0  → clear MO buffer
 */
class IridiumSpp(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        private const val AT_TIMEOUT_MS = 5_000L
        private const val CSQ_TIMEOUT_MS = 12_000L
        private const val SBDIX_TIMEOUT_MS = 95_000L
        const val SBDIX_HOLD_MS = 180_000L
        const val MO_MAX_SIZE = 340
        const val MT_MAX_SIZE = 270
        /** How often a modem that is still powering up is asked again. */
        const val WAKE_RETRY_MS = 2_000L
        /** Silent this long, the modem is reported as not answering; the checks go on, slower. */
        const val WAKE_GRACE_MS = 60_000L
        const val SILENT_RETRY_MS = 30_000L
        private const val TAG = "IridiumSpp"

        /**
         * MO statuses where the message may have reached the gateway although the modem reports a
         * failure: the session was cut after the upload (9602/9603 AT manual, +SBDIX). Seen on
         * flaneur on 19 Sep: Rock7 delivered "tst" while the app recorded a failure.
         */
        val MO_MAYBE_SENT = setOf(10, 13, 17, 18, 19)

        /** What an +SBDIX MO status means, in plain words. */
        fun moStatusText(code: Int): String = when (code) {
            in 0..4 -> "sent"
            10 -> "the gateway did not finish the call in time"
            11 -> "the modem's outgoing queue is full"
            12 -> "the message has too many segments"
            13 -> "the session did not complete"
            14 -> "the segment size is invalid"
            15 -> "the gateway denied access"
            16 -> "the modem is locked"
            17 -> "the gateway did not answer"
            18 -> "the radio link dropped"
            19 -> "the link failed"
            32 -> "no network service"
            33 -> "antenna fault"
            34 -> "the radio is switched off"
            35 -> "the modem is busy"
            36 -> "the gateway asked to try again later"
            37 -> "satellite messaging is paused by the network"
            38 -> "the network is limiting traffic"
            else -> "failed"
        }
    }

    enum class State { Disconnected, Connecting, Connected }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var link: ModemLink? = null
    private val inputStream: InputStream? get() = link?.input
    private val outputStream: OutputStream? get() = link?.output

    /** No SBDIX before this time: set after status 32/36. */
    @Volatile private var sbdixHeldUntil = 0L

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _signal = MutableStateFlow(0)
    val signal: StateFlow<Int> = _signal

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error

    private val _modemInfo = MutableStateFlow(ModemInfo())
    val modemInfo: StateFlow<ModemInfo> = _modemInfo

    private val _modemSilent = MutableStateFlow(false)

    /** The node's pipe is ours but no modem has answered AT for [WAKE_GRACE_MS]: none is fitted, or it has no power. */
    val modemSilent: StateFlow<Boolean> = _modemSilent

    /**
     * Where a message brought in by any satellite session goes, e.g. to be stored and shown. Every
     * session downloads one waiting message into the modem, whatever started it, and the modem keeps
     * only one: read later, it is overwritten by the next. On 19 Sep two messages from Rock7 were
     * lost that way while messages were being sent.
     */
    @Volatile var mtSink: (suspend (ByteArray) -> Unit)? = null

    private val _ringAlerts = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** The modem reported a waiting MT message (unsolicited SBDRING). */
    val ringAlerts: SharedFlow<Unit> = _ringAlerts

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
        /** The message this session brought in, already read from the modem, or null. */
        val mt: ByteArray? = null,
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

    // --- Link ---

    /** Use [newLink] and probe the modem; the state is Connected once the probe ran. */
    fun attach(newLink: ModemLink) {
        detach()
        link = newLink
        val watcher = LineWatcher("SBDRING") { _ringAlerts.tryEmit(Unit) }
        newLink.setReceiver { watcher.feed(it) }
        _state.value = State.Connecting
        scope.launch {
            if (!awaitModem(newLink)) return@launch
            probeModem()
            if (link === newLink) _state.value = State.Connected
        }
    }

    /**
     * Repeat the probe's first command until the modem answers. A node that has just switched
     * its modem on gives it about 10 s before it answers AT (the T-Beam node), and a node without
     * one never does: until then the state stays Connecting, never Connected. False once
     * [forLink] is no longer in use.
     */
    private suspend fun awaitModem(forLink: ModemLink): Boolean {
        val start = clock()
        while (link === forLink) {
            // Flow control off first: the node wires TX/RX/GND only.
            val resp = runCatching { sendAT("AT&K0") }.getOrDefault("")
            if (resp.contains("OK") || resp.contains("ERROR")) {
                _modemSilent.value = false
                return link === forLink
            }
            val silent = clock() - start >= WAKE_GRACE_MS
            if (silent && !_modemSilent.value) {
                _modemSilent.value = true
                _error.emit("The node's modem does not answer AT; still trying")
            }
            val until = clock() + if (silent) SILENT_RETRY_MS else WAKE_RETRY_MS
            while (clock() < until && link === forLink) delay(20)
        }
        return false
    }

    /** Stop using the link; the node keeps the modem powered. */
    fun detach() {
        link?.setReceiver(null)
        link = null
        _modemSilent.value = false
        _state.value = State.Disconnected
    }

    /** Kept for callers of the old SPP driver: the same as [detach]. */
    fun disconnect() = detach()

    // --- AT Commands ---

    @Synchronized
    private fun sendAT(command: String, timeoutMs: Long = AT_TIMEOUT_MS): String {
        val os = outputStream ?: throw IOException("Not connected")
        val input = inputStream ?: throw IOException("Not connected")

        // Drain anything pending; an unsolicited SBDRING in it was already seen by the watcher.
        while (input.available() > 0) input.read()

        os.write("$command\r".toByteArray(Charsets.US_ASCII))
        os.flush()

        // Read response until OK, ERROR, READY or timeout. The modem may echo the command.
        val buf = StringBuilder()
        val deadline = clock() + timeoutMs

        while (clock() < deadline) {
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

    /** The first line of [resp] that is neither the echoed command nor OK. */
    private fun infoLine(resp: String): String =
        resp.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("AT") && it != "OK" } ?: ""

    private suspend fun probeModem() {
        try {
            // AT&K0 (flow control off) already went out in awaitModem.
            sendAT("ATE0")
            // Unsolicited SBDRING when an MT message waits: the node has no RI wire.
            sendAT("AT+SBDMTA=1")

            val mfr = infoLine(sendAT("AT+CGMI"))
            val model = infoLine(sendAT("AT+CGMM"))
            val imei = infoLine(sendAT("AT+CGSN"))

            _modemInfo.value = ModemInfo(mfr, model, imei)
            pollSignal()
        } catch (e: Exception) {
            _error.emit("Modem probe failed: ${e.message}")
        }
    }

    private fun isWireReady(): Boolean = _state.value == State.Connected

    /** Poll signal strength (AT+CSQ, free). Returns 0-5. */
    suspend fun pollSignal(): Int {
        if (link == null) return 0
        return try {
            val resp = sendAT("AT+CSQ", CSQ_TIMEOUT_MS)
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
        if (!isWireReady()) return null
        return try {
            val resp = sendAT("AT+SBDSX")
            val match = Regex("[+]SBDSX:\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(-?\\d+),\\s*(\\d+),\\s*(\\d+)")
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
        if (!isWireReady()) return false
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

            // Payload + 2-byte checksum, one write so it leaves in as few chunks as the link allows.
            val os = outputStream ?: throw IOException("Not connected")
            val checksum = data.sumOf { it.toInt() and 0xFF }
            os.write(data + byteArrayOf(((checksum shr 8) and 0xFF).toByte(), (checksum and 0xFF).toByte()))
            os.flush()

            // "0" is success; 1 timeout, 2 bad checksum, 3 wrong size.
            val result = readUntilOkOrTimeout(AT_TIMEOUT_MS)
            val code = Regex("^\\s*(\\d)\\s*$", RegexOption.MULTILINE).find(result)?.groupValues?.get(1)
            if (code != "0") _error.emit("SBDWB rejected: ${code ?: result.trim()}")
            code == "0"
        } catch (e: Exception) {
            _error.emit("SBDWB failed: ${e.message}")
            false
        }
    }

    /** Milliseconds until the next SBDIX may be sent, 0 if it may be sent now. */
    fun sbdixHoldRemainingMs(): Long = (sbdixHeldUntil - clock()).coerceAtLeast(0)

    /**
     * SBD session (AT+SBDIX), billed. Refused while held after status 32/36. On success the
     * MO buffer is cleared.
     */
    suspend fun sbdix(deliverMt: Boolean = true): SbdixResult? {
        if (!isWireReady()) return null
        val hold = sbdixHoldRemainingMs()
        if (hold > 0) {
            _error.emit("SBDIX held for ${hold / 1000} s after a failed session")
            return null
        }
        return try {
            val resp = sendAT("AT+SBDIX", SBDIX_TIMEOUT_MS)
            val match = Regex("[+]SBDIX:\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)")
                .find(resp)
            if (match == null) {
                // Never silent: a session whose answer cannot be read may still have sent.
                val raw = resp.replace("\r", "\\r").replace("\n", "\\n").take(120)
                Log.w(TAG, "SBDIX answer not readable: $raw")
                _error.emit("The modem's answer to a satellite session could not be read")
                return null
            }
            val vals = match.groupValues.drop(1).map { it.toInt() }
            val result = SbdixResult(
                moStatus = vals[0],
                moMsn = vals[1],
                mtStatus = vals[2],
                mtMsn = vals[3],
                mtLength = vals[4],
                mtQueued = vals[5],
            )
            Log.i(
                TAG,
                "SBDIX: MO status ${result.moStatus} (${moStatusText(result.moStatus)}), MOMSN ${result.moMsn}, " +
                    "MT status ${result.mtStatus}, ${result.mtQueued} waiting",
            )
            when {
                result.moStatus == 32 || result.moStatus == 36 -> sbdixHeldUntil = clock() + SBDIX_HOLD_MS
                result.moSuccess -> clearMoBuffer()
            }
            // A message came in with this session: read it now, before another session overwrites it.
            val mt = if (result.mtAvailable) readMtBinary()?.takeIf { it.isNotEmpty() } else null
            if (mt != null) {
                Log.i(TAG, "SBDIX brought a message in: ${mt.size} bytes, MTMSN ${result.mtMsn}")
                if (deliverMt) {
                    mtSink?.let { sink ->
                        try {
                            sink(mt)
                        } catch (e: Exception) {
                            Log.w(TAG, "Storing the message that came in failed: ${e.message}")
                        }
                    }
                }
            }
            result.copy(mt = mt)
        } catch (e: Exception) {
            _error.emit("SBDIX failed: ${e.message}")
            null
        }
    }

    /** Clear the MO buffer (AT+SBDD0), so a later session does not send it again. */
    suspend fun clearMoBuffer(): Boolean {
        if (!isWireReady()) return false
        return try {
            infoLine(sendAT("AT+SBDD0")) == "0"
        } catch (e: Exception) {
            _error.emit("SBDD0 failed: ${e.message}")
            false
        }
    }

    /**
     * Read the MT buffer (AT+SBDRB), binary-safe. Returns the message, an empty array when
     * the buffer is empty, or null on a transport or checksum error.
     */
    suspend fun readMtBinary(): ByteArray? {
        if (!isWireReady()) return null
        return try {
            readMtBinaryLocked()
        } catch (e: Exception) {
            _error.emit("SBDRB failed: ${e.message}")
            null
        }
    }

    @Synchronized
    private fun readMtBinaryLocked(): ByteArray? {
        val os = outputStream ?: throw IOException("Not connected")
        val input = inputStream ?: throw IOException("Not connected")
        while (input.available() > 0) input.read()

        val command = "AT+SBDRB\r".toByteArray(Charsets.US_ASCII)
        os.write(command)
        os.flush()

        val raw = ByteArrayOutputStream()
        val deadline = clock() + AT_TIMEOUT_MS
        var frameStart = -1
        var needed = -1
        while (clock() < deadline) {
            if (input.available() == 0) {
                Thread.sleep(10)
                continue
            }
            val b = input.read()
            if (b == -1) break
            raw.write(b)
            val bytes = raw.toByteArray()
            if (frameStart < 0) {
                // With echo on the reply starts with the command itself.
                frameStart = when {
                    bytes.size < command.size && command.copyOf(bytes.size).contentEquals(bytes) -> continue
                    bytes.size >= command.size && bytes.copyOf(command.size).contentEquals(command) -> command.size
                    else -> 0
                }
            }
            if (needed < 0 && bytes.size >= frameStart + 2) {
                val len = ((bytes[frameStart].toInt() and 0xFF) shl 8) or (bytes[frameStart + 1].toInt() and 0xFF)
                if (len > MT_MAX_SIZE) throw IOException("SBDRB length $len > $MT_MAX_SIZE")
                needed = frameStart + 2 + len + 2
            }
            if (needed in 1..bytes.size) {
                val len = needed - frameStart - 4
                val msg = bytes.copyOfRange(frameStart + 2, frameStart + 2 + len)
                val sum = ((bytes[needed - 2].toInt() and 0xFF) shl 8) or (bytes[needed - 1].toInt() and 0xFF)
                readUntilOkOrTimeout(AT_TIMEOUT_MS)
                if (msg.sumOf { it.toInt() and 0xFF } and 0xFFFF != sum) throw IOException("SBDRB checksum mismatch")
                return msg
            }
        }
        throw IOException("SBDRB timed out")
    }

    /** The outcome of a mailbox check the user asked for. */
    sealed class MailboxResult {
        object NotConnected : MailboxResult()

        /** No session was started: the hold after a failed one runs for [seconds] more. */
        data class Held(val seconds: Long) : MailboxResult()

        /** The session failed, e.g. status 32: the modem sees no satellite. */
        data class SessionFailed(val moStatus: Int) : MailboxResult()

        object NoAnswer : MailboxResult()

        /**
         * The session ran. [received] messages were handed over, [stillQueued] more wait at
         * the gateway, and [sentOutgoing] says a waiting MO left in the same session.
         */
        data class Checked(val received: Int, val stillQueued: Int, val sentOutgoing: Boolean) : MailboxResult()
    }

    /**
     * Check the satellite mailbox on request. This is billed: one SBDIX, at least one credit
     * even when nothing waits. A message already in the MT buffer is read first, for free,
     * and an MO waiting in the MO buffer goes out in the same session. Every MT message is
     * handed to [onMessage].
     */
    suspend fun checkMailbox(onMessage: suspend (ByteArray) -> Unit): MailboxResult {
        if (!isWireReady()) return MailboxResult.NotConnected
        sbdixHoldRemainingMs().let { if (it > 0) return MailboxResult.Held((it + 999) / 1000) }

        val status = sbdStatus()
        var received = 0
        if (status?.mtFlag == true) {
            readMtBinary()?.takeIf { it.isNotEmpty() }?.let { onMessage(it); received++ }
        }
        // The session's message is handed over here, not through mtSink, so it is stored once.
        val result = sbdix(deliverMt = false) ?: return MailboxResult.NoAnswer
        result.mt?.let { onMessage(it); received++ }
        if (!result.moSuccess) return MailboxResult.SessionFailed(result.moStatus)
        return MailboxResult.Checked(received, result.mtQueued, sentOutgoing = status?.moFlag == true)
    }

    /**
     * Free end-to-end check of the link and the modem, with no satellite session: write
     * [size] bytes (CR, LF and 0x00 included) to the MO buffer, copy them to the MT buffer
     * (AT+SBDTC) and read them back (AT+SBDRB). Both buffers are cleared afterwards.
     */
    suspend fun loopbackTest(size: Int = 100): Boolean {
        if (!isWireReady()) return false
        val payload = ByteArray(size.coerceIn(1, MT_MAX_SIZE)) { i ->
            when (i % 4) {
                0 -> 0x0D
                1 -> 0x0A
                2 -> 0x00
                else -> i.toByte()
            }
        }
        return try {
            writeMoBuffer(payload) &&
                sendAT("AT+SBDTC").contains("OK") &&
                readMtBinary()?.contentEquals(payload) == true
        } catch (e: Exception) {
            _error.emit("Loopback failed: ${e.message}")
            false
        } finally {
            runCatching { sendAT("AT+SBDD2") }
        }
    }

    /** Read the MT buffer as UTF-8 text; null when empty or unreadable. */
    suspend fun readMtBuffer(): String? {
        val data = readMtBinary() ?: return null
        if (data.isEmpty()) return null
        return String(data, Charsets.UTF_8)
    }

    private fun readUntilOkOrTimeout(timeoutMs: Long): String {
        val input = inputStream ?: return ""
        val buf = StringBuilder()
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
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
}
