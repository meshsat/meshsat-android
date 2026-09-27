package net.meshsat.android.ble

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The byte-stream half of the MeshSat node's BLE Iridium pipe (MESHSAT-1236), free of
 * Android types so it runs in JVM tests. The contract is the node firmware's
 * (meshsat-meshtastic src/meshsat/IridiumPipe): raw bytes both ways, no framing.
 */
object IridiumPipeContract {
    const val SERVICE_UUID = "b3d305a2-7310-4877-ad12-8e245e71951a"

    /** Phone -> modem: write / write-without-response. */
    const val RX_UUID = "b9e2d4ba-f386-4728-b77a-7df7121db7a9"

    /** Modem -> phone: notify. Subscribing is what takes the modem. */
    const val TX_UUID = "469354dc-4c89-41ed-b939-d707c7a11f49"

    /**
     * [version, owner] in contract v1; [version, owner, flags, csq] in v2 (MESHSAT-1378). Read +
     * notify on every change. A client reads only what is there: 2 or 4 bytes.
     */
    const val STATUS_UUID = "69a4064d-78b9-46e5-a30a-1862e553245a"

    const val STATUS_VERSION = 1

    /** STATUS with the flags and signal bytes after the owner. */
    const val STATUS_VERSION_2 = 2

    /**
     * Contract v2 (MESHSAT-1378): 52 bytes of node health, little-endian, read + notify (on
     * change, at most every 2 s). Layout in meshsat-esp32/docs/IRIDIUM-BLE.md, "Version 2".
     */
    const val STATS_UUID = "9c22cf07-2256-4fc2-b6ee-ab0ceb12198d"

    const val STATS_VERSION = 2
    const val STATS_BYTES = 52

    /**
     * Contract v2 (MESHSAT-1378): pass windows from the phone, write with response:
     * [01][n][n x (u32 start epoch s, u16 duration s, u8 peak elevation deg)], little-endian,
     * n at most [PASS_MAX_WINDOWS]. A write replaces the node's list; it times the node's OWN
     * sessions when no phone holds the modem, and is never a signal gate on either side.
     */
    const val PASS_UUID = "5c1000e8-f411-4f3d-a4c9-5ee0610a8e66"

    const val PASS_VERSION = 1
    const val PASS_MAX_WINDOWS = 8

    /** The node keeps 1024 bytes from the phone; writes go out acknowledged, in chunks. */
    const val NODE_INBOUND_BYTES = 1024

    /** The smallest ATT payload any link allows (MTU 23 - 3). */
    const val MIN_CHUNK_BYTES = 20

    enum class Owner { Unknown, None, Phone, Node }

    /** The flags byte of STATUS v2 and STATS. */
    data class StatusFlags(
        val sessionInFlight: Boolean,
        val messageWaiting: Boolean,
        val modemAnswers: Boolean,
        val bufferCongested: Boolean,
    ) {
        companion object {
            fun of(b: Int) = StatusFlags(
                sessionInFlight = b and 0x01 != 0,
                messageWaiting = b and 0x02 != 0,
                modemAnswers = b and 0x04 != 0,
                bufferCongested = b and 0x08 != 0,
            )
        }
    }

    /** Everything a STATUS value carries; [flags] and [csq] are null on a v1 (2-byte) value. */
    data class NodeStatus(val owner: Owner, val flags: StatusFlags?, val csq: Int?)

    /** The STATS value (52 bytes). Null fields are the node's "unknown" markers. */
    data class NodeStats(
        val owner: Owner,
        val flags: StatusFlags,
        /** 0-5 as the modem last reported it; null when never read. */
        val csq: Int?,
        val csqAgeS: Long?,
        val sessions: Long,
        /** +SBDIX MO status of the last session, by any owner; null before the first. */
        val lastMoStatus: Int?,
        val lastMomsn: Int,
        val lastMtStatus: Int?,
        val lastMtQueued: Int,
        val lastSessionAgeS: Long?,
        val uptimeS: Long,
        val watchdogReboots: Long,
        val phoneBytesDropped: Long,
        val nodeSessions: Long,
        val nodeSent: Long,
        val nodeReceived: Long,
        val daySessionsUsed: Int,
        val daySessionsCap: Int,
    )

    /** One pass window for the node: when a satellite is up, from the phone's prediction. */
    data class PassWindow(val startEpochS: Long, val durationS: Int, val maxElevDeg: Int)

    private fun ownerOf(b: Int): Owner = when (b) {
        0 -> Owner.None
        1 -> Owner.Phone
        2 -> Owner.Node
        else -> Owner.Unknown
    }

    /** Decode the STATUS value's owner; an unknown contract version reads as [Owner.Unknown]. */
    fun parseStatus(value: ByteArray?): Owner = parseStatusFull(value)?.owner ?: Owner.Unknown

    /**
     * Decode a STATUS value of contract v1 (2 bytes) or v2 (4 bytes), reading only what is
     * there (MESHSAT-1378). Null for a version this app does not know.
     */
    fun parseStatusFull(value: ByteArray?): NodeStatus? {
        if (value == null || value.size < 2) return null
        val version = value[0].toInt() and 0xFF
        if (version != STATUS_VERSION && version != STATUS_VERSION_2) return null
        val owner = ownerOf(value[1].toInt() and 0xFF)
        if (value.size < 4) return NodeStatus(owner, null, null)
        return NodeStatus(owner, StatusFlags.of(value[2].toInt() and 0xFF), csqOf(value[3]))
    }

    private fun csqOf(b: Byte): Int? = (b.toInt() and 0xFF).let { if (it > 5) null else it }

    /** Decode a STATS value; null when shorter than [STATS_BYTES] or of another version. */
    fun parseStats(value: ByteArray?): NodeStats? {
        if (value == null || value.size < STATS_BYTES) return null
        if ((value[0].toInt() and 0xFF) != STATS_VERSION) return null
        fun u8(o: Int) = value[o].toInt() and 0xFF
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun i16(o: Int) = u16(o).toShort().toInt()
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        fun age(o: Int): Long? = u32(o).let { if (it == 0xFFFFFFFFL) null else it }
        return NodeStats(
            owner = ownerOf(u8(1)),
            flags = StatusFlags.of(u8(2)),
            csq = csqOf(value[3]),
            csqAgeS = age(4),
            sessions = u32(8),
            lastMoStatus = i16(12).let { if (it < 0) null else it },
            lastMomsn = u16(14),
            lastMtStatus = i16(16).let { if (it < 0) null else it },
            lastMtQueued = u16(18),
            lastSessionAgeS = age(20),
            uptimeS = u32(24),
            watchdogReboots = u32(28),
            phoneBytesDropped = u32(32),
            nodeSessions = u32(36),
            nodeSent = u32(40),
            nodeReceived = u32(44),
            daySessionsUsed = u8(48),
            daySessionsCap = u8(49),
        )
    }

    /** Encode the PASS value from the first [PASS_MAX_WINDOWS] of [windows]. */
    fun encodePassList(windows: List<PassWindow>): ByteArray {
        val kept = windows.take(PASS_MAX_WINDOWS)
        val out = java.io.ByteArrayOutputStream(2 + 7 * kept.size)
        out.write(PASS_VERSION)
        out.write(kept.size)
        for (w in kept) {
            val start = w.startEpochS.coerceIn(0, 0xFFFFFFFFL)
            for (i in 0 until 4) out.write(((start shr (8 * i)) and 0xFF).toInt())
            val dur = w.durationS.coerceIn(0, 0xFFFF)
            out.write(dur and 0xFF)
            out.write((dur shr 8) and 0xFF)
            out.write(w.maxElevDeg.coerceIn(0, 90))
        }
        return out.toByteArray()
    }
}

/**
 * Bytes the node notified on TX, read by the AT driver. [offer] is called from the GATT
 * callback and never blocks; when the buffer is full the excess is dropped and counted,
 * because a stalled reader must not stall the Bluetooth stack.
 */
class PipeInputStream(capacity: Int = 8192) : InputStream() {
    private val buf = ByteArray(capacity)
    private val lock = Object()
    private var head = 0
    private var size = 0
    private var closed = false

    /**
     * True once the connection behind this stream is gone. A reader that polls [available]
     * never sees the -1 a blocking read would get, so the AT driver asks this instead of
     * waiting out its command timeout (95 s for a session, MESHSAT-1372).
     */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Append [data]; returns how many bytes did not fit. */
    fun offer(data: ByteArray): Int = synchronized(lock) {
        var dropped = 0
        for (b in data) {
            if (size == buf.size) {
                dropped++
                continue
            }
            buf[(head + size) % buf.size] = b
            size++
        }
        lock.notifyAll()
        dropped
    }

    /** Discard everything buffered (the modem changed hands). */
    fun clear() = synchronized(lock) {
        head = 0
        size = 0
    }

    override fun available(): Int = synchronized(lock) { size }

    override fun read(): Int = synchronized(lock) {
        while (size == 0 && !closed) lock.wait()
        if (size == 0) return -1
        val b = buf[head].toInt() and 0xFF
        head = (head + 1) % buf.size
        size--
        b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
        if (len == 0) return 0
        while (size == 0 && !closed) lock.wait()
        if (size == 0) return -1
        val n = minOf(len, size)
        for (i in 0 until n) {
            b[off + i] = buf[head]
            head = (head + 1) % buf.size
        }
        size -= n
        n
    }

    override fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}

/**
 * Bytes the AT driver writes, sent to RX on [flush] in chunks of [chunkSize] bytes, each
 * one acknowledged before the next. [sendChunk] blocks until the write completed and
 * returns false if it failed. A write is refused unless [canWrite] says the phone owns
 * the modem: the node discards bytes from a phone that does not.
 */
class PipeOutputStream(
    private val chunkSize: () -> Int,
    private val canWrite: () -> Boolean,
    private val sendChunk: (ByteArray) -> Boolean,
) : OutputStream() {
    private val pending = java.io.ByteArrayOutputStream()

    override fun write(b: Int) = synchronized(pending) {
        pending.write(b)
        if (pending.size() >= IridiumPipeContract.NODE_INBOUND_BYTES) flushLocked()
    }

    override fun write(b: ByteArray, off: Int, len: Int) = synchronized(pending) {
        pending.write(b, off, len)
        if (pending.size() >= IridiumPipeContract.NODE_INBOUND_BYTES) flushLocked()
    }

    override fun flush() = synchronized(pending) { flushLocked() }

    private fun flushLocked() {
        if (pending.size() == 0) return
        val data = pending.toByteArray()
        pending.reset()
        if (!canWrite()) throw PipeNotOwnedException()
        val size = chunkSize().coerceAtLeast(IridiumPipeContract.MIN_CHUNK_BYTES)
        var off = 0
        while (off < data.size) {
            val end = minOf(off + size, data.size)
            if (!sendChunk(data.copyOfRange(off, end))) throw PipeWriteFailedException()
            off = end
        }
    }
}

/**
 * The node holds its own modem, so it discards anything this phone writes. A normal state
 * during a handover, and nothing to do with the health of the link.
 */
class PipeNotOwnedException : IOException("The node does not give this phone the modem")

/**
 * The write itself did not get through to the node. Counted towards a broken link, unlike
 * [PipeNotOwnedException]: telling the two apart is what keeps a handover from dropping the
 * BLE connection (MESHSAT-1270).
 */
class PipeWriteFailedException : IOException("Iridium pipe write failed")

/**
 * Watches the modem's output for an unsolicited line, "SBDRING" by default: the 9603
 * sends it in-band when a mobile-terminated message waits (AT+SBDMTA=1). The node has no
 * ring-indicator wire, so this is the only ring alert there is. The match survives a line
 * split across notifications.
 */
class LineWatcher(line: String = "SBDRING", private val onMatch: () -> Unit) {
    private val token = (line + "\r").toByteArray(Charsets.US_ASCII)
    private var matched = 0

    fun feed(data: ByteArray) {
        for (b in data) {
            matched = when {
                b == token[matched] -> matched + 1
                b == token[0] -> 1
                else -> 0
            }
            if (matched == token.size) {
                matched = 0
                onMatch()
            }
        }
    }
}
