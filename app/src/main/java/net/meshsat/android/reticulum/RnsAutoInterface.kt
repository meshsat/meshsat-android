package net.meshsat.android.reticulum

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import net.meshsat.android.rns.AutoInterface

/**
 * The phone's RNS AutoInterface (MESHSAT-1553 P4): Sideband, CrossTalk and rnsd on
 * the same WiFi with their AutoInterface on find this phone by themselves. Wraps the
 * JVM [AutoInterface] as an app interface and holds Android's multicast lock while it
 * runs, without which WiFi drops the discovery multicast before the app sees it.
 */
class RnsAutoInterface(
    private val context: Context,
    groupId: String = AutoInterface.DEFAULT_GROUP,
    override val interfaceId: String = INTERFACE_ID,
) : RnsInterface {
    override val name: String = "Reticulum AutoInterface"
    override val mtu: Int = AutoInterface.HW_MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 20

    private var receiveCallback: RnsReceiveCallback? = null
    private val core = AutoInterface(
        name = interfaceId, groupId = groupId,
        ignore = listOf("lo", "dummy0"),
        onPacket = { raw -> receiveCallback?.onReceive(interfaceId, raw) },
        log = { Log.i(TAG, it) },
    )
    private var multicastLock: WifiManager.MulticastLock? = null

    override val isOnline: Boolean get() = core.online
    fun peerCount(): Int = core.peerCount()
    fun peers(): List<AutoInterface.PeerInfo> = core.peers()
    fun devices(): List<String> = core.adoptedDevices()
    val groupText: String get() = core.groupText

    override fun setReceiveCallback(callback: RnsReceiveCallback?) { receiveCallback = callback }

    /** No peer yet is not a failure: there is nobody to send to. */
    override suspend fun send(packet: ByteArray): String? {
        if (!isOnline) return "autointerface offline"
        if (core.peerCount() == 0) return null
        return if (core.send(packet)) null else "no peer took the packet"
    }

    override suspend fun start() {
        if (core.online) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("meshsat-rns-auto")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock not acquired: ${e.message}")
        }
        if (!core.start()) {
            Log.w(TAG, "AutoInterface not started: no link-local IPv6 address (WiFi off?)")
            releaseLock()
        }
    }

    override suspend fun stop() {
        core.stop()
        releaseLock()
    }

    private fun releaseLock() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    companion object {
        private const val TAG = "RnsAuto"
        const val INTERFACE_ID = "auto_rns_0"
    }
}
