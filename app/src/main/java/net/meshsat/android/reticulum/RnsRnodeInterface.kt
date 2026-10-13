package net.meshsat.android.reticulum

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.meshsat.android.rnode.RnodeBleLink
import net.meshsat.android.rnode.RnodeDriver
import net.meshsat.android.rnode.RnodeLink
import net.meshsat.android.rnode.RnodeProtocol
import net.meshsat.android.rnode.RnodeUsbLink
import java.util.concurrent.CompletableFuture

/**
 * An RNode as a Reticulum interface (MESHSAT-1553 P4): the phone on Sideband's and
 * Tim's LoRa meshes through a radio on USB OTG or Bluetooth LE. The port string is
 * "usb" or "ble:<address>". Keeps the radio up: a drop or a device error closes and
 * reopens it with a growing pause.
 */
class RnsRnodeInterface(
    private val context: Context,
    private val scope: CoroutineScope,
    val config: Config,
    override val interfaceId: String = INTERFACE_ID,
) : RnsInterface {
    class Config(
        val port: String,
        val params: RnodeProtocol.Params,
        val flowControl: Boolean = true,
        val idCallsign: String = "",
        val idIntervalMin: Int = 0,
    )

    override val name: String = "RNode (LoRa)"
    override val mtu: Int = RnodeProtocol.HW_MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 500

    private var receiveCallback: RnsReceiveCallback? = null
    @Volatile private var driver: RnodeDriver? = null
    @Volatile private var link: RnodeLink? = null
    private var supervisor: Job? = null
    @Volatile private var stopped = false
    /** One line for the UI: what the radio is doing. */
    @Volatile var status: String = "not started"
        private set
    @Volatile var lastError: String = ""
        private set
    val isBle: Boolean get() = config.port.startsWith("ble:")
    val deviceDescription: String get() = (link as? RnodeUsbLink)?.deviceName?.ifBlank { null } ?: if (isBle) config.port.removePrefix("ble:") else "USB"

    override val isOnline: Boolean get() = driver?.online == true
    fun stats(): RnodeProtocol.Stats? = driver?.stats()
    fun bitrate(): Int = config.params.bitrate().toInt()

    override fun setReceiveCallback(callback: RnsReceiveCallback?) { receiveCallback = callback }

    override suspend fun send(packet: ByteArray): String? = driver?.takeIf { it.online }?.send(packet) ?: "rnode not online"

    override suspend fun start() {
        if (supervisor?.isActive == true) return
        stopped = false
        supervisor = scope.launch(Dispatchers.IO) {
            var pause = 5_000L
            while (isActive && !stopped) {
                val lost = CompletableFuture<String>()
                val err = openOnce(lost)
                if (err == null) {
                    pause = 5_000L
                    val why = try { lost.get() } catch (_: Exception) { "closed" }
                    status = "lost: $why"
                    Log.w(TAG, "RNode $why, reopening in ${pause / 1000} s")
                } else {
                    lastError = err
                    status = "not up: $err"
                    Log.w(TAG, "RNode not up: $err, trying again in ${pause / 1000} s")
                }
                closeDriver()
                if (stopped) break
                delay(pause)
                pause = minOf(pause * 2, 60_000L)
            }
        }
    }

    /** Opens the link and configures the radio; null when up, else why not. [lost] completes when it drops. */
    private fun openOnce(lost: CompletableFuture<String>): String? {
        status = "connecting to $deviceDescription"
        val transport: RnodeDriver.Transport
        val l: RnodeLink
        var d: RnodeDriver? = null
        if (isBle) {
            val ble = RnodeBleLink(context, config.port.removePrefix("ble:"), scope, log = { Log.i(TAG, it) })
            ble.onBytes = { b -> d?.feed(b) }
            ble.onDisconnected = { why -> d?.linkLost(why); lost.complete(why) }
            ble.connect()?.let { return it }
            l = ble; transport = RnodeDriver.Transport.BLE
        } else {
            val usb = RnodeUsbLink(context, log = { Log.i(TAG, it) })
            usb.onBytes = { b -> d?.feed(b) }
            usb.onDisconnected = { why -> d?.linkLost(why); lost.complete(why) }
            usb.open()?.let { return it }
            l = usb; transport = RnodeDriver.Transport.SERIAL
        }
        link = l
        status = "configuring the radio"
        d = RnodeDriver(l, RnodeDriver.Options(
            name = interfaceId, transport = transport, params = config.params, flowControl = config.flowControl,
            idCallsign = config.idCallsign, idIntervalMs = config.idIntervalMin * 60_000L,
            onPacket = { raw -> receiveCallback?.onReceive(interfaceId, raw) },
            onError = { why -> lost.complete(why) },
            log = { Log.i(TAG, it) },
        ))
        driver = d
        d.open()?.let { return it }
        lastError = ""
        status = "online on $deviceDescription"
        return null
    }

    private fun closeDriver() {
        try { driver?.close() } catch (_: Exception) {}
        try { link?.close() } catch (_: Exception) {}
        driver = null; link = null
    }

    override suspend fun stop() {
        stopped = true
        supervisor?.cancel(); supervisor = null
        closeDriver()
        status = "stopped"
    }

    companion object {
        private const val TAG = "RnsRnode"
        const val INTERFACE_ID = "rnode_rns_0"
    }
}
