package net.meshsat.android.ble

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes the GATT operations of one connection (MESHSAT-1236).
 *
 * Android runs one GATT operation per connection at a time: one issued while another
 * is in flight is refused or silently dropped. Every write, descriptor write and read
 * on the node's connection goes through here. [enqueue] adds an operation, the GATT
 * callback of that operation calls [complete], and the next one starts. An operation
 * the stack refuses, or that does not complete within [timeoutMs], fails on its own
 * without holding up the ones behind it. [enqueueUrgent] puts an operation ahead of every
 * queued one (never ahead of the one in flight): the 9704's MO segment answer, which the
 * modem waits for only about 360 ms (MESHSAT-1674).
 */
class GattOpQueue(
    scope: CoroutineScope,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "GattOpQueue"

        // Long enough for Android's pairing dialog: the first protected operation on a node
        // with a BLE PIN waits while a person types it, and the stack retries it afterwards.
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val STATUS_SUCCESS = 0
        const val STATUS_REFUSED = -1
        const val STATUS_TIMEOUT = -2
        const val STATUS_CLOSED = -3

        /**
         * How long Android may refuse every operation before the client counts as dead. The
         * first subscriptions after a connect are refused within milliseconds while the link
         * comes up, and the next operation is accepted; a minute is far beyond that.
         */
        const val DEAD_AFTER_MS = 60_000L

        /** And at least this many refusals in that time, so one slow retry is not enough. */
        const val DEAD_AFTER_REFUSALS = 3
    }

    /**
     * Called once when Android has refused every operation for [DEAD_AFTER_MS], at least
     * [DEAD_AFTER_REFUSALS] of them, and accepted none in between. That is a dead client: on
     * 7 Nov 2026 a switch of the phone's user restarted Android's Bluetooth under the app, the
     * client kept a binder to the old Bluetooth process (DeadObjectException on every call), no
     * disconnect callback ever came, and the node link said connected while nothing went
     * through for an hour (MESHSAT-1816). The owner of the connection drops it and connects again.
     */
    @Volatile var onDead: (() -> Unit)? = null

    // Touched only by the loop in init, one operation at a time.
    private var refusedSinceMs = 0L
    private var refusedCount = 0
    private var deadReported = false

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
    private val urgent = Channel<Op>(Channel.UNLIMITED)

    @Volatile private var current: Op? = null

    @Volatile private var closed = false

    init {
        scope.launch {
            while (true) {
                // select prefers its first clause when both have an operation waiting.
                val op = urgent.tryReceive().getOrNull() ?: select<Op?> {
                    urgent.onReceiveCatching { it.getOrNull() }
                    channel.onReceiveCatching { it.getOrNull() }
                } ?: break
                run(op)
            }
            // Closed: whatever is still queued fails rather than waiting for ever.
            for (ch in listOf(urgent, channel)) {
                while (true) ch.tryReceive().getOrNull()?.done?.complete(STATUS_CLOSED) ?: break
            }
        }
    }

    /** Queue [start], which issues the operation and returns false if the stack refused it. */
    fun enqueue(key: String, start: () -> Boolean): Op = offer(channel, key, start)

    /** As [enqueue], ahead of every operation still waiting. */
    fun enqueueUrgent(key: String, start: () -> Boolean): Op = offer(urgent, key, start)

    private fun offer(ch: Channel<Op>, key: String, start: () -> Boolean): Op {
        val op = Op(key, start)
        if (closed || ch.trySend(op).isFailure) op.done.complete(STATUS_CLOSED)
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
        urgent.close()
        current?.done?.complete(STATUS_CLOSED)
    }

    private suspend fun run(op: Op) {
        if (closed) {
            op.done.complete(STATUS_CLOSED)
            return
        }
        current = op
        var thrown: Exception? = null
        val started = try {
            op.start()
        } catch (e: Exception) {
            thrown = e
            false
        }
        if (!started) {
            // Before the caller hears of the refusal, so the verdict is in when it does.
            noteRefused(op.key, thrown)
            op.done.complete(STATUS_REFUSED)
        } else {
            refusedCount = 0
            if (withTimeoutOrNull(timeoutMs) { op.done.await() } == null) {
                op.done.complete(STATUS_TIMEOUT)
            }
        }
        current = null
    }

    private fun noteRefused(key: String, thrown: Exception?) {
        val now = nowMs()
        if (refusedCount == 0) {
            refusedSinceMs = now
            // Once per run of refusals, so a dead client does not flood the log.
            Log.w(TAG, "Android refused $key" + (thrown?.let { ": ${it.javaClass.simpleName} ${it.message.orEmpty()}" } ?: ""))
        }
        refusedCount++
        if (!deadReported && refusedCount >= DEAD_AFTER_REFUSALS && now - refusedSinceMs >= DEAD_AFTER_MS) {
            deadReported = true
            Log.w(TAG, "Android refused all $refusedCount operations for ${(now - refusedSinceMs) / 1000} s; the client is dead")
            onDead?.invoke()
        }
    }
}
