package net.meshsat.android

import kotlinx.coroutines.runBlocking
import net.meshsat.android.ble.NodeFirmwareImage
import net.meshsat.android.ble.NodeFirmwareKnown
import net.meshsat.android.ble.NodeFirmwareUpdater
import net.meshsat.android.ble.NodeOtaTransport
import net.meshsat.android.ble.NodeOtaTransport.Outcome
import net.meshsat.android.ble.NodeOtaTransport.Stage
import net.meshsat.android.ble.OtaProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way a node firmware update can stop, and what the phone keeps and offers then
 * (MESHSAT-1684). The loader (esp32-unified-ota v1.0.1, ota_processor.cpp handleOtaStart) checks
 * the command and answers "ERR ..." without touching app0, then sends "ERASING" and erases before
 * "OK"; so from the written command on the old firmware may be gone.
 */
class NodeOtaInterruptionTest {

    private val timeouts = OtaProtocol.Timeouts(eraseMs = 200, ackMs = 200, verifyMs = 200)
    private val image = ByteArray(1000) { it.toByte() }
    private val sha = "c".repeat(64)

    /** A loader that answers from a script: each write takes the next answers in order. */
    private class ScriptedLink(
        private val imageSize: Int = 1000,
        private val commandWrite: Boolean = true,
        private val chunkWrite: (Int) -> Boolean = { true },
        private val afterCommand: List<String?> = emptyList(),
        private val afterChunk: (Int, Boolean) -> String? = { _, last -> if (last) "OK" else "ACK" },
    ) : OtaProtocol.Link {
        val lines = ArrayDeque<String?>()
        var chunks = 0
        private var written = 0
        var stageAtCommandWrite: Stage? = null
        var stageNow: Stage = Stage.NotCommanded
        override fun drain() {}
        override suspend fun write(bytes: ByteArray, withResponse: Boolean): Boolean {
            if (withResponse) {
                stageAtCommandWrite = stageNow
                lines.addAll(afterCommand)
                return commandWrite
            }
            val n = chunks++
            if (!chunkWrite(n)) return false
            written += bytes.size
            lines.addLast(afterChunk(n, written >= imageSize))
            return true
        }
        override suspend fun nextLine(timeoutMs: Long): String? {
            val l = lines.removeFirstOrNull()
            if (l == null) Thread.sleep(timeoutMs)
            return l
        }
    }

    private fun run(link: ScriptedLink, chunk: Int = 400): Outcome = runBlocking {
        OtaProtocol.run(link, image, sha, chunk, timeouts, onStep = {}) { link.stageNow = it }
    }

    private fun failed(o: Outcome): Outcome.Failed {
        assertTrue("expected a failure, got $o", o is Outcome.Failed)
        return o as Outcome.Failed
    }

    @Test
    fun `the stage says erase-uncertain before the command is written`() {
        val link = ScriptedLink(afterCommand = listOf("ERASING", "OK"))
        assertEquals(Outcome.Done, run(link))
        assertEquals(Stage.EraseUncertain, link.stageAtCommandWrite)
    }

    @Test
    fun `a command write the link reports as failed may still have reached the loader`() {
        val f = failed(run(ScriptedLink(commandWrite = false)))
        assertEquals(Stage.EraseUncertain, f.stage)
        assertTrue(f.mayBeErased)
    }

    @Test
    fun `during erasing - no line at all`() {
        val f = failed(run(ScriptedLink(afterCommand = emptyList())))
        assertEquals(Stage.EraseUncertain, f.stage)
    }

    @Test
    fun `during erasing - ERASING then silence`() {
        val f = failed(run(ScriptedLink(afterCommand = listOf("ERASING"))))
        assertEquals(Stage.EraseUncertain, f.stage)
        assertTrue(f.reason, f.reason.contains("did not finish erasing"))
    }

    @Test
    fun `during erasing - an error line before ERASING is a refusal, the firmware is still there`() {
        val f = failed(run(ScriptedLink(afterCommand = listOf("ERR Hash Rejected (NVS Mismatch)"))))
        assertEquals(Stage.Refused, f.stage)
        assertFalse(f.mayBeErased)
    }

    @Test
    fun `during erasing - an error line after ERASING is a failed erase`() {
        val f = failed(run(ScriptedLink(afterCommand = listOf("ERASING", "ERR OTA Begin Failed"))))
        assertEquals(Stage.EraseUncertain, f.stage)
        assertTrue(f.mayBeErased)
    }

    @Test
    fun `during erasing - the link drops`() {
        assertEquals(Stage.EraseUncertain, failed(run(ScriptedLink(afterCommand = listOf(OtaProtocol.LINK_LOST)))).stage)
        assertEquals(Stage.EraseUncertain, failed(run(ScriptedLink(afterCommand = listOf("ERASING", OtaProtocol.LINK_LOST)))).stage)
    }

    @Test
    fun `at the ready acknowledgement - the first piece cannot be sent`() {
        val f = failed(run(ScriptedLink(afterCommand = listOf("ERASING", "OK"), chunkWrite = { false })))
        assertEquals(Stage.Erased, f.stage)
        assertTrue(f.mayBeErased)
    }

    @Test
    fun `at the ready acknowledgement - no ACK, a lost link, an error`() {
        assertEquals(Stage.Erased, failed(run(ScriptedLink(afterCommand = listOf("ERASING", "OK"), afterChunk = { _, _ -> null }))).stage)
        assertEquals(Stage.Erased, failed(run(ScriptedLink(afterCommand = listOf("ERASING", "OK"), afterChunk = { _, _ -> OtaProtocol.LINK_LOST }))).stage)
        assertEquals(Stage.Erased, failed(run(ScriptedLink(afterCommand = listOf("ERASING", "OK"), afterChunk = { n, _ -> if (n == 1) "ERR Flash Write" else "ACK" }))).stage)
    }

    @Test
    fun `the whole transfer`() {
        // 1000 bytes in pieces of 400: ACK, ACK, then OK after the hash check.
        val link = ScriptedLink(afterCommand = listOf("ERASING", "OK"), afterChunk = { n, _ -> if (n == 2) "OK" else "ACK" })
        assertEquals(Outcome.Done, run(link))
        assertEquals(3, link.chunks)
    }

    @Test
    fun `after the handover every failure offers a retry, and the words follow what the session knows`() {
        for (stage in Stage.values()) {
            val st = NodeFirmwareUpdater.failedInUpdater(Outcome.Failed("x.", stage), NodeFirmwareKnown.of(stage))
            assertTrue("$stage offers a retry", st.canRetry)
            assertTrue("$stage leaves the node in its updater", st.inUpdater)
        }
        assertTrue(NodeFirmwareUpdater.failedInUpdater(Outcome.Failed("x.", Stage.EraseUncertain), NodeFirmwareKnown.MayBeErased).reason.contains("may have erased"))
        assertTrue(NodeFirmwareUpdater.failedInUpdater(Outcome.Failed("x.", Stage.Erased), NodeFirmwareKnown.Erased).reason.contains("erased its firmware"))
        assertTrue(NodeFirmwareUpdater.failedInUpdater(Outcome.Failed("x.", Stage.NotCommanded), NodeFirmwareKnown.Intact).reason.contains("still on it"))
        assertTrue(NodeFirmwareUpdater.failedInUpdater(Outcome.Failed("x.", Stage.Refused), NodeFirmwareKnown.Intact).reason.contains("still on it"))
        // This attempt failed before its command, but the session knows an earlier one erased.
        assertFalse(NodeFirmwareUpdater.failedInUpdater(Outcome.Failed("x.", Stage.NotCommanded), NodeFirmwareKnown.Erased).reason.contains("still on it"))
    }

    @Test
    fun `interruption at the handover - what the node did decides whether the image is kept`() {
        val accepted = NodeFirmwareUpdater.handover("Rebooting to BLE OTA", linkUp = true)
        val dropped = NodeFirmwareUpdater.handover(null, linkUp = false)
        val refused = NodeFirmwareUpdater.handover("Unable to switch to the OTA partition", linkUp = true)
        val silent = NodeFirmwareUpdater.handover(null, linkUp = true)
        assertEquals(NodeFirmwareUpdater.Handover.Accepted, accepted)
        assertEquals(NodeFirmwareUpdater.Handover.LinkDropped, dropped)
        assertEquals(NodeFirmwareUpdater.Handover.Refused, refused)
        assertEquals(NodeFirmwareUpdater.Handover.NoAnswer, silent)
        assertTrue("a node that went to its loader keeps the image", NodeFirmwareUpdater.keepsImage(accepted))
        assertTrue("a dropped link most likely means the loader: keep it", NodeFirmwareUpdater.keepsImage(dropped))
        assertFalse(NodeFirmwareUpdater.keepsImage(refused))
        assertFalse(NodeFirmwareUpdater.keepsImage(silent))
    }

    @Test
    fun `a node waiting in its updater takes a file without the env check, the loader checks the hash`() {
        val b = ByteArray(200_000)
        b[0] = 0xE9.toByte()
        b[32] = 0x32; b[33] = 0x54; b[34] = 0xCD.toByte(); b[35] = 0xAB.toByte()
        assertTrue(NodeFirmwareImage.checkForWaitingUpdater(b) is NodeFirmwareImage.Check.Ok)
        assertTrue(NodeFirmwareImage.check(b, "") is NodeFirmwareImage.Check.Refused)
        val factory = b.copyOf().also { it[32] = 0 }
        assertTrue(NodeFirmwareImage.checkForWaitingUpdater(factory) is NodeFirmwareImage.Check.Refused)
    }

    @Test
    fun `the command line`() {
        assertEquals("OTA 1000 ${"c".repeat(64)}\n", NodeOtaTransport.otaCommand(1000, "C".repeat(64)))
    }
}
