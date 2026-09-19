package net.meshsat.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.meshsat.android.ble.GattOpQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * One GATT operation at a time (MESHSAT-1236): Android drops an operation issued while
 * another is in flight, which is how the fromNum subscription used to go missing.
 */
class GattOpQueueTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the next operation starts only after the previous one completed`() = runBlocking {
        val queue = GattOpQueue(scope, timeoutMs = 2_000)
        val started = Collections.synchronizedList(mutableListOf<String>())
        val first = queue.enqueue("w:a") { started.add("a"); true }
        val second = queue.enqueue("w:b") { started.add("b"); true }

        Thread.sleep(100)
        assertEquals(listOf("a"), started.toList())

        queue.complete("w:a", 0)
        assertEquals(0, first.await())
        Thread.sleep(100)
        assertEquals(listOf("a", "b"), started.toList())

        queue.complete("w:b", 0)
        assertEquals(0, second.await())
    }

    @Test
    fun `an operation the stack refuses fails without blocking the queue`() = runBlocking {
        val queue = GattOpQueue(scope, timeoutMs = 2_000)
        val refused = queue.enqueue("w:a") { false }
        val next = queue.enqueue("w:b") { true }
        assertEquals(GattOpQueue.STATUS_REFUSED, refused.await())
        Thread.sleep(50)
        queue.complete("w:b", 0)
        assertEquals(0, next.await())
    }

    @Test
    fun `an operation that never completes times out and the queue moves on`() = runBlocking {
        val queue = GattOpQueue(scope, timeoutMs = 200)
        val stuck = queue.enqueue("w:a") { true }
        val next = queue.enqueue("w:b") { true }
        assertEquals(GattOpQueue.STATUS_TIMEOUT, stuck.await())
        Thread.sleep(50)
        queue.complete("w:b", 0)
        assertEquals(0, next.await())
    }

    @Test
    fun `a late callback for another key does not complete the operation in flight`() = runBlocking {
        val queue = GattOpQueue(scope, timeoutMs = 300)
        val op = queue.enqueue("w:b") { true }
        Thread.sleep(50)
        queue.complete("w:a", 0)
        assertEquals(GattOpQueue.STATUS_TIMEOUT, op.await())
    }

    @Test
    fun `close fails what is queued`() = runBlocking {
        val queue = GattOpQueue(scope, timeoutMs = 2_000)
        val inFlight = queue.enqueue("w:a") { true }
        val queued = queue.enqueue("w:b") { true }
        Thread.sleep(50)
        queue.close()
        assertEquals(GattOpQueue.STATUS_CLOSED, inFlight.await())
        assertEquals(GattOpQueue.STATUS_CLOSED, queued.await())
        assertEquals(GattOpQueue.STATUS_CLOSED, queue.enqueue("w:c") { true }.await())
    }
}
