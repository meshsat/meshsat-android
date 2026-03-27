package com.cubeos.meshsat.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Meshtastic BLE connection manager.
 *
 * Connects to a Meshtastic radio via BLE and exchanges protobuf-framed messages.
 * Uses the official Meshtastic BLE service UUID and characteristics.
 */
@SuppressLint("MissingPermission")
class MeshtasticBle(private val context: Context) {

    companion object {
        // Meshtastic BLE UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547de15e6")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_MTU = 512
        private const val WRITE_CHUNK_SIZE = 512
    }

    enum class State { Disconnected, Scanning, Connecting, Connected }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null

    /** Last connected BLE address, used for reconnect(). */
    @Volatile var lastAddress: String? = null
        private set

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _scanResults = MutableSharedFlow<BluetoothDevice>(extraBufferCapacity = 16)
    val scanResults: SharedFlow<BluetoothDevice> = _scanResults

    private val _receivedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val receivedData: SharedFlow<ByteArray> = _receivedData

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error

    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi

    private val _myInfo = MutableStateFlow<MeshtasticProtocol.MyNodeInfo?>(null)
    val myInfo: StateFlow<MeshtasticProtocol.MyNodeInfo?> = _myInfo

    private val _nodes = MutableStateFlow<List<MeshtasticProtocol.MeshNodeInfo>>(emptyList())
    val nodes: StateFlow<List<MeshtasticProtocol.MeshNodeInfo>> = _nodes

    // --- Radio config state (populated by FromRadio.config responses) ---
    private val _ownerName = MutableStateFlow("")
    val ownerName: StateFlow<String> = _ownerName
    private val _ownerShortName = MutableStateFlow("")
    val ownerShortName: StateFlow<String> = _ownerShortName

    private val _loraConfig = MutableStateFlow<com.geeksville.mesh.ConfigProtos.Config.LoRaConfig?>(null)
    val loraConfig: StateFlow<com.geeksville.mesh.ConfigProtos.Config.LoRaConfig?> = _loraConfig
    private val _deviceConfig = MutableStateFlow<com.geeksville.mesh.ConfigProtos.Config.DeviceConfig?>(null)
    val deviceConfig: StateFlow<com.geeksville.mesh.ConfigProtos.Config.DeviceConfig?> = _deviceConfig
    private val _positionConfig = MutableStateFlow<com.geeksville.mesh.ConfigProtos.Config.PositionConfig?>(null)
    val positionConfig: StateFlow<com.geeksville.mesh.ConfigProtos.Config.PositionConfig?> = _positionConfig
    private val _bluetoothConfig = MutableStateFlow<com.geeksville.mesh.ConfigProtos.Config.BluetoothConfig?>(null)
    val bluetoothConfig: StateFlow<com.geeksville.mesh.ConfigProtos.Config.BluetoothConfig?> = _bluetoothConfig
    private val _networkConfig = MutableStateFlow<com.geeksville.mesh.ConfigProtos.Config.NetworkConfig?>(null)
    val networkConfig: StateFlow<com.geeksville.mesh.ConfigProtos.Config.NetworkConfig?> = _networkConfig
    private val _powerConfig = MutableStateFlow<com.geeksville.mesh.ConfigProtos.Config.PowerConfig?>(null)
    val powerConfig: StateFlow<com.geeksville.mesh.ConfigProtos.Config.PowerConfig?> = _powerConfig
    private val _displayConfig = MutableStateFlow<com.geeksville.mesh.ConfigProtos.Config.DisplayConfig?>(null)
    val displayConfig: StateFlow<com.geeksville.mesh.ConfigProtos.Config.DisplayConfig?> = _displayConfig

    private val _channels = MutableStateFlow<List<MeshtasticProtocol.MeshChannel>>(emptyList())
    val channels: StateFlow<List<MeshtasticProtocol.MeshChannel>> = _channels

    private val _deviceMetadata = MutableStateFlow<MeshtasticProtocol.MeshDeviceMetadata?>(null)
    val deviceMetadata: StateFlow<MeshtasticProtocol.MeshDeviceMetadata?> = _deviceMetadata

    fun setOwner(longName: String, shortName: String) {
        _ownerName.value = longName
        _ownerShortName.value = shortName
    }

    fun setConfig(config: com.geeksville.mesh.ConfigProtos.Config) {
        when {
            config.hasLora() -> _loraConfig.value = config.lora
            config.hasDevice() -> _deviceConfig.value = config.device
            config.hasPosition() -> _positionConfig.value = config.position
            config.hasBluetooth() -> _bluetoothConfig.value = config.bluetooth
            config.hasNetwork() -> _networkConfig.value = config.network
            config.hasPower() -> _powerConfig.value = config.power
            config.hasDisplay() -> _displayConfig.value = config.display
        }
    }

    fun addChannel(channel: MeshtasticProtocol.MeshChannel) {
        val current = _channels.value.toMutableList()
        current.removeAll { it.index == channel.index }
        current.add(channel)
        current.sortBy { it.index }
        _channels.value = current
    }

    fun setDeviceMetadata(metadata: MeshtasticProtocol.MeshDeviceMetadata) {
        _deviceMetadata.value = metadata
    }

    fun setMyInfo(info: MeshtasticProtocol.MyNodeInfo) { _myInfo.value = info }
    fun addNodeInfo(info: MeshtasticProtocol.MeshNodeInfo) {
        val current = _nodes.value.toMutableList()
        val existing = current.find { it.nodeNum == info.nodeNum }
        current.removeAll { it.nodeNum == info.nodeNum }
        // Merge: preserve identity fields from existing when new values are empty/zero.
        // Config downloads often send partial NodeInfo — only overwrite with non-empty data.
        val merged = info.copy(
            longName = info.longName.ifEmpty { existing?.longName ?: "" },
            shortName = info.shortName.ifEmpty { existing?.shortName ?: "" },
            macaddr = info.macaddr.ifEmpty { existing?.macaddr ?: "" },
            hwModel = if (info.hwModel != 0) info.hwModel else (existing?.hwModel ?: 0),
            batteryLevel = if (info.batteryLevel >= 0) info.batteryLevel else (existing?.batteryLevel ?: -1),
            lastHeard = if (info.lastHeard > 0) info.lastHeard else System.currentTimeMillis(),
        )
        current.add(merged)
        _nodes.value = current
    }

    /** Update lastHeard timestamp for a node (called on any RX packet). */
    fun touchNode(nodeNum: Long) {
        val current = _nodes.value.toMutableList()
        val idx = current.indexOfFirst { it.nodeNum == nodeNum }
        if (idx >= 0) {
            current[idx] = current[idx].copy(lastHeard = System.currentTimeMillis())
            _nodes.value = current
        }
    }

    /** Update battery level for a node. */
    fun updateNodeBattery(nodeNum: Long, batteryLevel: Int) {
        val current = _nodes.value.toMutableList()
        val idx = current.indexOfFirst { it.nodeNum == nodeNum }
        if (idx >= 0) {
            current[idx] = current[idx].copy(batteryLevel = batteryLevel, lastHeard = System.currentTimeMillis())
            _nodes.value = current
        }
    }

    private var writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    // --- BLE Scan ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scope.launch { _scanResults.emit(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            scope.launch { _error.emit("BLE scan failed: error $errorCode") }
            _state.value = State.Disconnected
        }
    }

    fun startScan(timeoutMs: Long = 10_000) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            scope.launch { _error.emit("Bluetooth not available") }
            return
        }

        _state.value = State.Scanning

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)

        scope.launch {
            delay(timeoutMs)
            stopScan()
        }
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_state.value == State.Scanning) {
            _state.value = State.Disconnected
        }
    }

    // --- BLE Connect ---

    fun connect(device: BluetoothDevice) {
        stopScan()
        _state.value = State.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connect(address: String) {
        lastAddress = address
        val device = adapter?.getRemoteDevice(address) ?: run {
            scope.launch { _error.emit("Invalid BLE address: $address") }
            return
        }
        connect(device)
    }

    /** Reconnect to the last-known BLE address. No-op if no address was previously used. */
    fun reconnect() {
        val addr = lastAddress
        if (addr != null) {
            connect(addr)
        } else {
            scope.launch { _error.emit("No previous BLE address for reconnect") }
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        toRadioChar = null
        fromRadioChar = null
        _state.value = State.Disconnected
    }

    // --- Send to Radio ---

    fun sendToRadio(data: ByteArray) {
        val char = toRadioChar ?: run {
            scope.launch { _error.emit("Not connected — toRadio characteristic unavailable") }
            return
        }

        // Meshtastic BLE protocol: 4-byte header [0x94 0xc3 MSB LSB] + payload
        val framed = byteArrayOf(
            0x94.toByte(), 0xc3.toByte(),
            ((data.size shr 8) and 0xFF).toByte(),
            (data.size and 0xFF).toByte(),
        ) + data

        // Chunk if needed
        val chunks = framed.toList().chunked(WRITE_CHUNK_SIZE).map { it.toByteArray() }
        synchronized(writeQueue) {
            chunks.forEach { writeQueue.addLast(it) }
        }
        drainWriteQueue()
    }

    private fun drainWriteQueue() {
        if (writing) return
        val chunk: ByteArray
        synchronized(writeQueue) {
            chunk = writeQueue.removeFirstOrNull() ?: return
            writing = true
        }
        toRadioChar?.let { char ->
            char.value = chunk
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt?.writeCharacteristic(char)
        }
    }

    /** Read remote RSSI. Result arrives via onReadRemoteRssi callback → _rssi StateFlow. */
    fun readRssi() {
        gatt?.readRemoteRssi()
    }

    // --- GATT Callback ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestMtu(MAX_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = State.Disconnected
                    scope.launch { _error.emit("BLE disconnected (status=$status)") }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scope.launch { _error.emit("Service discovery failed: $status") }
                disconnect()
                return
            }

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                scope.launch { _error.emit("Meshtastic BLE service not found") }
                disconnect()
                return
            }

            toRadioChar = service.getCharacteristic(TO_RADIO_UUID)
            fromRadioChar = service.getCharacteristic(FROM_RADIO_UUID)

            if (toRadioChar == null || fromRadioChar == null) {
                scope.launch { _error.emit("Meshtastic characteristics not found") }
                disconnect()
                return
            }

            // Enable notifications on fromRadio
            g.setCharacteristicNotification(fromRadioChar, true)
            fromRadioChar?.getDescriptor(CCC_DESCRIPTOR)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            }

            // Also enable notifications on fromNum (notify-on-new-data)
            service.getCharacteristic(FROM_NUM_UUID)?.let { fromNum ->
                g.setCharacteristicNotification(fromNum, true)
                fromNum.getDescriptor(CCC_DESCRIPTOR)?.let { desc ->
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }

            _state.value = State.Connected
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            when (characteristic.uuid) {
                FROM_RADIO_UUID -> {
                    characteristic.value?.let { data ->
                        scope.launch { _receivedData.emit(data) }
                    }
                }
                FROM_NUM_UUID -> {
                    // fromNum changed — read fromRadio to get the data
                    fromRadioChar?.let { g.readCharacteristic(it) }
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == FROM_RADIO_UUID) {
                characteristic.value?.let { data ->
                    if (data.isNotEmpty()) {
                        scope.launch { _receivedData.emit(data) }
                        // Keep reading until empty (drain the radio's buffer)
                        g.readCharacteristic(characteristic)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(writeQueue) { writing = false }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scope.launch { _error.emit("BLE write failed: $status") }
                return
            }
            drainWriteQueue()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }
    }
}
