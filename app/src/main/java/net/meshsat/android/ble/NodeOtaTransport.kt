package net.meshsat.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The phone's side of Meshtastic's ESP32 OTA loader protocol over Bluetooth (MESHSAT-1672),
 * written from the loader's README and code (github.com/meshtastic/esp32-unified-ota v1.0.1, the
 * loader in app1 of every MeshSat image), not taken from Meshtastic-Android.
 *
 * The loader advertises service [SERVICE_UUID], takes text and data on [WRITE_UUID] and answers on
 * [NOTIFY_UUID], one text line per answer ending in \n:
 * 1. "OTA <size> <sha256 hex>\n" (write with response); the SHA-256 must be the one the node was
 *    handed in AdminMessage.ota_request, or the loader answers "ERR Hash Rejected". It answers
 *    "ERASING", then "OK" once app0 is erased.
 * 2. The image in chunks of at most MTU-3 bytes (and 512), one GATT write per chunk, never ahead:
 *    each chunk is answered "ACK" (the loader drains a 520-byte buffer per answer and drops what
 *    does not fit), the last one "OK" after the loader checked the SHA-256 and set app0 to boot.
 *    It restarts into the new firmware about 2 s later.
 * 3. Any "ERR ..." ends the update. There is no PIN on the loader and one connection at a time.
 */
@SuppressLint("MissingPermission")
class NodeOtaTransport(private val context: Context, private val adapter: BluetoothAdapter) {

    sealed class Step {
        object Connecting : Step()
        object Erasing : Step()
        data class Sending(val sent: Int, val total: Int) : Step()
        object Verifying : Step()
    }

    sealed class Outcome {
        object Done : Outcome()
        /** [loaderHasImage] true once the loader accepted the OTA command: app0 is erased, retry with the same file. */
        data class Failed(val reason: String, val loaderHasImage: Boolean) : Outcome()
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val partial = StringBuilder()

    private sealed class Event {
        data class Connection(val connected: Boolean, val status: Int) : Event()
        data class Services(val status: Int) : Event()
        data class Mtu(val mtu: Int, val status: Int) : Event()
        data class DescriptorWritten(val status: Int) : Event()
        data class Written(val status: Int) : Event()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            events.trySend(Event.Connection(newState == BluetoothProfile.STATE_CONNECTED, status))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            events.trySend(Event.Services(status))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            events.trySend(Event.Mtu(mtu, status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            events.trySend(Event.DescriptorWritten(status))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            events.trySend(Event.Written(status))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onText(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onText(value)
        }
    }

    private fun onText(value: ByteArray) {
        synchronized(partial) {
            for (line in splitLines(partial, String(value, Charsets.US_ASCII))) lines.trySend(line)
        }
    }

    /**
     * Update the node at [address] with [image]; its loader is found by scan ([connectToLoader]). [onStep]
     * reports progress. The node must already have rebooted into its loader with this image's hash.
     */
    suspend fun update(address: String, image: NodeFirmwareImage, onStep: (Step) -> Unit): Outcome {
        onStep(Step.Connecting)
        var gatt: BluetoothGatt? = null
        var accepted = false
        try {
            gatt = connectToLoader(address) ?: return Outcome.Failed(
                "The node's updater did not answer over Bluetooth. Keep the phone close to the node and try again.", false,
            )
            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                ?: return Outcome.Failed("The node's updater was not found on the node.", false)
            val write = service.getCharacteristic(UUID.fromString(WRITE_UUID))
                ?: return Outcome.Failed("The node's updater has no data characteristic.", false)
            val notify = service.getCharacteristic(UUID.fromString(NOTIFY_UUID))
                ?: return Outcome.Failed("The node's updater has no answer characteristic.", false)

            var mtu = 23
            if (gatt.requestMtu(REQUEST_MTU)) {
                (awaitEvent<Event.Mtu>(MTU_TIMEOUT_MS))?.let { if (it.status == BluetoothGatt.GATT_SUCCESS) mtu = it.mtu }
            }
            val chunk = chunkSize(mtu)
            Log.i(TAG, "OTA: link MTU $mtu, chunks of $chunk bytes")

            val cccd = notify.getDescriptor(GattCompat.CCC_DESCRIPTOR)
                ?: return Outcome.Failed("The node's updater cannot send answers.", false)
            if (!gatt.setCharacteristicNotification(notify, true) ||
                !GattCompat.writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                awaitEvent<Event.DescriptorWritten>(OP_TIMEOUT_MS)?.status != BluetoothGatt.GATT_SUCCESS
            ) {
                return Outcome.Failed("Could not subscribe to the node's updater.", false)
            }

            drainLines()
            if (!writeAndWait(gatt, write, otaCommand(image.size, image.sha256Hex).toByteArray(Charsets.US_ASCII), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
                return Outcome.Failed("The node's updater did not take the update command.", false)
            }
            // ERASING, then OK once app0 is erased (several seconds for 3.3 MB).
            while (true) {
                val line = nextLine(ERASE_TIMEOUT_MS) ?: return Outcome.Failed("The node's updater did not finish erasing.", false)
                when (val r = parseReply(line)) {
                    Reply.Erasing -> onStep(Step.Erasing)
                    Reply.Ok -> break
                    is Reply.Error -> return Outcome.Failed("The node refused the update: ${r.text}", false)
                    else -> Log.d(TAG, "OTA: '$line' while erasing")
                }
            }
            accepted = true

            var sent = 0
            val total = image.size
            var lastPercent = -1
            while (sent < total) {
                val end = minOf(sent + chunk, total)
                val part = image.bytes.copyOfRange(sent, end)
                if (!writeAndWait(gatt, write, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                    return Outcome.Failed("A piece of the firmware could not be sent at byte $sent.", true)
                }
                val last = end == total
                if (last) onStep(Step.Verifying)
                val line = nextLine(if (last) VERIFY_TIMEOUT_MS else ACK_TIMEOUT_MS)
                    ?: return Outcome.Failed("The node's updater stopped answering at byte $end.", true)
                when (val r = parseReply(line)) {
                    Reply.Ack -> if (last) return Outcome.Failed("The node's updater wanted more than the whole image.", true)
                    Reply.Ok -> if (!last) return Outcome.Failed("The node's updater ended the transfer early at byte $end.", true)
                    is Reply.Error -> return Outcome.Failed("The node's updater refused the firmware: ${r.text}", true)
                    else -> return Outcome.Failed("The node's updater answered '$line' at byte $end.", true)
                }
                sent = end
                val percent = (sent * 100L / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onStep(Step.Sending(sent, total))
                }
            }
            return Outcome.Done
        } catch (e: SecurityException) {
            return Outcome.Failed("Bluetooth permission is missing: ${e.message}", accepted)
        } finally {
            gatt?.let {
                // The loader's attribute table must not stay cached for the node's address.
                refreshCache(it)
                it.disconnect()
                it.close()
            }
        }
    }

    /**
     * Find the loader by its service first. On MSC-2 unit 0001 (24 Oct 2026) the loader did not
     * answer on the node's own address: four direct connects cost about 55 s before the scan
     * found it. A direct connect to [address] is the fallback when the scan finds nothing, then
     * one more scan, as the loader may still be starting.
     */
    private suspend fun connectToLoader(address: String): BluetoothGatt? {
        scanForLoader(address, SCAN_TIMEOUT_MS)?.let { found ->
            tryConnect(found)?.let { return it }
            Log.w(TAG, "OTA: the loader found by scan (${found.address}) did not take the connection")
        }
        Log.w(TAG, "OTA: no loader in the scan; trying the node's address $address")
        tryConnect(adapter.getRemoteDevice(address))?.let { return it }
        delay(RETRY_PAUSE_MS)
        val again = scanForLoader(address, SECOND_SCAN_TIMEOUT_MS) ?: return null
        return tryConnect(again)
    }

    private suspend fun tryConnect(device: BluetoothDevice): BluetoothGatt? {
        drainEvents()
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return null
        val connected = awaitEvent<Event.Connection>(CONNECT_TIMEOUT_MS)
        if (connected?.connected != true) {
            gatt.close()
            return null
        }
        // The node's own attribute table may be cached for this address: drop it, then discover.
        refreshCache(gatt)
        delay(SETTLE_MS)
        for (round in 1..2) {
            if (!gatt.discoverServices()) break
            val done = awaitEvent<Event.Services>(DISCOVER_TIMEOUT_MS)
            if (done?.status == BluetoothGatt.GATT_SUCCESS && gatt.getService(UUID.fromString(SERVICE_UUID)) != null) return gatt
            refreshCache(gatt)
            delay(SETTLE_MS)
        }
        gatt.disconnect()
        gatt.close()
        return null
    }

    /**
     * The first device advertising the loader's service within [timeoutMs]. A loader on [address]
     * or the address after it (where the board's loader may advertise) is taken at once; another
     * loader only after [OTHER_LOADER_WAIT_MS] with no better one, as two nodes updating side by
     * side are rare.
     */
    private suspend fun scanForLoader(address: String, timeoutMs: Long): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice>()
        val expected = setOf(address.uppercase(), nextAddress(address))
        val other = AtomicReference<BluetoothDevice?>(null)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address.uppercase() in expected) found.complete(result.device) else other.compareAndSet(null, result.device)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        return try {
            withTimeoutOrNull(timeoutMs) {
                var otherSince = 0L
                while (!found.isCompleted) {
                    val o = other.get()
                    if (o != null) {
                        if (otherSince == 0L) otherSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - otherSince >= OTHER_LOADER_WAIT_MS) return@withTimeoutOrNull o
                    }
                    delay(100)
                }
                found.await()
            }?.also { Log.i(TAG, "OTA: the loader advertises as ${it.address} (node $address)") }
        } finally {
            scanner.stopScan(cb)
        }
    }

    private suspend fun writeAndWait(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean {
        repeat(WRITE_BUSY_RETRIES) {
            if (GattCompat.write(gatt, c, value, type)) {
                // Not every stack reports a write without response; the loader's ACK is the flow
                // control, so such a report is only waited for briefly.
                val waitMs = if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) NO_RESPONSE_REPORT_MS else OP_TIMEOUT_MS
                val done = awaitEvent<Event.Written>(waitMs)
                // A write without response is reported once handed to the controller; a missing
                // report is not a failure, the loader's answer decides.
                return done == null || done.status == BluetoothGatt.GATT_SUCCESS
            }
            delay(WRITE_BUSY_PAUSE_MS)
        }
        return false
    }

    private suspend inline fun <reified T : Event> awaitEvent(timeoutMs: Long): T? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val e = events.receive()
            if (e is T) return@withTimeoutOrNull e
            if (e is Event.Connection && !e.connected) return@withTimeoutOrNull null
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private suspend fun nextLine(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) { lines.receive() }

    private fun drainEvents() {
        while (events.tryReceive().isSuccess) Unit
    }

    private fun drainLines() {
        while (lines.tryReceive().isSuccess) Unit
    }

    private fun refreshCache(g: BluetoothGatt): Boolean = try {
        g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }

    sealed class Reply {
        object Ok : Reply()
        object Ack : Reply()
        object Erasing : Reply()
        data class Error(val text: String) : Reply()
        data class Other(val text: String) : Reply()
    }

    companion object {
        private const val TAG = "NodeOta"

        const val SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        const val WRITE_UUID = "62ec0272-3ec5-11eb-b378-0242ac130005"
        const val NOTIFY_UUID = "62ec0272-3ec5-11eb-b378-0242ac130003"

        /** The loader asks for 517; its receive buffer is 520 bytes. */
        const val REQUEST_MTU = 517
        const val MAX_CHUNK = 512

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        private const val MTU_TIMEOUT_MS = 5_000L
        private const val OP_TIMEOUT_MS = 5_000L
        private const val ERASE_TIMEOUT_MS = 60_000L
        private const val ACK_TIMEOUT_MS = 10_000L
        private const val VERIFY_TIMEOUT_MS = 30_000L
        private const val SCAN_TIMEOUT_MS = 45_000L
        private const val SECOND_SCAN_TIMEOUT_MS = 30_000L
        private const val OTHER_LOADER_WAIT_MS = 3_000L
        private const val RETRY_PAUSE_MS = 3_000L
        private const val SETTLE_MS = 600L
        private const val WRITE_BUSY_RETRIES = 50
        private const val WRITE_BUSY_PAUSE_MS = 20L
        private const val NO_RESPONSE_REPORT_MS = 300L

        fun otaCommand(size: Int, sha256Hex: String): String = "OTA $size ${sha256Hex.lowercase()}\n"

        /** The Bluetooth address one above [address] ("44:1B:F6:F9:41:7D" -> "44:1B:F6:F9:41:7E"), or "" if it is not one. */
        fun nextAddress(address: String): String {
            val parts = address.split(":")
            if (parts.size != 6) return ""
            val value = parts.joinToString("").toLongOrNull(16) ?: return ""
            val next = (value + 1) and 0xFFFFFFFFFFFFL
            return (0 until 6).joinToString(":") { i -> "%02X".format((next shr (8 * (5 - i))) and 0xFF) }
        }

        /** One GATT write per chunk, at most MTU-3 bytes and 512. */
        fun chunkSize(mtu: Int): Int = (mtu - 3).coerceIn(20, MAX_CHUNK)

        fun parseReply(line: String): Reply {
            val t = line.trim()
            return when {
                t == "OK" || t.startsWith("OK ") -> Reply.Ok
                t == "ACK" -> Reply.Ack
                t == "ERASING" -> Reply.Erasing
                t.startsWith("ERR") -> Reply.Error(t.removePrefix("ERR").trim().ifEmpty { "unspecified error" })
                else -> Reply.Other(t)
            }
        }

        /** Append [text] to [partial] and return every complete line, without its line end. */
        fun splitLines(partial: StringBuilder, text: String): List<String> {
            partial.append(text)
            val out = mutableListOf<String>()
            while (true) {
                val nl = partial.indexOf("\n")
                if (nl < 0) break
                val line = partial.substring(0, nl).trimEnd('\r')
                partial.delete(0, nl + 1)
                if (line.isNotBlank()) out.add(line)
            }
            return out
        }
    }
}
