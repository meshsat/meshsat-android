package net.meshsat.android.reticulum

import android.util.Log
import net.meshsat.android.bt.Iridium9704Spp
import net.meshsat.android.rns.ImtDedup
import net.meshsat.android.rns.ImtFraming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reticulum over Iridium IMT: the RockBLOCK 9704 behind the node's pipe (or an
 * HC-05), IMT topic 244. [MESHSAT-272, MESHSAT-1553 P6]
 *
 * Every packet goes out as "RNSI" + 0x01 + the raw packet, CrossTalk's framing,
 * so a Reticulum packet and a plain satellite text never get confused on the
 * same modem; on receive only a framed message whose remainder parses as a
 * packet is one, everything else stays a text for the satellite conversation.
 *
 * Packets go to the modem one at a time, in the order they were handed over,
 * and a failed MO is retried before the next packet goes: a Reticulum link
 * sends its RTT packet and the first message back to back, and the far end
 * drops a message that overtakes the RTT packet (the Bridge's sat_interface.go).
 * The node never waits for an MO: send() queues and returns.
 */
class RnsIridium9704Interface(
    private val spp: Iridium9704Spp,
    private val scope: CoroutineScope,
    override val interfaceId: String = "iridium9704_0",
    private val queueSize: Int = 64,
    /** A packet not sent by then is dropped. */
    private val maxAgeMs: Long = 15 * 60_000L,
    /** Waits between attempts; the last one repeats. */
    private val retryBackoffMs: List<Long> = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L),
) : RnsInterface {

    override val name: String = "Iridium 9704 IMT"
    override val mtu: Int = Iridium9704Spp.IMT_MAX_SIZE
    override val costCents: Int = 5
    override val latencyMs: Int = 60_000

    override val isOnline: Boolean
        get() = spp.state.value == Iridium9704Spp.State.Ready

    private var receiveCallback: RnsReceiveCallback? = null
    private var receiveJob: Job? = null
    private var sendJob: Job? = null
    private val dedup = ImtDedup()

    private class Out(val wire: ByteArray, val queuedAtMs: Long)
    private val queue = Channel<Out>(queueSize)

    /** The send queue's counters. */
    data class QueueStats(val queued: Long, val sent: Long, val retried: Long, val dropped: Long, val waiting: Int)
    @Volatile private var queued = 0L
    @Volatile private var sentCount = 0L
    @Volatile private var retried = 0L
    @Volatile private var dropped = 0L
    @Volatile private var waiting = 0
    fun queueStats(): QueueStats = QueueStats(queued, sentCount, retried, dropped, waiting)

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
        if (callback != null && receiveJob == null) startReceiving()
    }

    /** Queues a packet for the modem; null means queued, not sent. */
    override suspend fun send(packet: ByteArray): String? {
        val wire = ImtFraming.encode(packet)
        if (wire.size > mtu) return "packet exceeds IMT MTU"
        if (sendJob == null) startSending()
        val r = queue.trySend(Out(wire, System.currentTimeMillis()))
        return if (r.isSuccess) { queued++; waiting++; null } else { dropped++; "9704 send queue full ($queueSize)" }
    }

    private fun startSending() {
        sendJob = scope.launch {
            for (out in queue) {
                waiting--
                sendOne(out)
            }
        }
    }

    /** One packet until it is sent or too old; the packets behind it wait, order matters more than latency. */
    private suspend fun sendOne(out: Out) {
        var attempt = 0
        while (true) {
            val age = System.currentTimeMillis() - out.queuedAtMs
            if (age > maxAgeMs) {
                dropped++
                Log.w(TAG, "RNS packet dropped, not sent in ${age / 1000} s after $attempt attempts")
                return
            }
            if (isOnline) {
                val status = try { spp.sendMessageBlocking(out.wire, Iridium9704Spp.RAW_TOPIC) } catch (e: Exception) { Log.w(TAG, "IMT send failed: ${e.message}"); null }
                if (status == "mo_ack_received") {
                    sentCount++
                    Log.i(TAG, "RNS packet sent over IMT: ${out.wire.size} B, attempt ${attempt + 1}")
                    return
                }
                Log.w(TAG, "IMT MO failed (${status ?: "no answer"}), attempt ${attempt + 1}")
            } else {
                Log.d(TAG, "9704 not ready, RNS packet waits (attempt ${attempt + 1})")
            }
            retried++
            val wait = retryBackoffMs[minOf(attempt, retryBackoffMs.size - 1)]
            attempt++
            delay(wait)
        }
    }

    /** Framed MT messages become packets; the plain satellite conversation gets the rest elsewhere. */
    private fun startReceiving() {
        receiveJob = scope.launch {
            spp.receivedMessages.collect { mt ->
                val packet = ImtFraming.decode(mt) ?: return@collect
                if (dedup.seen(mt)) { Log.d(TAG, "repeated IMT frame dropped"); return@collect }
                Log.i(TAG, "RNS packet received over IMT: ${packet.size} B")
                receiveCallback?.onReceive(interfaceId, packet)
            }
        }
    }

    override suspend fun start() { /* SPP lifecycle managed by GatewayService */ }

    override suspend fun stop() {
        receiveJob?.cancel(); receiveJob = null
        sendJob?.cancel(); sendJob = null
    }

    companion object {
        private const val TAG = "RnsIridium9704"
    }
}
