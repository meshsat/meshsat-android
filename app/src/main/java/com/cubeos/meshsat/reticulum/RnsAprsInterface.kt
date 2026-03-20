package com.cubeos.meshsat.reticulum

import android.util.Log
import com.cubeos.meshsat.aprs.Ax25Address
import com.cubeos.meshsat.aprs.Ax25Codec
import com.cubeos.meshsat.aprs.Ax25Frame
import com.cubeos.meshsat.aprs.KissClient

/**
 * Reticulum interface over AX.25/APRS (KISS TNC).
 *
 * Encapsulates Reticulum packets in AX.25 UI frames via KISS protocol.
 * Coexists with normal APRS traffic — RNS packets use the RNSNET
 * destination callsign.
 *
 * AX.25 UI frame MTU: 256 bytes. Cost: free (RF). Latency: <1s.
 *
 * [MESHSAT-218]
 */
class RnsAprsInterface(
    private val kissClient: KissClient,
    private val callsign: () -> String,
    private val ssid: () -> Int,
    override val interfaceId: String = "aprs_0",
) : RnsInterface {

    override val name: String = "AX.25/APRS"
    override val mtu: Int = AX25_MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 500

    override val isOnline: Boolean
        get() = kissClient.isConnected

    private var receiveCallback: RnsReceiveCallback? = null
    private var previousFrameCallback: ((Ax25Frame) -> Unit)? = null

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "APRS interface offline"
        if (packet.size > mtu) return "packet exceeds AX.25 MTU ($mtu bytes)"

        return try {
            val dst = Ax25Address(RNS_AX25_DEST, 0)
            val src = Ax25Address(callsign(), ssid())
            val encoded = Ax25Codec.encode(dst, src, emptyList(), packet)
            kissClient.sendFrame(encoded)
            Log.d(TAG, "RNS packet sent via AX.25: ${packet.size}B")
            null
        } catch (e: Exception) {
            Log.w(TAG, "AX.25 send failed: ${e.message}")
            e.message ?: "AX.25 send failed"
        }
    }

    override suspend fun start() {
        val cb: (Ax25Frame) -> Unit = { frame ->
            try {
                if (frame.dst.call == RNS_AX25_DEST && frame.info.isNotEmpty()) {
                    receiveCallback?.onReceive(interfaceId, frame.info)
                    Log.d(TAG, "RNS packet received via AX.25 from ${frame.src.format()}: ${frame.info.size}B")
                }
            } catch (e: Exception) {
                Log.d(TAG, "AX.25 RNS parse error: ${e.message}")
            }
        }
        previousFrameCallback = cb
        kissClient.setFrameCallback(cb)
    }

    override suspend fun stop() {
        // Can't set null — set a no-op callback
        kissClient.setFrameCallback { }
        previousFrameCallback = null
    }

    companion object {
        private const val TAG = "RnsAprsInterface"
        const val AX25_MTU = 256
        const val RNS_AX25_DEST = "RNSNET"
    }
}
