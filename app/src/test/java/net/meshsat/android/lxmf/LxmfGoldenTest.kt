package net.meshsat.android.lxmf

import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.Msgpack
import net.meshsat.android.rns.Rns
import net.meshsat.android.rns.RnsIdentity
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import java.util.Properties

/** Python LXMF 1.2.0 wrote every vector (scripts/rns-golden.py); the Kotlin side must read them. [MESHSAT-1553] */
class LxmfGoldenTest {
    companion object {
        lateinit var g: Properties

        @JvmStatic
        @BeforeClass
        fun load() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            g = Properties()
            LxmfGoldenTest::class.java.getResourceAsStream("/rns/golden.properties")!!.use { g.load(it) }
        }
    }

    private fun hex(k: String): ByteArray = g.getProperty(k)!!.let { s -> ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }
    private fun str(k: String): String = g.getProperty(k)!!

    @Test
    fun `a Python message unpacks, verifies and re-hashes identically`() {
        val src = RnsIdentity.fromPrivateBytes(hex("lxmf.src_prv"))
        val dst = RnsIdentity.fromPrivateBytes(hex("lxmf.dst_prv"))
        assertArrayEquals(hex("lxmf.src_dest"), src.destHash(LxMessage.DELIVERY_NAME))
        assertArrayEquals(hex("lxmf.dst_dest"), dst.destHash(LxMessage.DELIVERY_NAME))
        // The message went from dst to src.
        val m = LxMessage.unpack(hex("lxmf.packed")) { source -> if (source.contentEquals(dst.destHash(LxMessage.DELIVERY_NAME))) dst.sigPubRaw else null }
        assertArrayEquals(hex("lxmf.hash"), m.hash)
        assertTrue(m.sourceKnown)
        assertTrue(m.signatureValid)
        assertArrayEquals(hex("lxmf.content"), m.content)
        assertArrayEquals(hex("lxmf.title"), m.title)
        assertEquals(str("lxmf.timestamp").toDouble(), m.timestamp, 0.0)
        assertNull(m.stamp)
        assertEquals(0, m.fields.entries.size)
        // An unknown source leaves the signature unverified but the message intact.
        val u = LxMessage.unpack(hex("lxmf.packed")) { null }
        assertFalse(u.sourceKnown); assertFalse(u.signatureValid)
        assertArrayEquals(m.hash, u.hash)
    }

    @Test
    fun `fields with unknown keys and a stamp at cost 8 survive`() {
        val dst = RnsIdentity.fromPrivateBytes(hex("lxmf.dst_prv"))
        val m = LxMessage.unpack(hex("lxmf2.packed")) { dst.sigPubRaw }
        assertTrue(m.signatureValid)
        assertArrayEquals(hex("lxmf2.hash"), m.hash)
        assertArrayEquals(hex("lxmf2.stamp"), m.stamp)
        assertArrayEquals(hex("lxmf2.fields_packed"), Msgpack.pack(m.fields))
        assertEquals(3, m.fields.entries.size)
        assertTrue(m.fields[0xABL] != null)   // unknown to us, kept
        val wb = LxStamper.workblock(m.hash)
        assertArrayEquals(hex("lxmf2.workblock_sha256"), Rns.sha256(wb))
        assertTrue(LxStamper.stampValid(wb, m.stamp!!, str("lxmf2.stamp_cost").toInt()))
        assertEquals(str("lxmf2.stamp_value").toInt(), LxStamper.stampValue(wb, m.stamp!!))
        assertFalse(LxStamper.stampValid(wb, m.stamp!!, 40))
    }

    @Test
    fun `announce app data reads and writes like LXMF`() {
        val info = LxmfAppData.parse(hex("lxmf.app_data"))
        assertEquals("MeshSat kit", info.displayName); assertEquals(8, info.stampCost); assertTrue(info.compression)
        val none = LxmfAppData.parse(hex("lxmf.app_data_noname"))
        assertNull(none.displayName); assertEquals(0, none.stampCost)
        val legacy = LxmfAppData.parse(hex("lxmf.app_data_legacy"))
        assertEquals(str("lxmf.display_name_legacy"), legacy.displayName); assertTrue(legacy.legacy)
        assertArrayEquals(hex("lxmf.app_data"), LxmfAppData.encode("MeshSat kit", 8, compression = true))
        val ours = LxmfAppData.parse(LxmfAppData.encode("Phone", 0))
        assertEquals("Phone", ours.displayName); assertEquals(0, ours.stampCost); assertFalse(ours.compression)
        val peer = LxmfAppData.parse(hex("announce.app_data"))
        assertEquals("Python peer", peer.displayName); assertEquals(12, peer.stampCost)
    }

    @Test
    fun `our own packing round-trips and sizes follow LXMessage`() {
        val src = RnsIdentity.generate(); val dst = RnsIdentity.generate()
        val m = LxMessage(dst.destHash(LxMessage.DELIVERY_NAME), src.destHash(LxMessage.DELIVERY_NAME), title = "hi".toByteArray(), content = "hello from the phone".toByteArray(), fields = MpMap(emptyList()))
        m.pack(src)
        val back = LxMessage.unpack(m.packed) { src.sigPubRaw }
        assertTrue(back.signatureValid)
        assertArrayEquals(m.hash, back.hash)
        assertEquals("hello from the phone", back.contentString)
        assertEquals(287, LxMessage.ENCRYPTED_PACKET_MAX_CONTENT)
        assertEquals(319, LxMessage.LINK_PACKET_MAX_CONTENT)
        assertEquals(112, LxMessage.OVERHEAD)
        val stamped = LxMessage(dst.destHash(LxMessage.DELIVERY_NAME), src.destHash(LxMessage.DELIVERY_NAME), content = "stamped".toByteArray())
        stamped.pack(src, 6) { h, c -> LxStamper.generateStamp(h, c)?.first }
        val sb = LxMessage.unpack(stamped.packed) { src.sigPubRaw }
        assertTrue(sb.signatureValid)
        assertTrue(LxStamper.stampValid(LxStamper.workblock(sb.hash), sb.stamp!!, 6))
    }
}
