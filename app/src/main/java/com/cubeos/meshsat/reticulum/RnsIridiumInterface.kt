package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.bt.IridiumSpp

/**
 * Reticulum interface over Iridium SBD (via HC-05 Bluetooth SPP).
 *
 * Encapsulates Reticulum packets in SBD Mobile-Originated (MO) messages.
 * Most Reticulum packets fit in a single SBD frame (340-byte MO MTU).
 * Oversized packets are rejected — fragmentation is handled at the
 * Reticulum Resource layer (MESHSAT-223).
 *
 * MO (send): Android → Iridium 9603N/9704 → satellite → Ground Control → Hub
 * MT (receive): Hub → Ground Control → satellite → modem → Android
 *
 * Cost: ~$0.05/message (Iridium SBD credit).
 * Latency: 30-90s (satellite acquisition + round-trip).
 *
 * [MESHSAT-216]
 */
class RnsIridiumInterface(
    private val spp: IridiumSpp,
    override val interfaceId: String = "iridium_0",
) : RnsInterface {

    override val name: String = "Iridium SBD"
    override val mtu: Int = IRIDIUM_MO_MTU
    override val costCents: Int = 5
    override val latencyMs: Int = 60_000

    override val isOnline: Boolean
        get() = spp.state.value == IridiumSpp.State.Connected

    private var receiveCallback: RnsReceiveCallback? = null

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    /**
     * Send a Reticulum packet via Iridium SBD MO.
     *
     * Writes the raw packet bytes to the modem's MO buffer and initiates
     * an SBD session. The modem handles satellite acquisition autonomously.
     *
     * Rate limiting is preserved — the TokenBucket in GatewayService
     * gates Iridium sends to prevent cost overrun.
     */
    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "iridium interface offline"
        if (packet.size > mtu) return "packet exceeds Iridium MO MTU ($mtu bytes)"

        return try {
            // Prepend RNS magic byte to distinguish from legacy SBD traffic
            val sbdPayload = byteArrayOf(SBD_RNS_MAGIC) + packet

            val written = spp.writeMoBuffer(sbdPayload)
            if (!written) return "failed to write MO buffer"

            val result = spp.sbdix()
            if (result == null) return "SBDIX session failed"
            if (result.moStatus > 4) return "MO send failed: status=${result.moStatus}"

            Log.d(TAG, "RNS packet sent via Iridium SBD: ${packet.size}B, MO status=${result.moStatus}")

            // Check for incoming MT message in same session
            if (result.mtStatus == 1 && result.mtLength > 0) {
                handleMtMessage()
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Iridium send failed: ${e.message}")
            e.message ?: "iridium send failed"
        }
    }

    /**
     * Poll for Mobile-Terminated (MT) messages from the Iridium modem.
     *
     * Called periodically or after an SBD session reports mtStatus=1.
     * MT messages with the RNS magic byte are delivered to the receive callback.
     */
    suspend fun pollMt(): Boolean {
        if (!isOnline) return false

        return try {
            val status = spp.sbdStatus() ?: return false
            if (!status.mtFlag) return false

            // Initiate session to receive MT
            val result = spp.sbdix() ?: return false
            if (result.mtStatus != 1 || result.mtLength <= 0) return false

            handleMtMessage()
            true
        } catch (e: Exception) {
            Log.d(TAG, "MT poll failed: ${e.message}")
            false
        }
    }

    private suspend fun handleMtMessage() {
        val mtData = spp.readMtBuffer() ?: return
        val mtBytes = mtData.toByteArray(Charsets.ISO_8859_1)

        // Check for RNS magic byte
        if (mtBytes.isEmpty() || mtBytes[0] != SBD_RNS_MAGIC) {
            Log.d(TAG, "MT message is not an RNS packet (no magic byte)")
            return
        }

        val packet = mtBytes.copyOfRange(1, mtBytes.size)
        receiveCallback?.onReceive(interfaceId, packet)
        Log.d(TAG, "RNS packet received via Iridium MT: ${packet.size}B")
    }

    override suspend fun start() {
        // SPP lifecycle managed by GatewayService
    }

    override suspend fun stop() {
        // SPP lifecycle managed by GatewayService
    }

    companion object {
        private const val TAG = "RnsIridiumInterface"

        /** Iridium SBD Mobile-Originated maximum payload. */
        const val IRIDIUM_MO_MTU = 340

        /** Iridium SBD Mobile-Terminated maximum payload. */
        const val IRIDIUM_MT_MTU = 270

        /**
         * Magic byte prefix for RNS packets in SBD payloads.
         * Distinguishes Reticulum traffic from legacy MeshSat SBD messages.
         * 0x52 = 'R' for Reticulum.
         */
        const val SBD_RNS_MAGIC: Byte = 0x52
    }
}
