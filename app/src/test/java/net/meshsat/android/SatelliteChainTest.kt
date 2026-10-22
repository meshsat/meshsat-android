package net.meshsat.android

import net.meshsat.android.bt.Iridium9704Spp
import net.meshsat.android.engine.SatelliteChain
import net.meshsat.android.engine.TransformException
import net.meshsat.android.engine.TransformPipeline
import net.meshsat.android.engine.TransformSpec
import net.meshsat.android.hub.InterfaceInfo
import net.meshsat.android.hub.imtStatus
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
        assertEquals("the rollback chain is sealed too", "sealed", SatelliteChain.egressFormat(SatelliteChain.MODE_LEGACY))
        assertEquals("plain", SatelliteChain.egressFormat(SatelliteChain.MODE_PLAIN))
        assertEquals(null, SatelliteChain.egressFormat(SatelliteChain.MODE_UNDECLARED))
        assertEquals(null, SatelliteChain.egressFormat(null))
    }

    @Test
    fun `a ready 9704 reads online to the Hub`() {
        assertEquals("online", imtStatus(Iridium9704Spp.State.Ready))
        assertEquals("binding", imtStatus(Iridium9704Spp.State.Initializing))
        assertEquals("binding", imtStatus(Iridium9704Spp.State.Connected))
        assertEquals("binding", imtStatus(Iridium9704Spp.State.Connecting))
        assertEquals("offline", imtStatus(Iridium9704Spp.State.Disconnected))
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

    @Test
    fun `the setting picks the chain both ways`() {
        assertEquals(SatelliteChain.Chain.H, SatelliteChain.chainOf(SatelliteChain.MODE_SEALED))
        assertEquals(SatelliteChain.Chain.LEGACY, SatelliteChain.chainOf(SatelliteChain.MODE_LEGACY))
        assertEquals(null, SatelliteChain.chainOf(SatelliteChain.MODE_PLAIN))
        assertEquals(null, SatelliteChain.chainOf(SatelliteChain.MODE_UNDECLARED))
        assertEquals(null, SatelliteChain.chainOf(null))
        // The rollback chain is parallax's: smaz2 with no dict (the default table), encrypt, base64.
        assertEquals(
            listOf(TransformSpec("smaz2"), TransformSpec("encrypt", mapOf("key" to key)), TransformSpec("base64")),
            SatelliteChain.Chain.LEGACY.steps(key),
        )
    }

    /** The review's A3, which reproduced R33-R2 on 2.22.0, now with parallax's chain: it opens. */
    @Test
    fun `a legacy wire from the general pipeline opens on the satellite path`() {
        val text = "hello there, the satellite is in range".toByteArray()
        val legacy = listOf(TransformSpec("smaz2"), TransformSpec("encrypt", mapOf("key" to key)), TransformSpec("base64"))
        val pipeline = TransformPipeline()
        val wire = byteArrayOf(1) + pipeline.applyEgress(text, legacy)
        assertArrayEquals(text, pipeline.applyIngress(wire.copyOfRange(1, wire.size), legacy))
        val want = SatelliteChain.Mt.Text(String(text), sealed = true, chain = SatelliteChain.Chain.LEGACY)
        assertEquals(want, SatelliteChain.readMt(wire, key, preferred = SatelliteChain.Chain.LEGACY))
        assertEquals(want, SatelliteChain.readMt(wire, key, preferred = SatelliteChain.Chain.H))
        // And an H wire still opens on the legacy setting, by its own tag.
        val h = SatelliteChain.seal(text, key)
        assertEquals(SatelliteChain.Mt.Text(String(text), sealed = true, chain = SatelliteChain.Chain.H), SatelliteChain.readMt(h, key, preferred = SatelliteChain.Chain.LEGACY))
        // Wrong key: neither chain reads it.
        assertTrue(SatelliteChain.readMt(wire, otherKey, preferred = SatelliteChain.Chain.LEGACY) is SatelliteChain.Mt.Unopened)
    }

    @Test
    fun `the rollback chain has the same one-message limit, at the boundary and no further`() {
        // Wire = version byte + base64 of (nonce + smaz2 with the default table + tag).
        fun wireLen(b: ByteArray): Int {
            val z = net.meshsat.android.codec.Smaz2Bytes.compress(b, net.meshsat.android.codec.Smaz2Dict.DEFAULT).size
            return 1 + 4 * ((28 + z + 2) / 3)
        }
        // Bytes 0x80-0xFF only: the default table copies them verbatim (it has no word or bigram for
        // them), so they come back exactly and every byte added grows the output. Bytes 0x01-0x08
        // do not come back from the default table at all (MESHSAT-1626).
        val rng = java.util.Random(1623)
        val pool = ByteArray(SatelliteChain.MAX_WIRE_BYTES) { (0x80 + rng.nextInt(0x80)).toByte() }
        var lo = 1
        var hi = pool.size
        while (lo < hi) { // the longest prefix whose wire fits
            val mid = (lo + hi + 1) / 2
            if (wireLen(pool.copyOf(mid)) <= SatelliteChain.MAX_WIRE_BYTES) lo = mid else hi = mid - 1
        }
        val most = pool.copyOf(lo)
        val wire = SatelliteChain.seal(most, key, chain = SatelliteChain.Chain.LEGACY)
        assertEquals(wireLen(most), wire.size)
        assertTrue(wire.size <= SatelliteChain.MAX_WIRE_BYTES)
        assertArrayEquals(most, SatelliteChain.open(wire, key, chain = SatelliteChain.Chain.LEGACY))
        assertTrue("one byte more is over", wireLen(pool.copyOf(lo + 1)) > SatelliteChain.MAX_WIRE_BYTES)
        expectRefused("one byte over") { SatelliteChain.seal(pool.copyOf(lo + 1), key, chain = SatelliteChain.Chain.LEGACY) }
    }

    /**
     * The check value both ends show for the key (the Bridge's key_check): SHA-256 of the 32 key
     * bytes, first 4 bytes in hex. The vectors are shared with the Bridge's keystore test.
     */
    @Test
    fun `a key's check value is the Bridge's`() {
        assertEquals("630dcd29", SatelliteChain.keyCheck(key))
        assertEquals("02d449a3", SatelliteChain.keyCheck("11".repeat(32)))
        assertEquals("upper or lower case, the same key", "630dcd29", SatelliteChain.keyCheck(key.uppercase()))
        expectRefused("not a key") { SatelliteChain.keyCheck("convkey:shared") }
    }

    /**
     * What the service sends (`outbound`, which sendVia9704 calls): on each setting, for a text,
     * an SOS and a burst, with a key and without one. The key is read only when the text is sealed.
     */
    @Test
    fun `the service seals a text on the setting's chain and nothing else`() = kotlinx.coroutines.runBlocking {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val withKey: suspend () -> String? = { reads.incrementAndGet(); key }
        for (mode in listOf(null, SatelliteChain.MODE_UNDECLARED, SatelliteChain.MODE_PLAIN)) {
            assertArrayEquals("$mode sends the text as it is", greek, SatelliteChain.outbound(mode, "", greek, key = withKey))
        }
        assertEquals("no key read for an unsealed setting", 0, reads.get())
        for ((mode, chain) in listOf(SatelliteChain.MODE_SEALED to SatelliteChain.Chain.H, SatelliteChain.MODE_LEGACY to SatelliteChain.Chain.LEGACY)) {
            val wire = SatelliteChain.outbound(mode, "", greek, key = withKey)
            assertEquals(SatelliteChain.PROTOCOL_VERSION, wire[0])
            assertArrayEquals("$mode seals on $chain", greek, SatelliteChain.open(wire, key, chain = chain))
            assertEquals("$mode reads back on its own setting", SatelliteChain.Mt.Text(String(greek, Charsets.UTF_8), true, chain), SatelliteChain.inbound(wire, mode) { key })
            val sos = byteArrayOf(0x53, 0x4F, 0x53, 0x01, 0x02)
            assertArrayEquals("$mode: an SOS goes as it is", sos, SatelliteChain.outbound(mode, "sos:7", sos, key = withKey))
            assertArrayEquals("$mode: a burst goes as it is", greek, SatelliteChain.outbound(mode, "burst:3", greek, key = withKey))
            expectRefused("$mode with no key") { kotlinx.coroutines.runBlocking { SatelliteChain.outbound(mode, "", greek) { null } } }
            expectRefused("$mode with another channel's reference") { kotlinx.coroutines.runBlocking { SatelliteChain.outbound(mode, "", greek) { "convkey:shared" } } }
        }
    }

    /** What the service reads (`inbound`): the key only for a versioned payload, H first when nothing is set. */
    @Test
    fun `the service reads a plain text without the key and a sealed one on either chain`() = kotlinx.coroutines.runBlocking {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val withKey: suspend () -> String? = { reads.incrementAndGet(); key }
        assertEquals(SatelliteChain.Mt.Text("hello", sealed = false), SatelliteChain.inbound("hello".toByteArray(), SatelliteChain.MODE_SEALED, withKey))
        assertEquals("a plain text never reads the key", 0, reads.get())
        val h = SatelliteChain.seal(greek, key)
        val legacy = SatelliteChain.seal(greek, key, chain = SatelliteChain.Chain.LEGACY)
        for (mode in listOf(null, SatelliteChain.MODE_PLAIN, SatelliteChain.MODE_SEALED, SatelliteChain.MODE_LEGACY)) {
            assertEquals("$mode opens H", SatelliteChain.Chain.H, (SatelliteChain.inbound(h, mode, withKey) as SatelliteChain.Mt.Text).chain)
            assertEquals("$mode opens legacy", SatelliteChain.Chain.LEGACY, (SatelliteChain.inbound(legacy, mode, withKey) as SatelliteChain.Mt.Text).chain)
            assertTrue("$mode, no key: unopened", SatelliteChain.inbound(h, mode) { null } is SatelliteChain.Mt.Unopened)
            assertTrue("$mode, wrong key: unopened", SatelliteChain.inbound(legacy, mode) { otherKey } is SatelliteChain.Mt.Unopened)
        }
    }
}
