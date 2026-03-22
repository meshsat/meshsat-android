@file:SuppressLint("MissingPermission")

package com.cubeos.meshsat.reticulum

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reticulum interface as a BLE peripheral (GATT server).
 *
 * Advertises a custom Reticulum BLE service so other devices (phones,
 * laptops, IoT) can connect and exchange Reticulum packets without
 * needing Meshtastic hardware.
 *
 * Protocol:
 * - Service UUID: custom Reticulum service
 * - RX characteristic: central writes packets to this (WRITE)
 * - TX characteristic: peripheral notifies packets on this (NOTIFY)
 * - Packets are raw RNS wire format (no additional framing)
 * - Max packet size limited by negotiated ATT MTU (default 20B, can be up to 512B)
 *
 * [MESHSAT-269]
 */
class RnsBlePeripheralInterface(
    private val context: Context,
    private val scope: CoroutineScope,
    override val interfaceId: String = "ble_peripheral_rns_0",
) : RnsInterface {

    companion object {
        private const val TAG = "RnsBlePeripheral"

        // Custom Reticulum BLE service UUID
        val SERVICE_UUID: UUID = UUID.fromString("a4c1b2d3-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        val TX_CHAR_UUID: UUID = UUID.fromString("a4c1b2d3-e5f6-4a7b-8c9d-0e1f2a3b4c5e")
        val RX_CHAR_UUID: UUID = UUID.fromString("a4c1b2d3-e5f6-4a7b-8c9d-0e1f2a3b4c5f")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEFAULT_MTU = 20
        const val MAX_MTU = 512
    }

    override val name: String = "BLE Peripheral"
    override val mtu: Int = RnsConstants.MTU
    override val costCents: Int = 0
    override val latencyMs: Int = 50

    private val btManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var receiveCallback: RnsReceiveCallback? = null
    @Volatile private var advertising = false

    // Track connected devices and their negotiated MTUs
    private val connectedDevices = ConcurrentHashMap<String, ConnectedPeer>()

    private data class ConnectedPeer(
        val device: BluetoothDevice,
        var mtu: Int = DEFAULT_MTU,
        var notificationsEnabled: Boolean = false,
    )

    override val isOnline: Boolean
        get() = advertising && connectedDevices.isNotEmpty()

    override fun setReceiveCallback(callback: RnsReceiveCallback?) {
        receiveCallback = callback
    }

    override suspend fun start() {
        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available")
            return
        }

        setupGattServer()
        startAdvertising()
    }

    override suspend fun stop() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
    }

    override suspend fun send(packet: ByteArray): String? {
        if (connectedDevices.isEmpty()) return "no connected peers"
        val txChar = txCharacteristic ?: return "GATT server not ready"
        val server = gattServer ?: return "GATT server not started"

        // Notify all connected devices with notifications enabled
        var sent = 0
        connectedDevices.values.forEach { peer ->
            if (peer.notificationsEnabled) {
                txChar.value = packet
                server.notifyCharacteristicChanged(peer.device, txChar, false)
                sent++
            }
        }

        return if (sent > 0) null else "no peers with notifications enabled"
    }

    // ═══════════════════════════════════════════════════════════════
    // GATT Server setup
    // ═══════════════════════════════════════════════════════════════

    private fun setupGattServer() {
        val server = btManager?.openGattServer(context, gattCallback) ?: run {
            Log.e(TAG, "Failed to open GATT server")
            return
        }
        gattServer = server

        // Create Reticulum service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // TX characteristic (peripheral → central): NOTIFY
        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0, // No permissions needed for notify
        )
        val cccDescriptor = BluetoothGattDescriptor(
            CCC_DESCRIPTOR,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        txChar.addDescriptor(cccDescriptor)
        service.addCharacteristic(txChar)
        txCharacteristic = txChar

        // RX characteristic (central → peripheral): WRITE
        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(rxChar)

        server.addService(service)
        Log.i(TAG, "GATT server configured with Reticulum service")
    }

    // ═══════════════════════════════════════════════════════════════
    // BLE Advertising
    // ═══════════════════════════════════════════════════════════════

    private fun startAdvertising() {
        val advertiser = btAdapter?.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BLE advertising not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (advertising) {
            btAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            advertising = false
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            Log.i(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.e(TAG, "BLE advertising failed: error $errorCode")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GATT Server callbacks
    // ═══════════════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices[device.address] = ConnectedPeer(device)
                    Log.i(TAG, "Peer connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device.address)
                    Log.i(TAG, "Peer disconnected: ${device.address}")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            connectedDevices[device.address]?.mtu = mtu
            Log.d(TAG, "MTU changed for ${device.address}: $mtu")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid == RX_CHAR_UUID && value != null) {
                // Received a Reticulum packet from the central
                if (value.size >= RnsTcpInterface.HEADER_MINSIZE) {
                    receiveCallback?.onReceive(interfaceId, value)
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid == CCC_DESCRIPTOR) {
                val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                connectedDevices[device.address]?.notificationsEnabled = enabled
                Log.d(TAG, "Notifications ${if (enabled) "enabled" else "disabled"} for ${device.address}")
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid == CCC_DESCRIPTOR) {
                val peer = connectedDevices[device.address]
                val value = if (peer?.notificationsEnabled == true)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }
    }

    /** Number of currently connected peers. */
    fun connectedPeerCount(): Int = connectedDevices.size
}
