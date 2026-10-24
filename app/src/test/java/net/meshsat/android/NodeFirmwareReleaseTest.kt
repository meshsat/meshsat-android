package net.meshsat.android

import net.meshsat.android.ble.NodeFirmwareImage
import net.meshsat.android.ble.NodeFirmwareRelease
import net.meshsat.android.ble.NodeOtaTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** The firmware release manifest: read, the node's own build picked, the download checked (MESHSAT-1672). */
class NodeFirmwareReleaseTest {

    private val sha = "a".repeat(64)

    private fun manifest(schema: Int = 1, images: String? = null) = """
        {"schema": $schema, "version": "2.8.0.4012de5", "tag": "meshsat-node-2.8.0.4012de5",
         "commit": "4012de597", "released_at": "2026-10-24T20:00:00Z",
         "images": ${images ?: """[
           {"env": "meshsat-tbeam-s3-rockblock9704", "file": "firmware-meshsat-tbeam-s3-rockblock9704-2.8.0.4012de5.bin", "size": 2419024, "sha256": "$sha"},
           {"env": "meshsat-tbeam-s3-rockblock", "file": "firmware-meshsat-tbeam-s3-rockblock-2.8.0.4012de5.bin", "size": 2400000, "sha256": "${"b".repeat(64)}"}
         ]"""}}
    """.trimIndent()

    private fun ok(text: String): NodeFirmwareRelease.Manifest =
        (NodeFirmwareRelease.parse(text) as NodeFirmwareRelease.Parsed.Ok).manifest

    @Test
    fun `a schema 1 manifest is read`() {
        val m = ok(manifest())
        assertEquals("2.8.0.4012de5", m.version)
        assertEquals("meshsat-node-2.8.0.4012de5", m.tag)
        assertEquals(2, m.images.size)
        assertEquals(2419024, m.images[0].size)
    }

    @Test
    fun `another schema, a broken entry, no images and no JSON are refused`() {
        assertTrue(NodeFirmwareRelease.parse(manifest(schema = 2)) is NodeFirmwareRelease.Parsed.Refused)
        assertTrue(NodeFirmwareRelease.parse(manifest(images = "[]")) is NodeFirmwareRelease.Parsed.Refused)
        assertTrue(
            NodeFirmwareRelease.parse(manifest(images = """[{"env": "x", "file": "f.bin", "size": 10, "sha256": "short"}]""")) is NodeFirmwareRelease.Parsed.Refused,
        )
        assertTrue(
            NodeFirmwareRelease.parse(manifest(images = """[{"env": "x", "file": "../f.bin", "size": 10, "sha256": "$sha"}]""")) is NodeFirmwareRelease.Parsed.Refused,
        )
        assertTrue(
            NodeFirmwareRelease.parse(manifest(images = """[{"env": "x", "file": "f.bin", "size": ${NodeFirmwareImage.APP_SLOT_BYTES + 1}, "sha256": "$sha"}]""")) is NodeFirmwareRelease.Parsed.Refused,
        )
        assertTrue(NodeFirmwareRelease.parse("not json") is NodeFirmwareRelease.Parsed.Refused)
        val r = NodeFirmwareRelease.parse(manifest(schema = 2)) as NodeFirmwareRelease.Parsed.Refused
        assertTrue(r.reason, r.reason.contains("schema 2"))
    }

    @Test
    fun `the node's own build is picked by its exact name`() {
        val m = ok(manifest())
        val p9704 = NodeFirmwareRelease.pick(m, "meshsat-tbeam-s3-rockblock9704") as NodeFirmwareRelease.Pick.Found
        assertEquals("meshsat-tbeam-s3-rockblock9704", p9704.image.env)
        val p9603 = NodeFirmwareRelease.pick(m, "meshsat-tbeam-s3-rockblock") as NodeFirmwareRelease.Pick.Found
        assertEquals("meshsat-tbeam-s3-rockblock", p9603.image.env)
        assertTrue(NodeFirmwareRelease.pick(m, "meshsat-xiao-s3-rockblock") is NodeFirmwareRelease.Pick.Refused)
        assertTrue(NodeFirmwareRelease.pick(m, "") is NodeFirmwareRelease.Pick.Refused)
    }

    @Test
    fun `the download must be the manifest's size and SHA-256`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val good = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val image = NodeFirmwareRelease.Image("e", "f.bin", 1000, good)
        assertNull(NodeFirmwareRelease.verify(bytes, image))
        assertTrue(NodeFirmwareRelease.verify(bytes.copyOf(999), image) != null)
        assertTrue(NodeFirmwareRelease.verify(bytes, image.copy(sha256 = sha)) != null)
    }

    @Test
    fun `the image URL and the current version`() {
        val m = ok(manifest())
        assertEquals(
            "https://github.com/meshsat/meshsat-firmware/releases/download/meshsat-node-2.8.0.4012de5/firmware-meshsat-tbeam-s3-rockblock9704-2.8.0.4012de5.bin",
            NodeFirmwareRelease.imageUrl(m, m.images[0]),
        )
        assertTrue(NodeFirmwareRelease.isCurrent(m, "2.8.0.4012de5"))
        assertFalse(NodeFirmwareRelease.isCurrent(m, "2.8.0.fc4f3bb"))
        assertFalse(NodeFirmwareRelease.isCurrent(m, ""))
    }

    @Test
    fun `a 9704 image is refused for a 9603 node although its name starts the same`() {
        val b = ByteArray(200_000)
        b[0] = 0xE9.toByte()
        b[32] = 0x32; b[33] = 0x54; b[34] = 0xCD.toByte(); b[35] = 0xAB.toByte()
        "meshsat-tbeam-s3-rockblock9704".toByteArray().copyInto(b, 5000)
        assertTrue(NodeFirmwareImage.check(b, "meshsat-tbeam-s3-rockblock") is NodeFirmwareImage.Check.Refused)
        assertTrue(NodeFirmwareImage.check(b, "meshsat-tbeam-s3-rockblock9704") is NodeFirmwareImage.Check.Ok)
    }

    @Test
    fun `the loader's next address`() {
        assertEquals("44:1B:F6:F9:41:7E", NodeOtaTransport.nextAddress("44:1B:F6:F9:41:7D"))
        assertEquals("44:1B:F6:F9:42:00", NodeOtaTransport.nextAddress("44:1b:f6:f9:41:ff"))
        assertEquals("", NodeOtaTransport.nextAddress("not an address"))
    }
}
