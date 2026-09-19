package net.meshsat.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import java.util.UUID

/**
 * GATT writes across API levels. From Android 13 the value travels with the call; before,
 * it had to be set on the shared characteristic object first, which races when two
 * writes to the same characteristic overlap. Each returns true if the stack accepted the
 * operation; its outcome arrives in the matching callback.
 */
@SuppressLint("MissingPermission")
internal object GattCompat {
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun write(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(c)
        }

    fun writeDescriptor(gatt: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(d)
        }

    /**
     * Queue the local registration and the CCCD write that turn notifications on or off.
     * The returned operation completes when the peripheral acknowledged the CCCD.
     */
    fun setNotify(queue: GattOpQueue, gatt: BluetoothGatt, c: BluetoothGattCharacteristic, on: Boolean): GattOpQueue.Op {
        val cccd = c.getDescriptor(CCC_DESCRIPTOR)
            ?: return queue.enqueue("d:${c.uuid}") { false }
        val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        return queue.enqueue("d:${c.uuid}") {
            gatt.setCharacteristicNotification(c, on) && writeDescriptor(gatt, cccd, value)
        }
    }

    fun read(queue: GattOpQueue, gatt: BluetoothGatt, c: BluetoothGattCharacteristic): GattOpQueue.Op =
        queue.enqueue("r:${c.uuid}") { gatt.readCharacteristic(c) }
}
