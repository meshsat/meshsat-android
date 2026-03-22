package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.astrocast.AstrocastSpp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reticulum interface over Astrocast LEO satellite (Astronode S via HC-05 SPP).
 *
 * Encapsulates Reticulum packets in Astronode S uplink payloads (max 160 bytes).
 * Store-and-forward LEO constellation — messages are delivered when a satellite
 * passes overhead (latency: minutes to hours).
 *
 * Uplink (MO): Android → HC-05 → Astronode S → LEO satellite → Ground → Hub
 * Downlink (MT): Hub → Ground → satellite → Astronode S → HC-05 → Android
 *
 * Cost: per-message (Astrocast credits), modeled as 3 cents.
 * Latency: variable (30s–hours, depending on satellite pass schedule).
 * Max uplink: 160 bytes (152 with geolocation). Queue depth: 8 messages.
 * Auto-fragmentation for larger payloads (up to 636 bytes via 4 fragments).
 *
 * [MESHSAT-12]
 */
class RnsAstrocastInterface(
    private val spp: AstrocastSpp,
    private val scope: CoroutineScope,
    override val interfaceId: String = "astrocast_rns_0",
) : RnsInterface {

    companion object {
        private const val TAG = "RnsAstrocastInterface"

        /** Max single uplink payload (without geolocation). */
        const val UPLINK_MTU = 160

        /** Max fragmented payload (4 × 159 bytes with 1-byte frag header). */
        const val FRAGMENTED_MTU = 636

        /**
         * Magic byte prefix for RNS packets in Astronode payloads.
         * 0x52 = 'R' for Reticulum (same as Iridium).
         */
        const val RNS_MAGIC: Byte = 0x52
    }

    override val name: String = "Astrocast LEO"
    override val mtu: Int = FRAGMENTED_MTU // Auto-fragmentation supported
    override val costCents: Int = 3
    override val latencyMs: Int = 300_000 // ~5 minutes average pass wait

    override val isOnline: Boolean
        get() = spp.state.value == AstrocastSpp.State.Connected

    private var receiveCallback: RnsReceiveCallback? = null
    private var payloadCounter = 0

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
        if (callback != null) {
            startReceiving()
        }
    }

    /**
     * Send a Reticulum packet via Astrocast uplink.
     * Prepends RNS magic byte. Auto-fragments if > 160 bytes.
     */
    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "astrocast interface offline"

        val payload = byteArrayOf(RNS_MAGIC) + packet
        if (payload.size > FRAGMENTED_MTU) {
            return "packet exceeds Astrocast fragmented MTU ($FRAGMENTED_MTU bytes)"
        }

        return try {
            val counter = payloadCounter++
            val result = spp.sendPayload(counter, payload)
            if (result >= 0) {
                Log.d(TAG, "RNS packet queued via Astrocast: ${packet.size}B, counter=$result")
                null
            } else {
                "Astrocast payload queue failed"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Astrocast send failed: ${e.message}")
            e.message ?: "astrocast send failed"
        }
    }

    /**
     * Start observing inbound downlink commands from the Astronode S.
     * Commands with the RNS magic byte are delivered to the receive callback.
     */
    private fun startReceiving() {
        scope.launch {
            spp.inboundCommands.collect { cmdData ->
                if (cmdData.isEmpty() || cmdData[0] != RNS_MAGIC) {
                    Log.d(TAG, "Downlink command is not an RNS packet (no magic byte)")
                    return@collect
                }

                // Process through fragmentation reassembly
                val reassembled = spp.processInboundPayload(cmdData)
                if (reassembled != null && reassembled.isNotEmpty() && reassembled[0] == RNS_MAGIC) {
                    val packet = reassembled.copyOfRange(1, reassembled.size)
                    receiveCallback?.onReceive(interfaceId, packet)
                    Log.d(TAG, "RNS packet received via Astrocast downlink: ${packet.size}B")
                }
            }
        }
    }

    override suspend fun start() {
        // SPP lifecycle managed by GatewayService
    }

    override suspend fun stop() {
        // SPP lifecycle managed by GatewayService
    }
}
