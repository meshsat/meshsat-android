package net.meshsat.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.meshsat.android.ble.IridiumPipeContract.NodeStats
import net.meshsat.android.ble.IridiumPipeContract.NodeStatus
import net.meshsat.android.ble.IridiumPipeContract.Owner
import net.meshsat.android.ble.IridiumPipeContract.PassWindow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The MeshSat node's BLE Iridium pipe (MESHSAT-1236): a binary-safe serial line to the
 * node's RockBLOCK 9603, on the same connection as its Meshtastic service. It replaces
 * the HC-05 SPP bridge; the 9603 AT driver runs on top of [input] and [output].
 *
 * Subscribing to TX is what takes the modem, and STATUS says who holds it: write only
 * while [owner] is [Owner.Phone], because the node discards bytes from anyone else.
 * While the node's own logic holds the modem, [owner] is [Owner.Node] and the app waits.
 */
@SuppressLint("MissingPermission")
class IridiumBlePipe internal constructor(
    private val gatt: BluetoothGatt,
    private val queue: GattOpQueue,
    service: BluetoothGattService,
    private val chunkSize: () -> Int,
) {
    private val rx: BluetoothGattCharacteristic? = service.getCharacteristic(UUID.fromString(IridiumPipeContract.RX_UUID))
    private val tx: BluetoothGattCharacteristic? = service.getCharacteristic(UUID.fromString(IridiumPipeContract.TX_UUID))
    private val statusChar: BluetoothGattCharacteristic? = service.getCharacteristic(UUID.fromString(IridiumPipeContract.STATUS_UUID))
    private val statsChar: BluetoothGattCharacteristic? = service.getCharacteristic(UUID.fromString(IridiumPipeContract.STATS_UUID))
    private val passChar: BluetoothGattCharacteristic? = service.getCharacteristic(UUID.fromString(IridiumPipeContract.PASS_UUID))

    /** False when the service lacks RX or TX: nothing here can be used. */
    val usable: Boolean = rx != null && tx != null

    /** The node serves contract v2's STATS (MESHSAT-1378); older firmware does not. */
    val hasStats: Boolean = statsChar != null

    /** The node takes pass windows (contract v2, MESHSAT-1378). */
    val hasPass: Boolean = passChar != null

    private val _owner = MutableStateFlow(Owner.Unknown)
    val owner: StateFlow<Owner> = _owner

    private val _status = MutableStateFlow<NodeStatus?>(null)

    /** The whole STATUS value, flags and signal included on a v2 node; null until read. */
    val status: StateFlow<NodeStatus?> = _status

    private val _stats = MutableStateFlow<NodeStats?>(null)

    /** The node's health as it reports it on STATS; null until read, or on a v1 node. */
    val stats: StateFlow<NodeStats?> = _stats

    val input = PipeInputStream()
    val output: OutputStream = PipeOutputStream(
        chunkSize = chunkSize,
        canWrite = { _owner.value == Owner.Phone },
        sendChunk = ::writeChunkBlocking,
    )

    /** Sees every notified chunk too, for in-band lines such as SBDRING. */
    @Volatile var onReceive: ((ByteArray) -> Unit)? = null

    /** The pass list this link last gave the node, so an unchanged prediction is not rewritten. */
    @Volatile var passesWritten: List<PassWindow>? = null

    /**
     * Take the modem: subscribe to STATUS and TX and wait until STATUS says the phone owns
     * it. Returns false when it did not within [timeoutMs]. A node without STATUS (firmware
     * before a5038c8) counts as owned once TX is subscribed.
     *
     * STATUS saying the node owns it is "wait", never a failure (MESHSAT-1372): with no phone
     * subscribed the node uses the modem itself (firmware a95e2e5), so for up to about a second
     * after the TX subscribe it still reads 01 02, and with a session of its own in flight it
     * releases only after the result, up to 90 s. The owner's STATUS notification is what ends
     * the wait; the service keeps reading STATUS back while it lasts.
     */
    suspend fun claim(timeoutMs: Long = 8_000): Boolean {
        val tx = tx ?: return false
        val status = statusChar
        // One retry each: the first write can fail while the link is still being encrypted,
        // and without STATUS notifications the handover is never heard.
        if (status != null) {
            val watching = (1..2).any { subscribe(status) }
            if (!watching) Log.w(TAG, "Iridium pipe: STATUS notifications could not be enabled; reading it instead")
        }
        val subscribed = (1..2).any { subscribe(tx) }
        if (!subscribed) {
            Log.w(TAG, "Iridium pipe: TX subscription failed")
            return false
        }
        // Subscribing to TX is what hands the modem over: read STATUS after it, so the answer
        // arrives even when its notification does not.
        if (status == null) _owner.value = Owner.Phone else refreshStatus()
        val owner = withTimeoutOrNull(timeoutMs) { owner.first { it == Owner.Phone } }
        if (owner == Owner.Phone) {
            Log.i(TAG, "Iridium pipe: the phone owns the modem")
            return true
        }
        val seen = _owner.value
        Log.i(
            TAG,
            "Iridium pipe: no handover within ${timeoutMs / 1000} s (STATUS says $seen" +
                (if (seen == Owner.Node) ", the node is using the modem, waiting for its release)" else ")"),
        )
        return false
    }

    /**
     * One CCCD write; the ATT status of a failure is logged, because 5 and 15 (authentication,
     * encryption) mean the link is not yet bonded and the write is worth repeating.
     */
    private suspend fun subscribe(c: BluetoothGattCharacteristic): Boolean {
        val result = GattCompat.setNotify(queue, gatt, c, true).await()
        if (result != GattOpQueue.STATUS_SUCCESS) Log.w(TAG, "Iridium pipe: subscribing to ${c.uuid} failed, status $result")
        return result == GattOpQueue.STATUS_SUCCESS
    }

    /** Ask STATUS again, for a notification that may have been lost. */
    fun refreshStatus() {
        statusChar?.let { GattCompat.read(queue, gatt, it) }
    }

    /**
     * Subscribe to STATS and read it once (MESHSAT-1378). Independent of the claim: the node's
     * health is worth showing whoever holds the modem. A node without STATS is left alone.
     */
    suspend fun watchStats() {
        val c = statsChar ?: return
        if (!subscribe(c)) Log.w(TAG, "Iridium pipe: STATS notifications could not be enabled; reading it once")
        GattCompat.read(queue, gatt, c)
    }

    /**
     * Give the node the next pass windows (MESHSAT-1378): one write with response, replacing
     * its list. False when the node has no PASS characteristic or the write failed. Advice for
     * the node's own routing while no phone holds the modem; the node takes it from any client.
     */
    suspend fun writePasses(windows: List<PassWindow>): Boolean {
        val c = passChar ?: return false
        val value = IridiumPipeContract.encodePassList(windows)
        val op = queue.enqueue("w:${c.uuid}") {
            GattCompat.write(gatt, c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        val result = op.await()
        if (result != GattOpQueue.STATUS_SUCCESS) Log.w(TAG, "Iridium pipe: PASS write of ${windows.size} windows failed, status $result")
        return result == GattOpQueue.STATUS_SUCCESS
    }

    /** Hand the modem back to the node. */
    suspend fun release() {
        tx?.let { GattCompat.setNotify(queue, gatt, it, false).await() }
        if (statusChar == null) _owner.value = Owner.None
        input.clear()
    }

    internal fun onValue(uuid: UUID, value: ByteArray) {
        when (uuid.toString()) {
            IridiumPipeContract.TX_UUID -> {
                input.offer(value)
                onReceive?.invoke(value)
            }
            IridiumPipeContract.STATUS_UUID -> {
                val full = IridiumPipeContract.parseStatusFull(value)
                val next = full?.owner ?: Owner.Unknown
                if (next != Owner.Phone && _owner.value == Owner.Phone) input.clear()
                _status.value = full
                _owner.value = next
            }
            IridiumPipeContract.STATS_UUID -> {
                IridiumPipeContract.parseStats(value)?.let { _stats.value = it }
                    ?: Log.w(TAG, "Iridium pipe: STATS value of ${value.size} bytes not understood")
            }
        }
    }

    /** The connection is gone: wake any reader and forget the owner. */
    internal fun close() {
        _owner.value = Owner.Unknown
        input.close()
    }

    /**
     * A drill for MESHSAT-1270: this pipe stops taking writes while the link stays attached,
     * which is the wedge of 20 September and cannot be produced on a sealed node. Everything
     * above this line is the real thing - the driver's counter, the interface going offline, the
     * reconnect. The flag lives on the pipe, and a reconnect makes a new pipe, so recovery ends
     * the drill exactly as it ended the real fault. Set from the loopback-only local API.
     */
    @Volatile
    internal var refuseWrites = false

    private fun writeChunkBlocking(chunk: ByteArray): Boolean {
        if (refuseWrites) {
            Log.w(TAG, "drill: write refused (${chunk.size} bytes)")
            return false
        }
        val rx = rx ?: return false
        val op = queue.enqueue("w:${rx.uuid}") {
            GattCompat.write(gatt, rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        return runBlocking { op.await() } == GattOpQueue.STATUS_SUCCESS
    }

    companion object {
        private const val TAG = "IridiumBlePipe"
        val SERVICE_UUID: UUID = UUID.fromString(IridiumPipeContract.SERVICE_UUID)

        fun isPipeCharacteristic(uuid: UUID): Boolean = uuid.toString().let {
            it == IridiumPipeContract.TX_UUID || it == IridiumPipeContract.STATUS_UUID || it == IridiumPipeContract.STATS_UUID
        }
    }
}

/** What the 9603 driver needs from a serial link. */
interface ModemLink {
    val input: InputStream
    val output: OutputStream

    /** Also receive every chunk as it arrives, e.g. to spot unsolicited lines. */
    fun setReceiver(receiver: ((ByteArray) -> Unit)?)
}

/** The pipe as a [ModemLink] for the 9603 driver. */
fun IridiumBlePipe.asModemLink(): ModemLink = object : ModemLink {
    override val input: InputStream get() = this@asModemLink.input
    override val output: OutputStream get() = this@asModemLink.output
    override fun setReceiver(receiver: ((ByteArray) -> Unit)?) {
        this@asModemLink.onReceive = receiver
    }
}
