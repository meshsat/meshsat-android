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
import net.meshsat.android.ble.IridiumPipeContract.Owner
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
    private val status: BluetoothGattCharacteristic? = service.getCharacteristic(UUID.fromString(IridiumPipeContract.STATUS_UUID))

    /** False when the service lacks RX or TX: nothing here can be used. */
    val usable: Boolean = rx != null && tx != null

    private val _owner = MutableStateFlow(Owner.Unknown)
    val owner: StateFlow<Owner> = _owner

    val input = PipeInputStream()
    val output: OutputStream = PipeOutputStream(
        chunkSize = chunkSize,
        canWrite = { _owner.value == Owner.Phone },
        sendChunk = ::writeChunkBlocking,
    )

    /** Sees every notified chunk too, for in-band lines such as SBDRING. */
    @Volatile var onReceive: ((ByteArray) -> Unit)? = null

    /**
     * Take the modem: subscribe to STATUS and TX and wait until STATUS says the phone owns
     * it. Returns false if the node's own logic holds it or nothing answered in time. A
     * node without STATUS (firmware before a5038c8) counts as owned once TX is subscribed.
     */
    suspend fun claim(timeoutMs: Long = 8_000): Boolean {
        val tx = tx ?: return false
        val status = status
        // One retry each: the first write can fail while the link is still being encrypted,
        // and without STATUS notifications the handover is never heard.
        if (status != null) {
            val watching = (1..2).any { GattCompat.setNotify(queue, gatt, status, true).await() == GattOpQueue.STATUS_SUCCESS }
            if (!watching) Log.w(TAG, "Iridium pipe: STATUS notifications could not be enabled; reading it instead")
        }
        val subscribed = (1..2).any { GattCompat.setNotify(queue, gatt, tx, true).await() == GattOpQueue.STATUS_SUCCESS }
        if (!subscribed) {
            Log.w(TAG, "Iridium pipe: TX subscription failed")
            return false
        }
        // Subscribing to TX is what hands the modem over: read STATUS after it, so the answer
        // arrives even when its notification does not.
        if (status == null) _owner.value = Owner.Phone else refreshStatus()
        val owner = withTimeoutOrNull(timeoutMs) { owner.first { it == Owner.Phone || it == Owner.Node } }
        Log.i(TAG, "Iridium pipe: claim answered ${owner ?: "nothing within ${timeoutMs / 1000} s"}")
        return owner == Owner.Phone
    }

    /** Ask STATUS again, for a notification that may have been lost. */
    fun refreshStatus() {
        status?.let { GattCompat.read(queue, gatt, it) }
    }

    /** Hand the modem back to the node. */
    suspend fun release() {
        tx?.let { GattCompat.setNotify(queue, gatt, it, false).await() }
        if (status == null) _owner.value = Owner.None
        input.clear()
    }

    internal fun onValue(uuid: UUID, value: ByteArray) {
        when (uuid.toString()) {
            IridiumPipeContract.TX_UUID -> {
                input.offer(value)
                onReceive?.invoke(value)
            }
            IridiumPipeContract.STATUS_UUID -> {
                val next = IridiumPipeContract.parseStatus(value)
                if (next != Owner.Phone && _owner.value == Owner.Phone) input.clear()
                _owner.value = next
            }
        }
    }

    /** The connection is gone: wake any reader and forget the owner. */
    internal fun close() {
        _owner.value = Owner.Unknown
        input.close()
    }

    private fun writeChunkBlocking(chunk: ByteArray): Boolean {
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
            it == IridiumPipeContract.TX_UUID || it == IridiumPipeContract.STATUS_UUID
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
