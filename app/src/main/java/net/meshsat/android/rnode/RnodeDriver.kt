package net.meshsat.android.rnode

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The bytes to and from an RNode: a USB serial port, a BLE Nordic UART, a pty in tests. Reads arrive through [RnodeDriver.feed]. */
interface RnodeLink {
    /** Writes every byte or throws. */
    fun write(b: ByteArray)
    fun close()
}

/**
 * One RNode (RNS/Interfaces/RNodeInterface.py, the Bridge's internal/rnode/driver.go):
 * detect the device, push frequency, bandwidth, TX power, spreading factor, coding
 * rate and airtime limits, verify the radio reports them back, then exchange raw
 * Reticulum packets as KISS data frames, with READY flow control. [MESHSAT-1553 P4]
 */
class RnodeDriver(private val link: RnodeLink, private val opts: Options) {
    enum class Transport { SERIAL, TCP, BLE }

    class Options(
        val name: String = "rnode",
        val transport: Transport = Transport.SERIAL,
        val params: RnodeProtocol.Params,
        val flowControl: Boolean = true,
        /** A callsign sent as a data frame every [idIntervalMs] of transmitting, for bands that require it. */
        val idCallsign: String = "",
        val idIntervalMs: Long = 0,
        /** Every data frame from the radio. */
        val onPacket: ((ByteArray) -> Unit)? = null,
        /** The device reported a fatal error or reset; the owner closes and reopens. */
        val onError: ((String) -> Unit)? = null,
        val log: (String) -> Unit = {},
        val now: () -> Long = System::currentTimeMillis,
        val sleep: (Long) -> Unit = { Thread.sleep(it) },
    )

    private val mu = ReentrantLock()
    private val writeMu = ReentrantLock()
    private val stats = RnodeProtocol.Stats()
    @Volatile var online = false
        private set
    private var ready = false
    private val queue = ArrayDeque<ByteArray>()
    private var firstTxMs = 0L
    private var lastWriteMs = 0L
    @Volatile private var closed = false
    private val detectLatch = CountDownLatch(1)
    private val splitter = Kiss.Splitter(RnodeProtocol.HW_MTU + 1, frameTimeoutMs())
    @Volatile var lastError: String? = null
        private set

    private fun frameTimeoutMs(): Long = when (opts.transport) { Transport.BLE -> 1250; Transport.TCP -> 1500; Transport.SERIAL -> 100 }

    private fun write(b: ByteArray) {
        writeMu.withLock { link.write(b) }
        mu.withLock { lastWriteMs = opts.now() }
    }

    /** Bytes from the link, any size, any boundary. */
    fun feed(bytes: ByteArray) {
        val frames = mu.withLock { splitter.feed(bytes, opts.now()) }
        for (f in frames) handleFrame(f)
        housekeeping()
    }

    /**
     * Runs RNodeInterface's configure_device sequence: settle, detect, initialise the radio,
     * validate what it reports. Blocks up to about ten seconds; null when the radio is up,
     * else why not.
     */
    fun open(): String? {
        opts.params.validate()?.let { return fail(it) }
        opts.sleep(2000)
        try { write(RnodeProtocol.detectBurst()) } catch (e: Exception) { return fail("write failed: ${e.message}") }
        val detectWait = if (opts.transport == Transport.SERIAL) 200L else 5000L
        if (!detectLatch.await(detectWait, TimeUnit.MILLISECONDS) && opts.transport == Transport.SERIAL) {
            // Python waits 0.2 s flat then checks; a slow USB stack gets a little more.
            detectLatch.await(1800, TimeUnit.MILLISECONDS)
        }
        // The version, platform and MCU replies follow the detect reply in the same burst.
        for (i in 0 until 50) { if (mu.withLock { stats.firmwareMajor != 0 }) break; opts.sleep(10) }
        val (detected, maj, min) = mu.withLock { Triple(stats.detected, stats.firmwareMajor, stats.firmwareMinor) }
        if (!detected) return fail("device not detected")
        if (maj < RnodeProtocol.REQUIRED_FW_MAJOR || (maj == RnodeProtocol.REQUIRED_FW_MAJOR && min < RnodeProtocol.REQUIRED_FW_MINOR)) {
            return fail("firmware $maj.$min is older than ${RnodeProtocol.REQUIRED_FW_MAJOR}.${RnodeProtocol.REQUIRED_FW_MINOR}")
        }
        try { initRadio() } catch (e: Exception) { return fail("radio init write failed: ${e.message}") }
        val validateWait = when (opts.transport) { Transport.BLE -> 1000L; Transport.TCP -> 1500L; Transport.SERIAL -> 250L }
        // Python sleeps a flat 0.25 s and checks once; polling to the same deadline is the same contract without the race on a fast link.
        val deadline = opts.now() + validateWait
        var verr: String?
        while (true) {
            verr = validate()
            if (verr == null || opts.now() > deadline) break
            opts.sleep(10)
        }
        if (verr != null) return fail(verr)
        mu.withLock { online = true; ready = true }
        val p = opts.params
        opts.log("rnode ${opts.name}: configured and powered up, ${p.frequency} Hz bw ${p.bandwidth} sf ${p.sf} cr ${p.cr} ${p.txPower} dBm, " +
            "${RnodeProtocol.platformName(stats.platform)} firmware $maj.$min")
        return null
    }

    private fun fail(why: String): String { lastError = why; opts.log("rnode ${opts.name}: $why"); return why }

    private fun initRadio() {
        val p = opts.params
        val cmds = arrayListOf(RnodeProtocol.setFrequency(p.frequency), RnodeProtocol.setBandwidth(p.bandwidth), RnodeProtocol.setTxPower(p.txPower), RnodeProtocol.setSf(p.sf), RnodeProtocol.setCr(p.cr))
        if (p.staLock > 0) cmds.add(RnodeProtocol.setStaLock(p.staLock))
        if (p.ltaLock > 0) cmds.add(RnodeProtocol.setLtaLock(p.ltaLock))
        cmds.add(RnodeProtocol.setRadioState(true))
        for (c in cmds) write(c)
        if (opts.transport == Transport.BLE) opts.sleep(2000)
    }

    private fun validate(): String? = mu.withLock {
        val p = opts.params
        val s = stats
        val problems = ArrayList<String>()
        if (s.frequency != 0L && Math.abs(s.frequency - p.frequency) > 100) problems.add("frequency ${s.frequency} != ${p.frequency}")
        if (s.bandwidth != p.bandwidth) problems.add("bandwidth ${s.bandwidth} != ${p.bandwidth}")
        if (s.txPower != p.txPower) problems.add("txpower ${s.txPower} != ${p.txPower}")
        if (s.sf != p.sf) problems.add("sf ${s.sf} != ${p.sf}")
        if (!s.radioOn) problems.add("radio state off")
        if (problems.isEmpty()) null else "radio did not confirm the configuration: $problems"
    }

    fun stats(): RnodeProtocol.Stats = mu.withLock { stats.copy() }

    /** Transmits one Reticulum packet (RNodeInterface.process_outgoing); null when sent or queued, else why not. */
    fun send(packet: ByteArray): String? {
        if (packet.size > RnodeProtocol.HW_MTU) return "packet of ${packet.size} bytes exceeds the RNode MTU of ${RnodeProtocol.HW_MTU}"
        mu.withLock {
            if (!online) return "rnode not online"
            if (opts.flowControl && !ready) { queue.addLast(packet.copyOf()); return null }
            if (opts.flowControl) ready = false
            if (firstTxMs == 0L) firstTxMs = opts.now()
            stats.txPackets++; stats.txBytes += packet.size
        }
        return try { write(RnodeProtocol.dataFrame(packet)); null } catch (e: Exception) { "write failed: ${e.message}" }
    }

    private fun processQueue() {
        val next = mu.withLock {
            if (queue.isEmpty()) { ready = true; return }
            ready = true
            queue.removeFirst()
        }
        send(next)
    }

    /** The callsign identification Python does between reads. */
    private fun housekeeping() {
        if (opts.idCallsign.isEmpty() || opts.idIntervalMs <= 0) return
        val due = mu.withLock {
            val t = opts.now()
            if (online && firstTxMs != 0L && t > firstTxMs + opts.idIntervalMs) { firstTxMs = 0L; true } else false
        }
        if (due) try { write(RnodeProtocol.dataFrame(opts.idCallsign.toByteArray(Charsets.UTF_8))) } catch (_: Exception) {}
    }

    /** Turns the radio off, says goodbye and closes the link. */
    fun close() {
        mu.withLock { if (closed) return; closed = true; online = false }
        try { write(RnodeProtocol.setRadioState(false)); write(RnodeProtocol.leave()) } catch (_: Exception) {}
        try { link.close() } catch (_: Exception) {}
    }

    /** The link dropped underneath us. */
    fun linkLost(why: String) {
        val was = mu.withLock { val w = online; online = false; w }
        if (was && !closed) failure("link lost: $why")
    }

    private fun failure(why: String) {
        lastError = why
        opts.log("rnode ${opts.name}: $why")
        opts.onError?.invoke(why)
    }

    private fun be16(p: ByteArray, i: Int): Int = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
    private fun be32(p: ByteArray, i: Int): Long = ((p[i].toLong() and 0xFF) shl 24) or ((p[i + 1].toLong() and 0xFF) shl 16) or ((p[i + 2].toLong() and 0xFF) shl 8) or (p[i + 3].toLong() and 0xFF)

    private fun handleFrame(f: Kiss.Frame) {
        val p = f.payload
        if (f.cmd == RnodeProtocol.CMD_DATA) {
            mu.withLock { stats.rxPackets++; stats.rxBytes += p.size }
            if (p.isNotEmpty()) opts.onPacket?.invoke(p.copyOf())
            return
        }
        var err: String? = null
        var ready = false
        var detected = false
        mu.withLock {
            when (f.cmd) {
                RnodeProtocol.CMD_FREQUENCY -> if (p.size >= 4) stats.frequency = be32(p, 0)
                RnodeProtocol.CMD_BANDWIDTH -> if (p.size >= 4) stats.bandwidth = be32(p, 0)
                RnodeProtocol.CMD_TXPOWER -> if (p.isNotEmpty()) stats.txPower = p[0].toInt() and 0xFF
                RnodeProtocol.CMD_SF -> if (p.isNotEmpty()) stats.sf = p[0].toInt() and 0xFF
                RnodeProtocol.CMD_CR -> if (p.isNotEmpty()) stats.cr = p[0].toInt() and 0xFF
                RnodeProtocol.CMD_RADIO_STATE -> if (p.isNotEmpty()) stats.radioOn = p[0].toInt() != 0
                RnodeProtocol.CMD_FW_VERSION -> if (p.size >= 2) { stats.firmwareMajor = p[0].toInt() and 0xFF; stats.firmwareMinor = p[1].toInt() and 0xFF }
                RnodeProtocol.CMD_STAT_RSSI -> if (p.isNotEmpty()) stats.rssi = (p[0].toInt() and 0xFF) - RnodeProtocol.RSSI_OFFSET
                RnodeProtocol.CMD_STAT_SNR -> if (p.isNotEmpty()) {
                    stats.snr = p[0].toInt() * 0.25
                    val sfs = stats.sf - 7.0
                    val qMin = -9 - sfs * 2.5
                    stats.quality = ((stats.snr - qMin) / (6 - qMin) * 100).coerceIn(0.0, 100.0)
                }
                RnodeProtocol.CMD_ST_ALOCK -> if (p.size >= 2) stats.staLock = be16(p, 0) / 100.0
                RnodeProtocol.CMD_LT_ALOCK -> if (p.size >= 2) stats.ltaLock = be16(p, 0) / 100.0
                RnodeProtocol.CMD_STAT_CHTM -> if (p.size >= 11) {
                    stats.airtimeShort = be16(p, 0) / 100.0; stats.airtimeLong = be16(p, 2) / 100.0
                    stats.channelLoadShort = be16(p, 4) / 100.0; stats.channelLoadLong = be16(p, 6) / 100.0
                    stats.currentRssi = (p[8].toInt() and 0xFF) - RnodeProtocol.RSSI_OFFSET
                    stats.noiseFloor = (p[9].toInt() and 0xFF) - RnodeProtocol.RSSI_OFFSET
                    stats.interference = if ((p[10].toInt() and 0xFF) == 0xFF) null else (p[10].toInt() and 0xFF) - RnodeProtocol.RSSI_OFFSET
                }
                RnodeProtocol.CMD_STAT_PHYPRM -> if (p.size >= 12) {
                    stats.symbolTimeMs = be16(p, 0) / 1000.0; stats.symbolRate = be16(p, 2); stats.preambleSymbols = be16(p, 4)
                    stats.preambleTimeMs = be16(p, 6); stats.csmaSlotTimeMs = be16(p, 8); stats.csmaDifsMs = be16(p, 10)
                }
                RnodeProtocol.CMD_STAT_BAT -> if (p.size >= 2) { stats.batteryState = p[0].toInt() and 0xFF; stats.batteryPercent = minOf(100, p[1].toInt() and 0xFF) }
                RnodeProtocol.CMD_STAT_TEMP -> if (p.isNotEmpty()) { val t = (p[0].toInt() and 0xFF) - 120; stats.temperatureC = if (t in -30..90) t else null }
                RnodeProtocol.CMD_PLATFORM -> if (p.isNotEmpty()) stats.platform = p[0].toInt() and 0xFF
                RnodeProtocol.CMD_MCU -> if (p.isNotEmpty()) stats.mcu = p[0].toInt() and 0xFF
                RnodeProtocol.CMD_DETECT -> if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == RnodeProtocol.DETECT_RESP) { stats.detected = true; detected = true }
                RnodeProtocol.CMD_ERROR -> if (p.isNotEmpty()) {
                    when (p[0].toInt() and 0xFF) {
                        RnodeProtocol.ERROR_INITRADIO -> err = "hardware error: radio initialisation failure"
                        RnodeProtocol.ERROR_TXFAILED -> err = "hardware error: transmit failure"
                        RnodeProtocol.ERROR_MEMORY_LOW, RnodeProtocol.ERROR_MODEM_TIMEOUT, RnodeProtocol.ERROR_QUEUE_FULL ->
                            opts.log("rnode ${opts.name}: device reports a non-fatal hardware error 0x%02x".format(p[0].toInt() and 0xFF))
                        else -> err = "hardware error: code 0x%02x".format(p[0].toInt() and 0xFF)
                    }
                }
                RnodeProtocol.CMD_RESET -> if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == RnodeProtocol.RESET_MAGIC && online && stats.platform == RnodeProtocol.PLATFORM_ESP32) {
                    online = false; err = "device reset while online"
                }
                RnodeProtocol.CMD_READY -> if (opts.flowControl) ready = true
            }
        }
        if (detected) detectLatch.countDown()
        if (ready) processQueue()
        err?.let { failure(it) }
    }
}
