package net.meshsat.android.rnode

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import net.meshsat.android.ble.GattOpQueue
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An RNode over Bluetooth LE: the Nordic UART service the RNode firmware exposes
 * (RNodeInterface's BLEConnection). Bytes to the radio go to the RX characteristic in
 * chunks of the negotiated MTU, bytes from it arrive as notifications on TX. The first
 * protected operation on an unbonded RNode brings up Android's pairing dialog, with the
 * passkey on the radio's display; the operation queue waits for that. [MESHSAT-1553 P4]
 */
@SuppressLint("MissingPermission")
class RnodeBleLink(private val context: Context, val address: String, scope: CoroutineScope, private val log: (String) -> Unit) : RnodeLink {
    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        /** The host writes here. */
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        /** The radio notifies here. */
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MAX_MTU = 512
    }

    var onBytes: ((ByteArray) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    private val queue = GattOpQueue(scope)
    private val ready = CountDownLatch(1)
    @Volatile private var readyError: String? = null
    @Volatile private var closed = false

    val chunkSize: Int get() = maxOf(20, mtu - 3)

    /** Connects and brings the UART up; null when ready, else why not. Blocks up to [timeoutMs]. */
    fun connect(timeoutMs: Long = 40_000): String? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return "Bluetooth is off"
        val device = try { adapter.getRemoteDevice(address) } catch (e: Exception) { return "bad BLE address $address" }
        log("rnode ble: connecting to $address")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) { close(); return "BLE connection to $address timed out" }
        return readyError?.also { close() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(MAX_MTU)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (gatt === g) { g.close(); gatt = null }
                    queue.close()
                    if (ready.count > 0) { readyError = "BLE disconnected (status $status)"; ready.countDown() }
                    else if (!closed) onDisconnected?.invoke("BLE disconnected (status $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { readyError = "service discovery failed ($status)"; ready.countDown(); return }
            val svc = g.getService(NUS_SERVICE) ?: run { readyError = "no Nordic UART service: not an RNode?"; ready.countDown(); return }
            rx = svc.getCharacteristic(NUS_RX); tx = svc.getCharacteristic(NUS_TX)
            val t = tx
            if (rx == null || t == null) { readyError = "UART characteristics missing"; ready.countDown(); return }
            val op = queue.enqueue("d:${t.uuid}") {
                g.setCharacteristicNotification(t, true)
                val d = t.getDescriptor(CCCD) ?: return@enqueue false
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                else { @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } }
            }
            Thread({
                val st = runBlocking { op.await() }
                if (st != GattOpQueue.STATUS_SUCCESS) readyError = "could not enable UART notifications ($st)"
                else log("rnode ble: UART up, mtu $mtu")
                ready.countDown()
            }, "rnode-ble-ready").apply { isDaemon = true }.start()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = queue.complete("d:${d.characteristic.uuid}", status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = queue.complete("w:${c.uuid}", status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { if (c.uuid == NUS_TX) onBytes?.invoke(value) }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { if (c.uuid == NUS_TX) c.value?.let { onBytes?.invoke(it) } }
    }

    override fun write(b: ByteArray) {
        val g = gatt ?: throw IllegalStateException("BLE not connected")
        val c = rx ?: throw IllegalStateException("UART not up")
        var i = 0
        while (i < b.size) {
            val chunk = b.copyOfRange(i, minOf(i + chunkSize, b.size))
            val op = queue.enqueue("w:${c.uuid}") {
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                else { @Suppress("DEPRECATION") run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = chunk; g.writeCharacteristic(c) } }
            }
            val st = runBlocking { op.await() }
            if (st != GattOpQueue.STATUS_SUCCESS) throw IllegalStateException("BLE write failed ($st)")
            i += chunk.size
        }
    }

    override fun close() {
        closed = true
        queue.close()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
}
