package com.cubeos.meshsat

import com.cubeos.meshsat.reticulum.RnsConstants
import com.cubeos.meshsat.reticulum.RnsIridium9704Interface
import com.cubeos.meshsat.reticulum.RnsIridiumInterface
import com.cubeos.meshsat.reticulum.RnsMeshInterface
import com.cubeos.meshsat.reticulum.RnsMqttInterface
import com.cubeos.meshsat.reticulum.RnsSmsInterface
import com.cubeos.meshsat.reticulum.RnsAprsInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Reticulum transport interfaces (MESHSAT-213/215/216/218/220).
 * Tests protocol encoding/decoding without actual hardware connections.
 */
class RnsInterfaceTest {

    // --- Mesh (BLE) interface: protobuf encoding ---

    @Test
    fun `mesh encodePrivateApp produces valid protobuf`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val toRadio = RnsMeshInterface.encodePrivateApp(payload)
        assertTrue(toRadio.isNotEmpty())
        // ToRadio has a length-delimited field 1 wrapping the MeshPacket
        assertTrue(toRadio.size > payload.size)
    }

    @Test
    fun `mesh encodePrivateApp round-trips with extractPrivateApp`() {
        val payload = "hello reticulum mesh".toByteArray()
        val toRadio = RnsMeshInterface.encodePrivateApp(payload)

        // Build a fake FromRadio: field 2 = MeshPacket containing our data
        // The encodePrivateApp returns ToRadio(field 1 = MeshPacket)
        // FromRadio uses field 2 for MeshPacket, so we need to re-wrap
        val meshPacket = extractField1(toRadio)
        assertNotNull(meshPacket)
        val fromRadio = encodeBytesField(2, meshPacket!!)

        val extracted = RnsMeshInterface.extractPrivateApp(fromRadio)
        assertNotNull(extracted)
        assertArrayEquals(payload, extracted)
    }

    @Test
    fun `mesh extractPrivateApp returns null for non-PRIVATE_APP portnum`() {
        // Build a FromRadio with portnum=1 (TEXT_MESSAGE)
        val dataProto = encodeVarintField(1, 1) + encodeBytesField(2, "text".toByteArray())
        val meshPacket = encodeVarintField(2, 0xFFFFFFFFL) +
            encodeVarintField(3, 0) +
            encodeBytesField(4, dataProto)
        val fromRadio = encodeBytesField(2, meshPacket)

        val result = RnsMeshInterface.extractPrivateApp(fromRadio)
        assertNull(result)
    }

    @Test
    fun `mesh MTU is 237`() {
        assertEquals(237, RnsMeshInterface.MESH_MTU)
    }

    @Test
    fun `mesh PORTNUM_PRIVATE_APP is 256`() {
        assertEquals(256, RnsMeshInterface.PORTNUM_PRIVATE_APP)
    }

    // --- SMS interface ---

    @Test
    fun `SMS prefix is RNS colon`() {
        assertEquals("RNS:", RnsSmsInterface.SMS_PREFIX)
    }

    @Test
    fun `SMS binary MTU is 117`() {
        // 160 chars - 4 prefix = 156 base64 chars * 3/4 = 117 bytes
        assertEquals(117, RnsSmsInterface.SMS_BINARY_MTU)
    }

    @Test
    fun `SMS multipart MTU is 837`() {
        assertEquals(837, RnsSmsInterface.SMS_MULTIPART_MTU)
    }

    // --- Iridium interface ---

    @Test
    fun `Iridium MO MTU is 340`() {
        assertEquals(340, RnsIridiumInterface.IRIDIUM_MO_MTU)
    }

    @Test
    fun `Iridium MT MTU is 270`() {
        assertEquals(270, RnsIridiumInterface.IRIDIUM_MT_MTU)
    }

    @Test
    fun `Iridium RNS magic byte is 0x52`() {
        assertEquals(0x52.toByte(), RnsIridiumInterface.SBD_RNS_MAGIC)
    }

    // --- Iridium 9704 (IMT) interface ---

    @Test
    fun `Iridium 9704 RNS magic byte is 0x52`() {
        assertEquals(0x52.toByte(), RnsIridium9704Interface.RNS_MAGIC)
    }

    @Test
    fun `Iridium 9704 magic byte matches SBD magic byte`() {
        // Both use 0x52 ('R') for protocol consistency
        assertEquals(RnsIridiumInterface.SBD_RNS_MAGIC, RnsIridium9704Interface.RNS_MAGIC)
    }

    // --- APRS interface ---

    @Test
    fun `APRS MTU is 256`() {
        assertEquals(256, RnsAprsInterface.AX25_MTU)
    }

    @Test
    fun `APRS RNS destination callsign is RNSNET`() {
        assertEquals("RNSNET", RnsAprsInterface.RNS_AX25_DEST)
    }

    // --- MQTT interface ---

    @Test
    fun `MQTT uses Reticulum MTU`() {
        assertEquals(RnsConstants.MTU, 500)
    }

    @Test
    fun `MQTT topic suffixes correct`() {
        assertEquals("/reticulum/tx", RnsMqttInterface.TOPIC_TX_SUFFIX)
        assertEquals("/reticulum/rx", RnsMqttInterface.TOPIC_RX_SUFFIX)
    }

    // --- Helpers for protobuf test encoding ---

    private fun encodeVarint(value: Long): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        while (v > 0x7F) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result.toByteArray()
    }

    private fun encodeVarintField(fieldNumber: Int, value: Long): ByteArray {
        val tag = encodeVarint(((fieldNumber shl 3) or 0).toLong())
        return tag + encodeVarint(value)
    }

    private fun encodeBytesField(fieldNumber: Int, data: ByteArray): ByteArray {
        val tag = encodeVarint(((fieldNumber shl 3) or 2).toLong())
        return tag + encodeVarint(data.size.toLong()) + data
    }

    private fun extractField1(toRadio: ByteArray): ByteArray? {
        // Extract field 1 (length-delimited) from ToRadio
        var i = 0
        while (i < toRadio.size) {
            val (tag, tagLen) = readVarint(toRadio, i)
            i += tagLen
            val wireType = (tag and 0x07).toInt()
            val fn = (tag ushr 3).toInt()
            if (wireType == 2) {
                val (len, lenLen) = readVarint(toRadio, i)
                i += lenLen
                if (fn == 1) return toRadio.copyOfRange(i, i + len.toInt())
                i += len.toInt()
            } else if (wireType == 0) {
                val (_, valLen) = readVarint(toRadio, i)
                i += valLen
            } else break
        }
        return null
    }

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to (i - offset)
    }
}
