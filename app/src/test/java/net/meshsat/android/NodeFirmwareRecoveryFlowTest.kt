package net.meshsat.android

import android.content.ContextWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.ble.NodeFirmwareImage
import net.meshsat.android.ble.NodeFirmwareKnown
import net.meshsat.android.ble.NodeFirmwareUpdater
import net.meshsat.android.ble.NodeFirmwareUpdater.State
import net.meshsat.android.ble.NodeOtaTransport.Outcome
import net.meshsat.android.ble.NodeOtaTransport.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real updater across recovery attempts (MESHSAT-1684, R63-F1): every attempt starts its own
 * stage at the beginning, but what the node is left with belongs to the session, so it never goes
 * down from one attempt to the next, and a recovery with no history never says the old firmware is
 * still there. Only the Bluetooth attempt is scripted; the updater's flow is the app's.
 */
class NodeFirmwareRecoveryFlowTest {

    private val context = object : ContextWrapper(null) {}
    private val original = NodeFirmwareUpdater.attempt
    private val script = ArrayDeque<Outcome?>()
    private val attemptCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val attempts: Int get() = attemptCount.get()

    private val image: NodeFirmwareImage = run {
        val b = ByteArray(200_000)
        b[0] = 0xE9.toByte()
        b[32] = 0x32; b[33] = 0x54; b[34] = 0xCD.toByte(); b[35] = 0xAB.toByte()
        "2.8.0.1505094".toByteArray().copyInto(b, 48)
        "meshsat-tbeam-s3-rockblock9704".toByteArray().copyInto(b, 5000)
        (NodeFirmwareImage.check(b, "meshsat-tbeam-s3-rockblock9704") as NodeFirmwareImage.Check.Ok).image
    }

    @Before
    fun up() {
        NodeFirmwareUpdater.clearForTest()
        NodeFirmwareUpdater.restartIntoLoaderMs = 0
        NodeFirmwareUpdater.restartIntoFirmwareMs = 0
        NodeFirmwareUpdater.attempt = { _, _, _, _ ->
            val next = synchronized(script) { script.removeFirst() }
            attemptCount.incrementAndGet()
            next
        }
    }

    @After
    fun down() {
        NodeFirmwareUpdater.clearForTest()
        NodeFirmwareUpdater.attempt = original
    }

    private fun failed(stage: Stage) = Outcome.Failed("Attempt failed at $stage.", stage)

    /** Run one attempt the way the screen does, and return where the updater settled. */
    private fun settle(action: () -> Unit): State = runBlocking {
        val before = attempts
        action()
        withTimeout(5_000) {
            while (attempts == before || NodeFirmwareUpdater.running) delay(5)
        }
        NodeFirmwareUpdater.state.value
    }

    private fun failedText(st: State): String {
        assertTrue("expected a failure, got $st", st is State.Failed)
        st as State.Failed
        assertTrue("a retry is offered", st.canRetry)
        assertTrue("the node is in its updater", st.inUpdater)
        assertTrue("the image is kept", NodeFirmwareUpdater.hasPending)
        return st.reason
    }

    private fun handover(): State = runBlocking {
        NodeFirmwareUpdater.afterHandover(context, null, "44:1B:F6:F9:41:7D", image)
        NodeFirmwareUpdater.state.value
    }

    private fun retry(): State = settle { NodeFirmwareUpdater.retry(context, null) }

    @Test
    fun `a retry across attempts keeps the strongest state the session saw`() {
        script.addAll(listOf(failed(Stage.EraseUncertain), failed(Stage.NotCommanded), failed(Stage.Erased), failed(Stage.Refused), Outcome.Done))

        assertTrue(failedText(handover()).contains("may have erased its firmware"))
        assertEquals(NodeFirmwareKnown.MayBeErased, NodeFirmwareUpdater.knownState)

        // The second attempt never reached its command: the first one may still have erased.
        val second = failedText(retry())
        assertTrue(second, second.contains("may have erased its firmware"))
        assertFalse(second, second.contains("still on it"))
        assertEquals(NodeFirmwareKnown.MayBeErased, NodeFirmwareUpdater.knownState)

        assertTrue(failedText(retry()).contains("erased its firmware and waits"))
        assertEquals(NodeFirmwareKnown.Erased, NodeFirmwareUpdater.knownState)

        // A refusal now says nothing about the erase that already happened.
        val fourth = failedText(retry())
        assertTrue(fourth, fourth.contains("erased its firmware and waits"))
        assertFalse(fourth, fourth.contains("still on it"))

        assertTrue(retry() is State.Done)
        assertNull("a finished update clears the session", NodeFirmwareUpdater.knownState)
        assertFalse(NodeFirmwareUpdater.hasPending)
        assertEquals(5, attempts)
    }

    @Test
    fun `a recovery with no history never says the old firmware is still there`() {
        script.addAll(listOf(failed(Stage.NotCommanded), failed(Stage.Refused), failed(Stage.EraseUncertain), failed(Stage.NotCommanded)))

        val first = failedText(settle { NodeFirmwareUpdater.sendToUpdater(context, null, null, image) })
        assertTrue(first, first.contains("whether its old firmware is still on it is not known"))
        assertFalse(first, first.contains("with its firmware still on it"))
        assertEquals(NodeFirmwareKnown.Unknown, NodeFirmwareUpdater.knownState)

        val second = failedText(retry())
        assertTrue(second, second.contains("is not known"))
        assertFalse(second, second.contains("with its firmware still on it"))

        assertTrue(failedText(retry()).contains("may have erased"))
        val fourth = failedText(retry())
        assertTrue(fourth, fourth.contains("may have erased"))
        assertEquals(NodeFirmwareKnown.MayBeErased, NodeFirmwareUpdater.knownState)
    }

    @Test
    fun `picking the file again for the updater keeps what the session knows`() {
        script.addAll(listOf(failed(Stage.Erased), failed(Stage.NotCommanded)))
        assertTrue(failedText(handover()).contains("erased its firmware"))
        // The screen's "Choose the file again": the card goes back to Idle, the file goes to the updater.
        NodeFirmwareUpdater.reset()
        val again = failedText(settle { NodeFirmwareUpdater.sendToUpdater(context, null, null, image) })
        assertTrue(again, again.contains("erased its firmware and waits"))
        assertEquals(NodeFirmwareKnown.Erased, NodeFirmwareUpdater.knownState)
    }

    @Test
    fun `a session that never wrote its command says the firmware is still there, and Bluetooth off keeps the session's words`() {
        script.addAll(listOf(failed(Stage.Refused), failed(Stage.Erased), null))
        val refused = failedText(handover())
        assertTrue(refused, refused.contains("with its firmware still on it: retry with the file this update started with."))
        assertEquals(NodeFirmwareKnown.Intact, NodeFirmwareUpdater.knownState)
        failedText(retry())
        val off = failedText(retry())
        assertEquals("Bluetooth is off. The node erased its firmware and waits in its updater: switch Bluetooth on and retry with the same file.", off)
    }

    @Test
    fun `a new handover starts a new session`() {
        script.addAll(listOf(failed(Stage.Erased), Outcome.Done, failed(Stage.NotCommanded)))
        failedText(handover())
        assertTrue(retry() is State.Done)
        // The node ran its new firmware and was handed over again: nothing of the old session is left.
        val next = failedText(handover())
        assertTrue(next, next.contains("with its firmware still on it"))
        assertEquals(NodeFirmwareKnown.Intact, NodeFirmwareUpdater.knownState)
    }

    @Test
    fun `the merge only moves up`() {
        assertEquals(NodeFirmwareKnown.Erased, NodeFirmwareKnown.merge(NodeFirmwareKnown.Erased, NodeFirmwareKnown.Intact))
        assertEquals(NodeFirmwareKnown.Erased, NodeFirmwareKnown.merge(NodeFirmwareKnown.Erased, NodeFirmwareKnown.Unknown))
        assertEquals(NodeFirmwareKnown.MayBeErased, NodeFirmwareKnown.merge(NodeFirmwareKnown.Unknown, NodeFirmwareKnown.MayBeErased))
        assertEquals(NodeFirmwareKnown.Unknown, NodeFirmwareKnown.merge(NodeFirmwareKnown.Unknown, NodeFirmwareKnown.Intact))
        assertEquals(NodeFirmwareKnown.Unknown, NodeFirmwareKnown.merge(NodeFirmwareKnown.Intact, NodeFirmwareKnown.Unknown))
        assertEquals(NodeFirmwareKnown.Intact, NodeFirmwareKnown.merge(null, NodeFirmwareKnown.Intact))
    }
}
