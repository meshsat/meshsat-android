package net.meshsat.android.reticulum

import android.util.Log
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.MeshtasticProtoAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reticulum interface over Meshtastic BLE (LoRa mesh).
 *
 * Encapsulates Reticulum packets in Meshtastic PRIVATE_APP portnum (256),
 * allowing them to traverse the LoRa mesh alongside normal text/position messages.
 *
 * MTU: ~230 bytes at SF7 (Meshtastic payload limit depends on spreading factor).
 * Cost: free (RF).
 * Latency: low (local mesh, <1s typical).
 *
 * [MESHSAT-213]
 */
class RnsMeshInterface(
    private val ble: MeshtasticBle,
    private val scope: CoroutineScope,
    override val interfaceId: String = "mesh_0",
) : RnsInterface {

    override val name: String = "Meshtastic BLE"
    override val mtu: Int = MESH_MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 200

    override val isOnline: Boolean
        get() = ble.state.value == MeshtasticBle.State.Connected

    private var receiveCallback: RnsReceiveCallback? = null

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "mesh interface offline"
        if (packet.size > mtu) return "packet exceeds mesh MTU ($mtu bytes)"

        return try {
            val toRadio = encodePrivateApp(packet)
            ble.sendToRadio(toRadio)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Mesh send failed: ${e.message}")
            e.message ?: "mesh send failed"
        }
    }

    override suspend fun start() {
        scope.launch {
            ble.receivedData.collect { data ->
                try {
                    val payload = extractPrivateApp(data) ?: return@collect
                    receiveCallback?.onReceive(interfaceId, payload)
                } catch (e: Exception) {
                    Log.d(TAG, "Mesh packet parse error: ${e.message}")
                }
            }
        }
    }

    override suspend fun stop() {
        // BLE lifecycle managed by GatewayService
    }

    companion object {
        private const val TAG = "RnsMeshInterface"

        const val PORTNUM_PRIVATE_APP = 256
        const val MESH_MTU = 237

        /**
         * Encode Reticulum packet as a Meshtastic ToRadio protobuf with portnum=256
         * (PRIVATE_APP), broadcast. Through the generated types: the hand-rolled encoder that
         * stood here wrote MeshPacket.to (a fixed32) as a varint, and the node dropped every
         * packet as malformed (MESHSAT-1372).
         */
        fun encodePrivateApp(
            payload: ByteArray,
            to: Long = 0xFFFFFFFFL,
            channel: Int = 0,
        ): ByteArray = MeshtasticProtoAdapter.encodePrivateApp(payload, to, channel)

        /**
         * Extract Reticulum packet from a Meshtastic FromRadio protobuf.
         * Returns null if not a PRIVATE_APP portnum packet.
         */
        fun extractPrivateApp(fromRadio: ByteArray): ByteArray? {
            val meshPacket = extractBytesField(fromRadio, 2) ?: return null
            val decoded = extractBytesField(meshPacket, 4) ?: return null
            val portnum = extractVarintField(decoded, 1)?.toInt() ?: return null
            if (portnum != PORTNUM_PRIVATE_APP) return null
            return extractBytesField(decoded, 2)
        }

        // --- Minimal protobuf decoding ---

        private fun extractVarintField(data: ByteArray, fieldNumber: Int): Long? {
            var i = 0
            while (i < data.size) {
                val (tag, tagLen) = readVarint(data, i)
                i += tagLen
                val wireType = (tag and 0x07).toInt()
                val fn = (tag ushr 3).toInt()
                when (wireType) {
                    0 -> { // varint
                        val (value, valLen) = readVarint(data, i)
                        if (fn == fieldNumber) return value
                        i += valLen
                    }
                    2 -> { // length-delimited
                        val (len, lenLen) = readVarint(data, i)
                        i += lenLen + len.toInt()
                    }
                    5 -> i += 4 // fixed32
                    1 -> i += 8 // fixed64
                    else -> return null
                }
            }
            return null
        }

        private fun extractBytesField(data: ByteArray, fieldNumber: Int): ByteArray? {
            var i = 0
            while (i < data.size) {
                val (tag, tagLen) = readVarint(data, i)
                i += tagLen
                val wireType = (tag and 0x07).toInt()
                val fn = (tag ushr 3).toInt()
                when (wireType) {
                    0 -> { // varint
                        val (_, valLen) = readVarint(data, i)
                        i += valLen
                    }
                    2 -> { // length-delimited
                        val (len, lenLen) = readVarint(data, i)
                        i += lenLen
                        if (fn == fieldNumber) return data.copyOfRange(i, i + len.toInt())
                        i += len.toInt()
                    }
                    5 -> i += 4
                    1 -> i += 8
                    else -> return null
                }
            }
            return null
        }

        private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var i = offset
            while (i < data.size) {
                val b = data[i].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                i++
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result to (i - offset)
        }
    }
}
