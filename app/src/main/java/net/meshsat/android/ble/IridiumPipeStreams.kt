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

    /** [version, owner], read + notify on every owner change. */
    const val STATUS_UUID = "69a4064d-78b9-46e5-a30a-1862e553245a"

    const val STATUS_VERSION = 1

    /** The node keeps 1024 bytes from the phone; writes go out acknowledged, in chunks. */
    const val NODE_INBOUND_BYTES = 1024

    /** The smallest ATT payload any link allows (MTU 23 - 3). */
    const val MIN_CHUNK_BYTES = 20

    enum class Owner { Unknown, None, Phone, Node }

    /** Decode the STATUS value; an unknown contract version reads as [Owner.Unknown]. */
    fun parseStatus(value: ByteArray?): Owner {
        if (value == null || value.size < 2 || value[0].toInt() != STATUS_VERSION) return Owner.Unknown
        return when (value[1].toInt()) {
            0 -> Owner.None
            1 -> Owner.Phone
            2 -> Owner.Node
            else -> Owner.Unknown
        }
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
        if (!canWrite()) throw IOException("The node does not give this phone the modem")
        val size = chunkSize().coerceAtLeast(IridiumPipeContract.MIN_CHUNK_BYTES)
        var off = 0
        while (off < data.size) {
            val end = minOf(off + size, data.size)
            if (!sendChunk(data.copyOfRange(off, end))) throw IOException("Iridium pipe write failed")
            off = end
        }
    }
}

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
