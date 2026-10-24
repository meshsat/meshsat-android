package net.meshsat.android

import net.meshsat.android.ble.NodeFirmwareImage
import net.meshsat.android.ble.NodeFirmwareUpdater
import net.meshsat.android.ble.NodeOtaTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The node firmware update's checks and the OTA loader's line protocol (MESHSAT-1672). */
class NodeFirmwareUpdateTest {

    private val env = "meshsat-tbeam-s3-rockblock9704"

    private fun appImage(size: Int = 200_000, envName: String = env, version: String = "2.8.0.fc4f3bb"): ByteArray {
        val b = ByteArray(size)
        b[0] = 0xE9.toByte()
        // esp_app_desc magic 0xABCD5432 at 32, little-endian
        b[32] = 0x32; b[33] = 0x54; b[34] = 0xCD.toByte(); b[35] = 0xAB.toByte()
        version.toByteArray().copyInto(b, 48)
        "firmware".toByteArray().copyInto(b, 80)
        envName.toByteArray().copyInto(b, 5000)
        return b
    }

    @Test
    fun `an app image built for the node's env is taken, with its version and SHA-256`() {
        val c = NodeFirmwareImage.check(appImage(), env)
        assertTrue(c is NodeFirmwareImage.Check.Ok)
        val image = (c as NodeFirmwareImage.Check.Ok).image
        assertEquals("2.8.0.fc4f3bb", image.version)
        assertEquals(64, image.sha256Hex.length)
        assertEquals(32, image.sha256.size)
    }

    @Test
    fun `an image for another env, a factory image, a too large or too small file are refused`() {
        assertTrue(NodeFirmwareImage.check(appImage(envName = "tbeam-s3-core"), env) is NodeFirmwareImage.Check.Refused)
        val factory = appImage().also { it[32] = 0; it[33] = 0; it[34] = 0; it[35] = 0 }
        assertTrue(NodeFirmwareImage.check(factory, env) is NodeFirmwareImage.Check.Refused)
        assertTrue(NodeFirmwareImage.check(appImage(size = NodeFirmwareImage.APP_SLOT_BYTES + 1), env) is NodeFirmwareImage.Check.Refused)
        assertTrue(NodeFirmwareImage.check(ByteArray(1000), env) is NodeFirmwareImage.Check.Refused)
        assertTrue(NodeFirmwareImage.check(appImage(), "") is NodeFirmwareImage.Check.Refused)
        val notEsp = appImage().also { it[0] = 0 }
        assertTrue(NodeFirmwareImage.check(notEsp, env) is NodeFirmwareImage.Check.Refused)
    }

    @Test
    fun `the OTA command names size and lowercase hash, and chunks fit the MTU`() {
        assertEquals("OTA 2417568 abcdef\n", NodeOtaTransport.otaCommand(2_417_568, "ABCDEF"))
        assertEquals(20, NodeOtaTransport.chunkSize(23))
        assertEquals(244, NodeOtaTransport.chunkSize(247))
        assertEquals(512, NodeOtaTransport.chunkSize(517))
    }

    @Test
    fun `the loader's answers are read line by line, across and within notifications`() {
        val partial = StringBuilder()
        assertEquals(emptyList<String>(), NodeOtaTransport.splitLines(partial, "ERAS"))
        assertEquals(listOf("ERASING", "OK"), NodeOtaTransport.splitLines(partial, "ING\r\nOK\n"))
        assertEquals(listOf("ACK"), NodeOtaTransport.splitLines(partial, "ACK\n"))
        assertTrue(NodeOtaTransport.parseReply("OK") is NodeOtaTransport.Reply.Ok)
        assertTrue(NodeOtaTransport.parseReply("OK lilygo 2.8.0 3 v1.0.1") is NodeOtaTransport.Reply.Ok)
        assertTrue(NodeOtaTransport.parseReply("ACK") is NodeOtaTransport.Reply.Ack)
        assertTrue(NodeOtaTransport.parseReply("ERASING") is NodeOtaTransport.Reply.Erasing)
        val err = NodeOtaTransport.parseReply("ERR Hash Mismatch")
        assertTrue(err is NodeOtaTransport.Reply.Error)
        assertEquals("Hash Mismatch", (err as NodeOtaTransport.Reply.Error).text)
    }

    @Test
    fun `the node's handover answers are recognised, other notifications are not`() {
        assertTrue(NodeFirmwareUpdater.isHandoverAnswer("Rebooting to BLE OTA"))
        assertTrue(NodeFirmwareUpdater.isHandoverAnswer("Cannot start OTA: Invalid `ota_hash` provided."))
        assertTrue(NodeFirmwareUpdater.isHandoverAnswer("OTA Loader does not support BLE"))
        assertTrue(NodeFirmwareUpdater.isHandoverAnswer("Unable to switch to the OTA partition."))
        assertFalse(NodeFirmwareUpdater.isHandoverAnswer("Low battery"))
    }
}
