package net.meshsat.android.rnode

/**
 * KISS framing as RNode speaks it (TNC-2): FEND-delimited frames whose first byte is
 * a command, FESC escaping inside. A port of the Bridge's internal/kiss. [MESHSAT-1553 P4]
 */
object Kiss {
    const val FEND = 0xC0
    const val FESC = 0xDB
    const val TFEND = 0xDC
    const val TFESC = 0xDD
    const val CMD_DATA = 0x00

    fun escape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + data.size / 16 + 4)
        for (b in data) {
            when (b.toInt() and 0xFF) {
                FESC -> { out.write(FESC); out.write(TFESC) }
                FEND -> { out.write(FESC); out.write(TFEND) }
                else -> out.write(b.toInt())
            }
        }
        return out.toByteArray()
    }

    /** Reverses [escape]; an invalid escape pair keeps the escaped byte. */
    fun unescape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var esc = false
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (esc) {
                when (v) { TFEND -> out.write(FEND); TFESC -> out.write(FESC); else -> out.write(v) }
                esc = false
                continue
            }
            if (v == FESC) { esc = true; continue }
            out.write(v)
        }
        return out.toByteArray()
    }

    /** One frame: FEND cmd escaped(payload) FEND. */
    fun encode(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val esc = escape(payload)
        val out = ByteArray(esc.size + 3)
        out[0] = FEND.toByte(); out[1] = cmd.toByte()
        esc.copyInto(out, 2)
        out[out.size - 1] = FEND.toByte()
        return out
    }

    class Frame(val cmd: Int, val payload: ByteArray)

    /**
     * Turns a byte stream into frames. [feed] returns every complete frame so far; a
     * partial frame older than [timeoutMs] is dropped (RNode's device read timeout), and
     * one longer than [maxLen] is discarded.
     */
    class Splitter(private val maxLen: Int = 0, private val timeoutMs: Long = 0) {
        private val buf = java.io.ByteArrayOutputStream()
        private var inFrame = false
        private var lastByteMs = 0L

        fun feed(data: ByteArray, nowMs: Long): List<Frame> {
            if (timeoutMs > 0 && inFrame && buf.size() > 0 && nowMs - lastByteMs > timeoutMs) { buf.reset(); inFrame = false }
            if (data.isNotEmpty()) lastByteMs = nowMs
            val frames = ArrayList<Frame>()
            for (b in data) {
                val v = b.toInt() and 0xFF
                if (v == FEND) {
                    if (inFrame && buf.size() > 0) {
                        val raw = buf.toByteArray()
                        frames.add(Frame(raw[0].toInt() and 0xFF, unescape(raw.copyOfRange(1, raw.size))))
                    }
                    buf.reset(); inFrame = true
                    continue
                }
                if (!inFrame) continue
                if (maxLen > 0 && buf.size() > maxLen) { buf.reset(); inFrame = false; continue }
                buf.write(v)
            }
            return frames
        }

        fun reset() { buf.reset(); inFrame = false }
    }
}
