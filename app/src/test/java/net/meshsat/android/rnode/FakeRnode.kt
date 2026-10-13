package net.meshsat.android.rnode

import java.util.concurrent.Executors

/**
 * A software RNode for tests, the Kotlin twin of scripts/fake_rnode.py: answers the host
 * protocol of RNode firmware 1.75 (detect, firmware version, platform, MCU, configuration
 * echo, READY flow control), and every data frame written to one side is delivered as a
 * data frame on its [peer]. Replies arrive on their own thread, as a real link's do.
 */
class FakeRnode(val name: String, private val ready: Boolean = true, private val firmware: Pair<Int, Int> = 1 to 75, private val answerDetect: Boolean = true) : RnodeLink {
    var peer: FakeRnode? = null
    /** The driver's feed on this side. */
    var onBytes: ((ByteArray) -> Unit)? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-rnode-$name").apply { isDaemon = true } }
    private val splitter = Kiss.Splitter()
    val written = ArrayList<Kiss.Frame>()
    @Volatile var closed = false
    /** Injected device-side frames, e.g. an error or a reset. */
    fun emit(cmd: Int, payload: ByteArray) = deliver(Kiss.encode(cmd, payload))

    private fun deliver(b: ByteArray) { exec.execute { onBytes?.invoke(b) } }

    override fun write(b: ByteArray) {
        if (closed) throw IllegalStateException("closed")
        for (f in splitter.feed(b, System.currentTimeMillis())) {
            synchronized(written) { written.add(f) }
            handle(f)
        }
    }

    private fun handle(f: Kiss.Frame) {
        val p = f.payload
        when (f.cmd) {
            RnodeProtocol.CMD_DETECT -> if (answerDetect && p.isNotEmpty() && (p[0].toInt() and 0xFF) == RnodeProtocol.DETECT_REQ) deliver(Kiss.encode(RnodeProtocol.CMD_DETECT, byteArrayOf(RnodeProtocol.DETECT_RESP.toByte())))
            RnodeProtocol.CMD_FW_VERSION -> deliver(Kiss.encode(RnodeProtocol.CMD_FW_VERSION, byteArrayOf(firmware.first.toByte(), firmware.second.toByte())))
            RnodeProtocol.CMD_PLATFORM -> deliver(Kiss.encode(RnodeProtocol.CMD_PLATFORM, byteArrayOf(RnodeProtocol.PLATFORM_ESP32.toByte())))
            RnodeProtocol.CMD_MCU -> deliver(Kiss.encode(RnodeProtocol.CMD_MCU, byteArrayOf(0x81.toByte())))
            RnodeProtocol.CMD_FREQUENCY, RnodeProtocol.CMD_BANDWIDTH, RnodeProtocol.CMD_TXPOWER, RnodeProtocol.CMD_SF, RnodeProtocol.CMD_CR,
            RnodeProtocol.CMD_RADIO_STATE, RnodeProtocol.CMD_ST_ALOCK, RnodeProtocol.CMD_LT_ALOCK -> deliver(Kiss.encode(f.cmd, p))
            RnodeProtocol.CMD_DATA -> {
                peer?.deliver(Kiss.encode(RnodeProtocol.CMD_DATA, p))
                if (ready) deliver(Kiss.encode(RnodeProtocol.CMD_READY, byteArrayOf(1)))
            }
        }
    }

    override fun close() { closed = true }
}
