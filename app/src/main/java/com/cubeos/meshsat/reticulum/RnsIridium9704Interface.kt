package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.bt.Iridium9704Spp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reticulum interface over Iridium IMT (RockBLOCK 9704 via HC-05 Bluetooth SPP).
 *
 * Encapsulates Reticulum packets in IMT Mobile-Originated (MO) messages
 * using the JSPR protocol. Supports up to 100KB payloads — no Reticulum-layer
 * fragmentation needed.
 *
 * MO (send): Android → HC-05 → 9704 → Iridium → Ground Control → Hub
 * MT (receive): Hub → Ground Control → Iridium → 9704 → HC-05 → Android
 *
 * Cost: ~$0.05/message (Iridium IMT credit).
 * Latency: 30-90s (satellite acquisition + round-trip).
 *
 * [MESHSAT-272]
 */
class RnsIridium9704Interface(
    private val spp: Iridium9704Spp,
    private val scope: CoroutineScope,
    override val interfaceId: String = "iridium9704_0",
) : RnsInterface {

    override val name: String = "Iridium 9704 IMT"
    override val mtu: Int = Iridium9704Spp.IMT_MAX_SIZE
    override val costCents: Int = 5
    override val latencyMs: Int = 60_000

    override val isOnline: Boolean
        get() = spp.state.value == Iridium9704Spp.State.Ready

    private var receiveCallback: RnsReceiveCallback? = null

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
        if (callback != null) {
            startReceiving()
        }
    }

    /**
     * Send a Reticulum packet via Iridium IMT MO.
     *
     * Prepends the RNS magic byte and sends the full payload in a single
     * IMT message (up to 100KB — no fragmentation needed).
     */
    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "9704 interface offline"
        if (packet.size + 1 > mtu) return "packet exceeds IMT MTU"

        return try {
            // Prepend RNS magic byte to distinguish from legacy IMT traffic
            val payload = byteArrayOf(RNS_MAGIC) + packet

            val status = spp.sendMessageBlocking(payload)
            if (status == "mo_ack_received") {
                Log.d(TAG, "RNS packet sent via IMT: ${packet.size}B")
                null
            } else {
                "IMT MO failed: ${status ?: "timeout"}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "IMT send failed: ${e.message}")
            e.message ?: "imt send failed"
        }
    }

    /**
     * Start observing the 9704's receivedMessages SharedFlow for incoming
     * MT messages. Messages with the RNS magic byte are delivered to the
     * receive callback.
     */
    private fun startReceiving() {
        scope.launch {
            spp.receivedMessages.collect { mtPayload ->
                if (mtPayload.isEmpty() || mtPayload[0] != RNS_MAGIC) {
                    Log.d(TAG, "MT message is not an RNS packet (no magic byte)")
                    return@collect
                }

                val packet = mtPayload.copyOfRange(1, mtPayload.size)
                receiveCallback?.onReceive(interfaceId, packet)
                Log.d(TAG, "RNS packet received via IMT MT: ${packet.size}B")
            }
        }
    }

    override suspend fun start() {
        // SPP lifecycle managed by GatewayService
    }

    override suspend fun stop() {
        // SPP lifecycle managed by GatewayService
    }

    companion object {
        private const val TAG = "RnsIridium9704"

        /**
         * Magic byte prefix for RNS packets in IMT payloads.
         * Same as SBD (0x52 = 'R' for Reticulum) for protocol consistency.
         */
        const val RNS_MAGIC: Byte = 0x52
    }
}
