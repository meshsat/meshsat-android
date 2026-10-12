package net.meshsat.android.rns

import net.meshsat.android.routing.Identity
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

/**
 * The wire layer's own consistency (MESHSAT-1553). What Python RNS writes and
 * reads is covered by GoldenVectorTest (committed vectors) and OracleTest (live).
 */
class WireTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun addProvider() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        }
    }

    private val id = RnsIdentity.generate()
    private val dest = id.destHash("lxmf.delivery")

    @Test
    fun `every packet carries a context byte, flagged or not`() {
        val h = Header(destHash = dest, data = "x".toByteArray())
        val raw = h.pack()
        assertEquals(Rns.HEADER_MINSIZE + 1, raw.size)
        assertEquals(0, raw[18].toInt())
        val back = Header.unpack(raw)
        assertEquals(0, back.contextFlag)
        assertEquals(Rns.CTX_NONE, back.context)
        assertArrayEquals("x".toByteArray(), back.data)

        val h2 = Header(headerType = Rns.HEADER_2, transportType = Rns.TRANSPORT_TRANSPORT, contextFlag = 1,
            transportId = id.hash, destHash = dest, context = Rns.CTX_PATH_RESPONSE, hops = 7, data = byteArrayOf(1, 2, 3))
        val raw2 = h2.pack()
        assertEquals(Rns.HEADER_MAXSIZE + 3, raw2.size)
        val b2 = Header.unpack(raw2)
        assertEquals(1, b2.contextFlag)
        assertEquals(Rns.TRANSPORT_TRANSPORT, b2.transportType)
        assertEquals(Rns.CTX_PATH_RESPONSE, b2.context)
        assertEquals(7, b2.hops)
        assertArrayEquals(id.hash, b2.transportId)
        assertArrayEquals(dest, b2.destHash)
    }

    @Test
    fun `unpack rejects what RNS drops`() {
        assertNull(Header.unpackOrNull(ByteArray(18)))
        val raw = Header(destHash = dest, data = byteArrayOf(1)).pack()
        raw[1] = 128.toByte()
        assertNull(Header.unpackOrNull(raw))
        assertNull(Header.unpackOrNull(Header(destHash = dest).pack()))   // zero-length data
    }

    @Test
    fun `the packet hash survives the transport rewrite`() {
        val raw = Header(destHash = dest, hops = 3, data = "payload".toByteArray()).pack()
        val h1 = Header.packetHash(raw)
        val raw2 = Header.rewriteForTransport(raw, id.hash)
        val b = Header.unpack(raw2)
        assertEquals(Rns.HEADER_2, b.headerType)
        assertEquals(Rns.TRANSPORT_TRANSPORT, b.transportType)
        assertArrayEquals(h1, Header.packetHash(raw2))
        assertArrayEquals(raw, Header.stripTransport(raw2))
        assertArrayEquals(h1.copyOf(16), Header.truncatedPacketHash(raw2))
    }

    @Test
    fun `token round trips both key sizes and refuses a tampered byte`() {
        for (size in intArrayOf(32, 64)) {
            val t = Token(Rns.randomBytes(size))
            val plain = "token golden test payload, 40 bytes long!".toByteArray()
            val ct = t.encrypt(plain)
            assertEquals(16 + 48 + 32, ct.size)
            assertArrayEquals(plain, t.decrypt(ct))
            ct[20] = (ct[20].toInt() xor 1).toByte()
            assertFalse(t.verifyHmac(ct))
            try { t.decrypt(ct); throw AssertionError("tampered token accepted") } catch (_: WireException) {}
        }
        assertArrayEquals(ByteArray(0), Token(Rns.randomBytes(64)).let { it.decrypt(it.encrypt(ByteArray(0))) })
    }

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val ikm = "0b".repeat(22).unhex()
        val salt = "000102030405060708090a0b0c".unhex()
        val info = "f0f1f2f3f4f5f6f7f8f9".unhex()
        val okm = Hkdf.derive(42, ikm, salt, info)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun `identity round trips private bytes and encrypts to itself`() {
        val back = RnsIdentity.fromPrivateBytes(id.privateBytes())
        assertArrayEquals(id.publicBytes, back.publicBytes)
        assertArrayEquals(id.hash, back.hash)
        val pub = RnsIdentity.fromPublicBytes(id.publicBytes)
        assertFalse(pub.hasPrivateKey)
        val plain = "single packet plaintext".toByteArray()
        val ct = pub.encrypt(plain)
        val (got, idx) = id.decrypt(ct)!!
        assertArrayEquals(plain, got)
        assertEquals(-1, idx)
        assertTrue(pub.verify("data".toByteArray(), id.sign("data".toByteArray())))
        assertFalse(pub.verify("data!".toByteArray(), id.sign("data".toByteArray())))
    }

    @Test
    fun `ratchets are tried newest first and the identity key last`() {
        val r1 = RnsIdentity.generateRatchet()
        val r2 = RnsIdentity.generateRatchet()
        val pub = RnsIdentity.fromPublicBytes(id.publicBytes)
        val ct = pub.encrypt("r".toByteArray(), RnsIdentity.ratchetPublic(r1))
        assertEquals(1, id.decrypt(ct, listOf(r2, r1))!!.second)
        assertNull(id.decrypt(ct, listOf(r2)))
        val ct2 = pub.encrypt("i".toByteArray())
        assertEquals(-1, id.decrypt(ct2, listOf(r1, r2))!!.second)
    }

    @Test
    fun `the routing identity converts with the same keys`() {
        val routing = Identity.generate()
        val rns = RnsIdentity.fromRouting(routing)
        assertArrayEquals(routing.signingPubRaw, rns.sigPubRaw)
        assertArrayEquals(routing.encryptionPubRaw, rns.encPubRaw)
        val data = "cross-check".toByteArray()
        assertTrue(Identity.verifyWithRaw(routing.signingPubRaw, data, rns.sign(data)))
        assertTrue(rns.verify(data, routing.sign(data)))
    }

    @Test
    fun `announces sign everything the receiver checks`() {
        val a = Announce.create(id, "lxmf.delivery", "app".toByteArray())
        assertEquals(Announce.MIN_PAYLOAD + 3, a.payload().size)
        assertNull(a.verify())
        val back = Announce.unpackPacket(a.packet())
        assertNull(back.verify())
        assertEquals(0, back.contextFlag)
        assertArrayEquals("app".toByteArray(), back.appData)
        assertTrue(Math.abs(back.emittedAt() - System.currentTimeMillis() / 1000) <= 2)

        val rp = RnsIdentity.ratchetPublic(RnsIdentity.generateRatchet())
        val ar = Announce.create(id, "lxmf.delivery", null, ratchetPub = rp)
        assertEquals(Announce.RATCHET_PAYLOAD, ar.payload().size)
        val raw = ar.packet()
        assertEquals(0x21, raw[0].toInt() and 0xFF)   // context flag + ANNOUNCE
        val br = Announce.unpackPacket(raw)
        assertNull(br.verify())
        assertArrayEquals(rp, br.ratchet)
        raw[2 + 16 + 1 + 64 + 10 + 10] = (raw[2 + 16 + 1 + 64 + 10 + 10].toInt() xor 1).toByte()
        assertNotNull(Announce.unpackPacket(raw).verify())

        val tr = ar.transportPacket(id.hash, 2, Rns.CTX_PATH_RESPONSE)
        val bt = Announce.unpackPacket(tr)
        assertNull(bt.verify())
        assertEquals(2, bt.hops)
        assertEquals(Rns.CTX_PATH_RESPONSE, bt.context)
        assertEquals(Rns.HEADER_2, Header.unpack(tr).headerType)
    }

    @Test
    fun `link signalling and ids are what Link_py computes`() {
        val s = LinkWire.signalling(500, LinkWire.MODE_AES256_CBC)
        assertEquals("2001f4", s.hex())
        assertEquals(500 to 1, LinkWire.parseSignalling(s))
        assertEquals(431, LinkWire.mdu(500))
        val eph = RnsIdentity.generate()
        val req = LinkWire.Request(eph.encPubRaw, eph.sigPubRaw)
        val raw = Header(packetType = Rns.PACKET_LINKREQUEST, destHash = dest, data = req.pack()).pack()
        val rawPlain = Header(packetType = Rns.PACKET_LINKREQUEST, destHash = dest, data = req.pack().copyOf(64)).pack()
        assertArrayEquals(LinkWire.linkIdFromRequestPacket(raw), LinkWire.linkIdFromRequestPacket(rawPlain))
        assertTrue(LinkWire.Request.unpack(req.pack()).signalled)
        assertFalse(LinkWire.Request.unpack(req.pack().copyOf(64)).signalled)

        val lid = LinkWire.linkIdFromRequestPacket(raw)
        val signed = LinkWire.proofSignedData(lid, eph.encPubRaw, id.sigPubRaw, 500, 1)
        val pd = LinkWire.ProofData(id.sign(signed), eph.encPubRaw)
        val back = LinkWire.ProofData.unpack(pd.pack())
        assertEquals(99, pd.pack().size)
        assertTrue(id.verify(signed, back.signature))
        assertEquals(500, back.mtu)

        assertArrayEquals(id.publicBytes, LinkWire.parseIdentify(LinkWire.identifyData(id, lid), lid))
        assertNull(LinkWire.parseIdentify(LinkWire.identifyData(id, lid), Rns.randomBytes(16)))
        assertEquals(0.734, LinkWire.parseRttData(LinkWire.rttData(0.734)))
    }

    @Test
    fun `packet proofs validate in both forms`() {
        val raw = Header(destHash = dest, data = "x".toByteArray()).pack()
        val ph = Header.packetHash(raw)
        val impl = id.sign(ph)
        val expl = ph + impl
        assertTrue(Proof.validate(impl, ph, id.sigPubRaw))
        assertTrue(Proof.validate(expl, ph, id.sigPubRaw))
        assertFalse(Proof.validate(expl, Rns.sha256(ph), id.sigPubRaw))
        val pkt = Header.unpack(Proof.packet(raw, impl))
        assertEquals(Rns.PACKET_PROOF, pkt.packetType)
        assertArrayEquals(Header.truncatedPacketHash(raw), pkt.destHash)
    }

    @Test
    fun `ifac wraps and unwraps, and another network is refused`() {
        val f = Ifac("meshsat-test", "hunter2")
        val raw = Announce.create(id, "meshsat.node", null).packet()
        val wrapped = f.wrap(raw)
        assertEquals(raw.size + 16, wrapped.size)
        assertTrue(Ifac.hasFlag(wrapped))
        assertArrayEquals(raw, f.unwrap(wrapped))
        try { Ifac("other", "").unwrap(wrapped); throw AssertionError("wrong network accepted") } catch (_: WireException) {}
    }

    @Test
    fun `hdlc frames survive splitting and garbage`() {
        val pkt = ByteArray(40) { (it * 7).toByte() } + byteArrayOf(0x7E, 0x7D)
        val frame = Hdlc.frame(pkt)
        val r = Hdlc.FrameReader()
        assertTrue(r.feed("junk".toByteArray()).isEmpty())
        assertTrue(r.feed(frame.copyOfRange(0, 10)).isEmpty())
        val got = r.feed(frame.copyOfRange(10, frame.size) + frame)
        assertEquals(2, got.size)
        assertArrayEquals(pkt, got[0])
        assertArrayEquals(pkt, got[1])
        assertTrue(Hdlc.FrameReader().feed(Hdlc.frame(ByteArray(5))).isEmpty())   // below a header, dropped
    }

    @Test
    fun `msgpack round trips the shapes LXMF uses`() {
        val v = MpArray(
            MpFloat(1758814000.123), MpBin("title".toByteArray()), MpBin(ByteArray(300) { 'x'.code.toByte() }),
            MpMap(MpInt(1) to MpStr("hello"), MpInt(16) to MpArray(MpInt(-5), MpInt(200), MpInt(70000), MpInt(-200), MpBool(true), MpNil)),
            MpInt(5000000000L), MpInt(-5000000000L), MpStr("s".repeat(40)), MpInt(-1, uint64 = true),
        )
        val packed = Msgpack.pack(v)
        val (back, n) = Msgpack.unpack(packed)
        assertEquals(packed.size, n)
        assertEquals(v, back)
        assertArrayEquals(packed, Msgpack.pack(back))
        assertEquals("cb", Msgpack.pack(MpFloat(1.0)).copyOf(1).hex())
        assertEquals("7f", Msgpack.pack(MpInt(127)).hex())
        assertEquals("cc80", Msgpack.pack(MpInt(128)).hex())
        assertEquals("e0", Msgpack.pack(MpInt(-32)).hex())
        assertEquals("d0df", Msgpack.pack(MpInt(-33)).hex())
        assertEquals(MpFloat(1.5), Msgpack.unpack("ca3fc00000".unhex()).first)
        assertEquals(MpStr("hello"), (back as MpArray).items[3].let { (it as MpMap)[1L] })
    }
}
