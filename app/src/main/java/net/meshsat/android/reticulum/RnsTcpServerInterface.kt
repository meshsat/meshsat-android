package net.meshsat.android.reticulum

import android.util.Log
import net.meshsat.android.rns.TcpServerInterface
import java.net.NetworkInterface

/**
 * The phone as an RNS TCPServerInterface (MESHSAT-1553 P4): Sideband, CrossTalk,
 * MeshChat or an rnsd on the same WiFi add a TCP client interface pointed at this
 * phone's address and port, and talk Reticulum to it with no extra setup. Wraps
 * [TcpServerInterface] (plain threads, HDLC, any number of peers) as an app
 * interface; a transmit goes to every connected peer, as upstream's does.
 */
class RnsTcpServerInterface(
    private val port: Int,
    override val interfaceId: String = INTERFACE_ID,
) : RnsInterface {
    override val name: String = "Reticulum TCP listener"
    override val mtu: Int = RnsConstants.MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 50

    private val server = TcpServerInterface(interfaceId, bindAddress = "0.0.0.0", log = { Log.i(TAG, it) })
    private var receiveCallback: RnsReceiveCallback? = null

    override val isOnline: Boolean get() = server.isOnline
    val boundPort: Int get() = server.port
    fun peerCount(): Int = server.peerCount()

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
        server.onReceive = if (callback == null) null else { raw -> callback.onReceive(interfaceId, raw) }
    }

    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "tcp listener offline"
        return if (server.send(packet)) null else "no peer took the packet"
    }

    override suspend fun start() {
        if (server.isOnline) return
        try {
            val p = server.start(port)
            Log.i(TAG, "Reticulum TCP listener on port $p (${wifiAddress() ?: "no WiFi address"})")
        } catch (e: Exception) {
            Log.w(TAG, "Reticulum TCP listener could not bind port $port: ${e.message}")
        }
    }

    override suspend fun stop() = server.stop()

    companion object {
        private const val TAG = "RnsTcpServer"
        const val INTERFACE_ID = "tcp_rns_server"

        /** The phone's site-local IPv4 address, what a Sideband user types; null off WiFi. */
        fun wifiAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}
