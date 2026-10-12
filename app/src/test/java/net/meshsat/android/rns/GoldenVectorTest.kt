package net.meshsat.android.rns

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import java.util.Properties

/**
 * Python RNS and LXMF wrote every value in rns/golden.properties
 * (scripts/rns-golden.py, pinned venv). The Kotlin side must read them all;
 * where it writes too (Token, IFAC, announces) Python must read ours, which
 * OracleTest proves with the live interpreter. [MESHSAT-1553]
 */
class GoldenVectorTest {
    companion object {
        lateinit var g: Properties

        @JvmStatic
        @BeforeClass
        fun load() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            g = Properties()
            val s = GoldenVectorTest::class.java.getResourceAsStream("/rns/golden.properties")
                ?: throw AssertionError("rns/golden.properties missing from test resources")
            s.use { g.load(it) }
        }
    }

    private fun hex(k: String): ByteArray = (g.getProperty(k) ?: throw AssertionError("no vector $k")).unhex()
    private fun str(k: String): String = g.getProperty(k) ?: throw AssertionError("no vector $k")
    private fun int(k: String): Int = str(k).toInt()

    @Test
    fun `pinned to the versions the clients ship`() {
        assertEquals("1.5.5", str("rns.version"))
        assertEquals("1.2.0", str("lxmf.version"))
    }

    @Test
    fun `token decrypts what Python encrypted, both key sizes`() {
        for (n in listOf("token256", "token128")) {
            assertArrayEquals(hex("$n.plain"), Token(hex("$n.key")).decrypt(hex("$n.ct")))
        }
    }

    @Test
    fun `hkdf agrees with RNS Cryptography hkdf`() {
        assertArrayEquals(hex("hkdf.a"), Hkdf.derive(64, hex("hkdf.ikm"), hex("hkdf.salt")))
        assertArrayEquals(hex("hkdf.b"), Hkdf.derive(48, hex("hkdf.ikm"), null))
    }

    @Test
    fun `identity loads Python private bytes and decrypts its packets`() {
        val id = RnsIdentity.fromPrivateBytes(hex("identity.prv"))
        assertArrayEquals(hex("identity.pub"), id.publicBytes)
        assertArrayEquals(hex("identity.hash"), id.hash)
        assertArrayEquals(hex("identity.lxmf_dest"), id.destHash("lxmf.delivery"))
        assertArrayEquals(hex("identity.name_hash"), Rns.nameHash("lxmf.delivery"))
        val (plain, idx) = id.decrypt(hex("identity.ct"))!!
        assertArrayEquals(hex("identity.plain"), plain)
        assertEquals(-1, idx)
        assertTrue(id.verify(hex("identity.sig_data"), hex("identity.sig")))
        assertArrayEquals(hex("ratchet.pub"), RnsIdentity.ratchetPublic(hex("ratchet.prv")))
        val (rp, ridx) = id.decrypt(hex("ratchet.ct"), listOf(hex("ratchet.prv")))!!
        assertArrayEquals(hex("ratchet.plain"), rp)
        assertEquals(0, ridx)
        assertNull(id.decrypt(hex("ratchet.ct")))   // the ratchet is not the identity key
    }

    @Test
    fun `packet hashes match Packet_py in both header forms`() {
        assertArrayEquals(hex("packet.hash"), Header.packetHash(hex("packet.raw")))
        assertArrayEquals(hex("packet.trunc"), Header.truncatedPacketHash(hex("packet.raw")))
        assertArrayEquals(hex("packet.hash2"), Header.packetHash(hex("packet.raw2")))
        val h = Header.unpack(hex("packet.raw2"))
        assertEquals(Rns.HEADER_2, h.headerType)
        assertEquals(3, h.hops)
        assertArrayEquals(hex("packet.raw"), Header.stripTransport(hex("packet.raw2")))
    }

    @Test
    fun `link ids, signalling and keys match Link_py`() {
        val raw = hex("link.request_raw")
        assertArrayEquals(hex("link.id"), LinkWire.linkIdFromRequestPacket(raw))
        assertArrayEquals(hex("link.id"), LinkWire.linkIdFromRequestPacket(hex("link.request_raw_unsignalled")))
        assertArrayEquals(hex("link.signalling_500_1"), LinkWire.signalling(500, 1))
        assertArrayEquals(hex("link.signalling_262144_1"), LinkWire.signalling(262144, 1))
        val req = LinkWire.Request.unpack(Header.unpack(raw).data)
        assertEquals(int("link.mtu"), req.mtu)
        assertEquals(int("link.mode"), req.mode)
        assertArrayEquals(hex("link.key"), LinkWire.deriveLinkKey(hex("link.shared"), hex("link.id")))
    }

    @Test
    fun `a link proof signed by Python verifies`() {
        val id = RnsIdentity.fromPublicBytes(hex("identity.pub"))
        val lid = hex("linkproof.link_id")
        val pd = LinkWire.ProofData.unpack(hex("linkproof.data"))
        assertTrue(pd.signalled)
        assertTrue(id.verify(LinkWire.proofSignedData(lid, pd.ephPub, id.sigPubRaw, pd.mtu, pd.mode), pd.signature))
        val old = LinkWire.ProofData.unpack(hex("linkproof.data_unsignalled"))
        assertFalse(old.signalled)
        assertTrue(id.verify(LinkWire.proofSignedData(lid, old.ephPub, id.sigPubRaw, old.mtu, old.mode, signalled = false), old.signature))
    }

    @Test
    fun `rtt values are umsgpack float64`() {
        assertArrayEquals(hex("rtt.packed_0_734"), LinkWire.rttData(0.734))
        assertEquals(1.25, LinkWire.parseRttData(hex("rtt.packed_1_25")))
    }

    @Test
    fun `python proofs validate and address the truncated hash`() {
        val id = RnsIdentity.fromPublicBytes(hex("identity.pub"))
        val ph = Header.packetHash(hex("proof.raw"))
        assertArrayEquals(hex("proof.hash"), ph)
        assertTrue(Proof.validate(hex("proof.impl"), ph, id.sigPubRaw))
        assertTrue(Proof.validate(hex("proof.expl"), ph, id.sigPubRaw))
        assertArrayEquals(hex("proof.dest"), Header.unpack(Proof.packet(hex("proof.raw"), hex("proof.impl"))).destHash)
    }

    @Test
    fun `announces from Python parse and verify, with and without a ratchet`() {
        val a1 = Announce.unpackPacket(hex("announce1.raw"))
        assertNull(a1.verify())
        assertEquals(0, a1.contextFlag)
        assertNull(a1.ratchet)
        assertEquals(0, a1.appData.size)
        assertEquals(str("announce.ts").toLong(), a1.emittedAt())

        val a2 = Announce.unpackPacket(hex("announce2.raw"))
        assertNull(a2.verify())
        assertEquals(1, a2.contextFlag)
        assertArrayEquals(hex("ratchet.pub"), a2.ratchet)
        assertArrayEquals(hex("announce.app_data"), a2.appData)
        assertArrayEquals(hex("identity.lxmf_dest"), a2.destHash)
        assertArrayEquals(a2.packet(), hex("announce2.raw"))   // re-marshals byte for byte

        val a3 = Announce.unpackPacket(hex("announce3.raw"))
        assertNull(a3.verify())
        assertEquals(2, a3.hops)
        assertEquals(Rns.CTX_PATH_RESPONSE, a3.context)
        assertEquals(Rns.HEADER_2, Header.unpack(hex("announce3.raw")).headerType)

        assertNotNull(Announce.unpackPacket(hex("announce.bad")).verify())
    }

    @Test
    fun `the path request destination is the plain hash`() {
        assertArrayEquals(hex("plain.path_request_dest"), Rns.PATH_REQUEST_DEST_HASH)
    }

    @Test
    fun `ifac keys and wrapping match Transport_py`() {
        val f = Ifac(str("ifac.netname"), str("ifac.netkey"))
        assertArrayEquals(hex("ifac.key"), f.key)
        assertArrayEquals(hex("ifac.wrapped"), f.wrap(hex("ifac.clean")))   // Ed25519 is deterministic
        assertArrayEquals(hex("ifac.clean"), f.unwrap(hex("ifac.wrapped")))
        try { f.unwrap(hex("ifac.wrapped_other")); throw AssertionError("other network accepted") } catch (_: WireException) {}
    }

    @Test
    fun `msgpack re-encodes umsgpack output byte for byte`() {
        val packed = hex("msgpack.complex")
        val (v, n) = Msgpack.unpack(packed)
        assertEquals(packed.size, n)
        assertArrayEquals(packed, Msgpack.pack(v))
        val items = (v as MpArray).items
        assertEquals(MpInt(-1, uint64 = true), items[7])
        assertEquals(MpInt(-(1L shl 40)), items[8])
        assertArrayEquals(hex("msgpack.float32_reencoded"), Msgpack.pack(Msgpack.unpack(hex("msgpack.float32")).first))
    }
}
