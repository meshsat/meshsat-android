package com.cubeos.meshsat.routing

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Callback to transmit a keepalive packet on the network.
 */
fun interface SendCallback {
    fun send(linkId: ByteArray, data: ByteArray)
}

/**
 * Keepalive packet: minimal heartbeat to keep a link alive.
 * Wire format: type(1) + link_id(32) + random(1) = 34 bytes.
 */
data class KeepalivePacket(
    val linkId: ByteArray,  // 32 bytes
    val random: Byte,       // 1 byte of randomness to prevent dedup swallowing
) {
    fun marshal(): ByteArray {
        val buf = ByteArray(1 + LINK_ID_LEN + 1)
        buf[0] = PACKET_KEEPALIVE
        System.arraycopy(linkId, 0, buf, 1, LINK_ID_LEN)
        buf[1 + LINK_ID_LEN] = random
        return buf
    }

    companion object {
        fun unmarshal(data: ByteArray): KeepalivePacket {
            require(data.size >= 1 + LINK_ID_LEN + 1) { "keepalive packet too short" }
            require(data[0] == PACKET_KEEPALIVE) { "not a keepalive packet" }
            val linkId = data.copyOfRange(1, 1 + LINK_ID_LEN)
            val random = data[1 + LINK_ID_LEN]
            return KeepalivePacket(linkId, random)
        }
    }
}

const val PACKET_KEEPALIVE: Byte = 0x14

/**
 * Manages periodic keepalive sending and timeout detection for all active links.
 *
 * Bandwidth: 0.45 bps per link. At 1-byte keepalive every ~18s, 100 links on a
 * 1200 bps channel consumes 3.75% capacity.
 *
 * Port of Go's routing/keepalive.go.
 */
class LinkKeepalive(
    private val linkMgr: LinkManager,
    private val sendFn: SendCallback?,
    private val interval: Duration = KEEPALIVE_INTERVAL,
    private val timeout: Duration = KEEPALIVE_TIMEOUT,
    private val scope: CoroutineScope,
) {
    /** Launch the keepalive loop. Sends keepalives and closes timed-out links. */
    fun start() {
        scope.launch {
            while (isActive) {
                delay(interval)
                tick()
            }
        }
    }

    /** Process an incoming keepalive and update link activity timestamp. */
    fun handleKeepalive(data: ByteArray) {
        val kp = KeepalivePacket.unmarshal(data)
        val link = linkMgr.getLink(kp.linkId)
        if (link == null) {
            Log.d(TAG, "Keepalive for unknown link: ${kp.linkId.toHex().take(32)}")
            return
        }
        link.lastActivity = System.currentTimeMillis()
    }

    private fun tick() {
        val links = linkMgr.activeLinks()
        val now = System.currentTimeMillis()
        val timeoutMs = timeout.inWholeMilliseconds

        for (link in links) {
            // Check timeout
            if (now - link.lastActivity > timeoutMs) {
                Log.i(TAG, "Link timeout: ${link.idHex} inactive=${now - link.lastActivity}ms")
                linkMgr.closeLink(link.id)
                continue
            }

            // Send keepalive
            val randomBuf = ByteArray(1)
            SecureRandom().nextBytes(randomBuf)
            val kp = KeepalivePacket(linkId = link.id, random = randomBuf[0])
            sendFn?.send(link.id, kp.marshal())
        }
    }

    companion object {
        private const val TAG = "LinkKeepalive"

        /** Bandwidth consumed per link for keepalive: 0.45 bps. */
        const val KEEPALIVE_BPS = 0.45

        /** Time between keepalive packets. 8 bits / 0.45 bps ~ 18s. */
        val KEEPALIVE_INTERVAL: Duration = 18.seconds

        /** Timeout: ~3 missed keepalives. */
        val KEEPALIVE_TIMEOUT: Duration = 60.seconds

        /** Max concurrent links for a given bandwidth budget. */
        fun maxLinksForBandwidth(bandwidthBps: Int, capacityPct: Double): Int {
            if (bandwidthBps <= 0 || capacityPct <= 0) return 0
            val budget = bandwidthBps.toDouble() * capacityPct / 100.0
            return (budget / KEEPALIVE_BPS).toInt()
        }
    }
}
