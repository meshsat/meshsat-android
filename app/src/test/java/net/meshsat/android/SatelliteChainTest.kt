package net.meshsat.android

import net.meshsat.android.bt.Iridium9704Spp
import net.meshsat.android.engine.SatelliteChain
import net.meshsat.android.engine.TransformException
import net.meshsat.android.engine.TransformPipeline
import net.meshsat.android.hub.InterfaceInfo
import net.meshsat.android.hub.modemDeclarations
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The app's side of the kits' satellite chain beyond the Bridge's vectors (MESHSAT-1623): no key
 * never means plaintext, sends draw fresh nonces, the limit is stated and enforced, an SOS goes
 * as it is, the bytes survive the 9704's segments, and the Hub birth declares the chain.
 */
class SatelliteChainTest {

    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val otherKey = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100"
    private val greek = "Καλησπέρα από το σκάφος, όλα καλά".toByteArray(Charsets.UTF_8)

    private fun expectRefused(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what was not refused")
        } catch (e: TransformException) {
        }
    }

    @Test
    fun `no key, or one that is not a key, is never sent as plaintext`() {
        expectRefused("no key") { SatelliteChain.seal(greek, null) }
        expectRefused("empty key") { SatelliteChain.seal(greek, "") }
        // What the key store answers when its secret is missing: a reference, not a key.
        expectRefused("a key-store reference") { SatelliteChain.seal(greek, "convkey:shared") }
        expectRefused("a short key") { SatelliteChain.seal(greek, key.take(62)) }
    }

    @Test
    fun `every send draws a fresh nonce and both open`() {
        val a = SatelliteChain.seal(greek, key)
        val b = SatelliteChain.seal(greek, key)
        assertNotEquals(a.toList(), b.toList())
        assertNotEquals(a.copyOfRange(1, 13).toList(), b.copyOfRange(1, 13).toList())
        assertArrayEquals(greek, SatelliteChain.open(a, key))
        assertArrayEquals(greek, SatelliteChain.open(b, key))
    }

    @Test
    fun `the version byte is added once and taken off once`() {
        val wire = SatelliteChain.seal(greek, key)
        assertEquals(SatelliteChain.PROTOCOL_VERSION, wire[0])
        // Without it the wire is not the chain's; with it twice it does not open.
        expectRefused("no version byte") { SatelliteChain.open(wire.copyOfRange(1, wire.size), key) }
        expectRefused("a second version byte") { SatelliteChain.open(byteArrayOf(SatelliteChain.PROTOCOL_VERSION) + wire, key) }
        // A nonce that itself starts with 0x01 is not mistaken for the version byte.
        val p = TransformPipeline().apply { fixedNonce = ByteArray(12) { if (it == 0) 0x01 else 0x55 } }
        val w = SatelliteChain.seal(greek, key, p)
        assertEquals(0x01.toByte(), w[1])
        assertArrayEquals(greek, SatelliteChain.open(w, key))
    }

    @Test
    fun `a wrong key, changed or cut bytes, or no key never read as text`() {
        val wire = SatelliteChain.seal("meet at the north gate".toByteArray(), key)
        val cases = mapOf(
            "wrong key" to (wire to otherKey),
            "no key" to (wire to null),
            "tag changed" to (wire.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() } to key),
            "ciphertext changed" to (wire.copyOf().also { it[13] = (it[13].toInt() xor 1).toByte() } to key),
            "cut" to (wire.copyOf(wire.size - 1) to key),
            "version byte only" to (byteArrayOf(0x01) to key),
        )
        for ((name, c) in cases) {
            val mt = SatelliteChain.readMt(c.first, c.second)
            assertTrue("$name read as $mt", mt is SatelliteChain.Mt.Unopened)
        }
        // A typed text does not start with 0x01: it reads as before.
        assertEquals(SatelliteChain.Mt.Text("hello", sealed = false), SatelliteChain.readMt("hello".toByteArray(), key))
    }

    @Test
    fun `one satellite message at most, at the boundary and no further`() {
        // Bytes no table shortens go raw: wire = 30 + n.
        val rng = java.util.Random(1623)
        val most = ByteArray(SatelliteChain.MAX_WIRE_BYTES - SatelliteChain.OVERHEAD).also { rng.nextBytes(it) }
        val wire = SatelliteChain.seal(most, key)
        assertEquals(Iridium9704Spp.IMT_MAX_SIZE, wire.size)
        assertArrayEquals(most, SatelliteChain.open(wire, key))
        val one = most + byteArrayOf(0x42)
        expectRefused("one byte over") { SatelliteChain.seal(one, key) }
    }

    @Test
    fun `an SOS, a burst and binary frames go as they are`() {
        val text = "all good here".toByteArray()
        assertTrue(SatelliteChain.sealsDelivery("", text))
        assertTrue(SatelliteChain.sealsDelivery("compose:1", greek))
        assertFalse(SatelliteChain.sealsDelivery("sos:1761091200000:sat", text))
        assertFalse(SatelliteChain.sealsDelivery("sos:1761091200000:cancel:sat", text))
        assertFalse(SatelliteChain.sealsDelivery("burst:1761091200000", text))
        assertFalse(SatelliteChain.sealsDelivery("", byteArrayOf(0x4D, 0x53, 0x07, 0xC3.toByte(), 0x28)))
    }

    @Test
    fun `the sealed bytes survive the 9704's base64 segments`() {
        val all = ByteArray(256) { it.toByte() }
        val wires = listOf(all, SatelliteChain.seal(greek, key), SatelliteChain.seal(all, key))
        for (wire in wires) {
            for (segment in listOf(1, 2, 3, 7, 1446, wire.size)) {
                val back = wire.toList().chunked(segment).flatMap {
                    Iridium9704Spp.jsprSegmentBytes(Iridium9704Spp.jsprSegmentData(it.toByteArray())).toList()
                }.toByteArray()
                assertArrayEquals("segments of $segment", wire, back)
            }
        }
        // A data field with a line break in it still decodes, as android.util.Base64.DEFAULT did.
        val b64 = Iridium9704Spp.jsprSegmentData(all)
        assertArrayEquals(all, Iridium9704Spp.jsprSegmentBytes(b64.substring(0, 40) + "\n" + b64.substring(40)))
    }

    @Test
    fun `the birth declares the chain as the Bridge does`() {
        val sealed = InterfaceInfo("iridium_imt_0", "iridium_imt", "online", imei = "300000000000001", egressFormat = "sealed").toJson()
        assertEquals("sealed", sealed.getString("egress_format"))
        assertTrue(sealed.getBoolean("egress_sealed"))
        val plain = InterfaceInfo("iridium_imt_0", "iridium_imt", "online", imei = "300000000000001", egressFormat = "plain").toJson()
        assertEquals("plain", plain.getString("egress_format"))
        assertFalse(plain.has("egress_sealed"))
        // Nothing set, or the setting not read yet: nothing declared, so the Hub keeps what it has.
        val none = InterfaceInfo("iridium_imt_0", "iridium_imt", "online", imei = "300000000000001").toJson()
        assertFalse(none.has("egress_format"))
        assertFalse(none.has("egress_sealed"))

        assertEquals("sealed", SatelliteChain.egressFormat(SatelliteChain.MODE_SEALED))
        assertEquals("plain", SatelliteChain.egressFormat(SatelliteChain.MODE_PLAIN))
        assertEquals(null, SatelliteChain.egressFormat(SatelliteChain.MODE_UNDECLARED))
        assertEquals(null, SatelliteChain.egressFormat(null))
    }

    @Test
    fun `a late IMEI or a chain switch re-announces, a missing IMEI declares nothing`() {
        val before = modemDeclarations(listOf(InterfaceInfo("iridium_imt_0", "iridium_imt", "binding", egressFormat = "sealed")))
        assertEquals(emptySet<String>(), before)
        val late = modemDeclarations(listOf(InterfaceInfo("iridium_imt_0", "iridium_imt", "online", imei = "300000000000001", egressFormat = "sealed")))
        assertNotEquals(before, late)
        val plain = modemDeclarations(listOf(InterfaceInfo("iridium_imt_0", "iridium_imt", "online", imei = "300000000000001", egressFormat = "plain")))
        assertNotEquals(late, plain)
        assertEquals(setOf("300000000000001:sealed"), late)
    }
}
