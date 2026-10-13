package net.meshsat.android.rnode

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An RNode on USB OTG: the first USB serial adapter the phone sees (CP210x, CH34x, FTDI,
 * or a board's own CDC port), 115200 8N1 as the RNode firmware listens. Android asks the
 * user once per device for permission; that wait is part of [open]. [MESHSAT-1553 P4]
 */
class RnodeUsbLink(private val context: Context, private val log: (String) -> Unit) : RnodeLink {
    companion object {
        const val BAUD = 115200
        private const val ACTION_PERMISSION = "net.meshsat.android.RNODE_USB_PERMISSION"
    }

    var onBytes: ((ByteArray) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    @Volatile var deviceName: String = ""
        private set

    /** Finds, asks for and opens the adapter; null when open, else why not. */
    fun open(timeoutMs: Long = 60_000): String? {
        val mgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return "no USB host"
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr)
        if (drivers.isEmpty()) return "no USB serial device attached"
        val drv = drivers[0]
        val dev: UsbDevice = drv.device
        deviceName = "${dev.productName ?: dev.deviceName} (${drv.javaClass.simpleName.removeSuffix("SerialDriver")})"
        if (!mgr.hasPermission(dev)) {
            log("rnode usb: asking for permission on $deviceName")
            val latch = CountDownLatch(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (i.action == ACTION_PERMISSION) latch.countDown() }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
                mgr.requestPermission(dev, pi)
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
            if (!mgr.hasPermission(dev)) return "USB permission for $deviceName not granted"
        }
        val conn = mgr.openDevice(dev) ?: return "could not open $deviceName"
        val p = drv.ports.firstOrNull() ?: return "$deviceName has no serial port"
        return try {
            p.open(conn)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.dtr = true; p.rts = true } catch (_: Exception) {}
            val m = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) { onBytes?.invoke(data) }
                override fun onRunError(e: Exception) { onDisconnected?.invoke("USB read failed: ${e.message}") }
            })
            m.start()
            io = m; port = p
            log("rnode usb: $deviceName open at $BAUD")
            null
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            "could not open $deviceName: ${e.message}"
        }
    }

    override fun write(b: ByteArray) { (port ?: throw IllegalStateException("USB not open")).write(b, 2000) }

    override fun close() {
        try { io?.stop() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        io = null; port = null
    }
}
