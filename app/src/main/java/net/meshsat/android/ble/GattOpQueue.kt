package net.meshsat.android.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes the GATT operations of one connection (MESHSAT-1236).
 *
 * Android runs one GATT operation per connection at a time: one issued while another
 * is in flight is refused or silently dropped. Every write, descriptor write and read
 * on the node's connection goes through here. [enqueue] adds an operation, the GATT
 * callback of that operation calls [complete], and the next one starts. An operation
 * the stack refuses, or that does not complete within [timeoutMs], fails on its own
 * without holding up the ones behind it.
 */
class GattOpQueue(
    scope: CoroutineScope,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        // Long enough for Android's pairing dialog: the first protected operation on a node
        // with a BLE PIN waits while a person types it, and the stack retries it afterwards.
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val STATUS_SUCCESS = 0
        const val STATUS_REFUSED = -1
        const val STATUS_TIMEOUT = -2
        const val STATUS_CLOSED = -3
    }

    /**
     * One operation. [key] names what its callback will report (e.g. "w:<uuid>"), so a
     * late callback for an operation that already timed out cannot complete the next one.
     */
    class Op internal constructor(val key: String, internal val start: () -> Boolean) {
        internal val done = CompletableDeferred<Int>()

        /** The GATT status of the operation, or one of the negative STATUS_ codes. */
        suspend fun await(): Int = done.await()
    }

    private val channel = Channel<Op>(Channel.UNLIMITED)

    @Volatile private var current: Op? = null

    @Volatile private var closed = false

    init {
        scope.launch {
            for (op in channel) run(op)
        }
    }

    /** Queue [start], which issues the operation and returns false if the stack refused it. */
    fun enqueue(key: String, start: () -> Boolean): Op {
        val op = Op(key, start)
        if (closed || channel.trySend(op).isFailure) op.done.complete(STATUS_CLOSED)
        return op
    }

    /** Called from the GATT callback that finishes the operation reported as [key]. */
    fun complete(key: String, status: Int) {
        val op = current ?: return
        if (op.key == key) op.done.complete(status)
    }

    /** Fail everything still queued; used when the connection goes away. */
    fun close() {
        closed = true
        channel.close()
        current?.done?.complete(STATUS_CLOSED)
    }

    private suspend fun run(op: Op) {
        if (closed) {
            op.done.complete(STATUS_CLOSED)
            return
        }
        current = op
        val started = try {
            op.start()
        } catch (_: Exception) {
            false
        }
        if (!started) {
            op.done.complete(STATUS_REFUSED)
        } else if (withTimeoutOrNull(timeoutMs) { op.done.await() } == null) {
            op.done.complete(STATUS_TIMEOUT)
        }
        current = null
    }
}
