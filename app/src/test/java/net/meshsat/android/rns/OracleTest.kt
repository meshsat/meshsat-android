package net.meshsat.android.rns

import net.meshsat.android.interop.PyEnv
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/**
 * The live oracle: upstream Python RNS reads what the Kotlin wire code writes.
 * Skipped unless the pinned venv exists (PyEnv). [MESHSAT-1553]
 */
class OracleTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            PyEnv.python()
        }
    }

    @Test
    fun `python decrypts our token and we decrypt its`() {
        val key = Rns.randomBytes(64)
        val tok = Token(key)
        val plain = "token golden test payload, 40 bytes long!".toByteArray()
        val r = PyEnv.pyKv("""
import sys
from RNS.Cryptography import Token
key = bytes.fromhex(sys.argv[1]); ct = bytes.fromhex(sys.argv[2]); plain = bytes.fromhex(sys.argv[3])
tok = Token(key)
print("plain=" + tok.decrypt(ct).hex()); print("ct=" + tok.encrypt(plain).hex())
""", key.hex(), tok.encrypt(plain).hex(), plain.hex())
        assertEquals(plain.hex(), r["plain"])
        assertArrayEquals(plain, tok.decrypt(r["ct"]!!.unhex()))
    }

    @Test
    fun `python decrypts our single packet, plain and ratcheted, and our hashes agree`() {
        val id = RnsIdentity.generate()
        val plain = "single packet plaintext for lxmf.delivery".toByteArray()
        val ratchet = RnsIdentity.generateRatchet()
        val rpub = RnsIdentity.ratchetPublic(ratchet)
        val r = PyEnv.pyKv("""
import sys, RNS
prv = bytes.fromhex(sys.argv[1]); ct = bytes.fromhex(sys.argv[2]); rct = bytes.fromhex(sys.argv[3]); ratchet = bytes.fromhex(sys.argv[4]); rpub = bytes.fromhex(sys.argv[5]); plain = bytes.fromhex(sys.argv[6])
ident = RNS.Identity.from_bytes(prv)
pub = RNS.Identity(create_keys=False); pub.load_public_key(ident.get_public_key())
print("plain=" + ident.decrypt(ct).hex())
print("rplain=" + ident.decrypt(rct, ratchets=[ratchet], enforce_ratchets=True).hex())
print("ct=" + pub.encrypt(plain).hex())
print("rct=" + pub.encrypt(plain, ratchet=rpub).hex())
print("hash=" + ident.hash.hex())
print("dest=" + RNS.Destination.hash(ident, "lxmf", "delivery").hex())
print("rpub=" + RNS.Identity._ratchet_public_bytes(ratchet).hex())
""", id.privateBytes().hex(), id.encrypt(plain).hex(), id.encrypt(plain, rpub).hex(), ratchet.hex(), rpub.hex(), plain.hex())
        assertEquals(plain.hex(), r["plain"])
        assertEquals(plain.hex(), r["rplain"])
        assertEquals(id.hashHex, r["hash"])
        assertEquals(id.destHash("lxmf.delivery").hex(), r["dest"])
        assertEquals(rpub.hex(), r["rpub"])
        assertEquals(-1, id.decrypt(r["ct"]!!.unhex())!!.second)
        assertEquals(0, id.decrypt(r["rct"]!!.unhex(), listOf(ratchet))!!.second)
    }

    @Test
    fun `python accepts our announces, with a ratchet, in transport and as proof`() {
        val id = RnsIdentity.generate()
        val rpub = RnsIdentity.ratchetPublic(RnsIdentity.generateRatchet())
        val app = Msgpack.pack(MpArray(MpBin("Kotlin peer".toByteArray()), MpInt(8), MpArray(MpInt(0))))
        val a = Announce.create(id, "lxmf.delivery", app, ratchetPub = rpub)
        val tr = a.transportPacket(Rns.randomBytes(16), 2, Rns.CTX_PATH_RESPONSE)
        val plainA = Announce.create(id, "meshsat.node", null)
        val proved = Header(destHash = id.destHash("lxmf.delivery"), data = "x".toByteArray()).pack()
        val ph = Header.packetHash(proved)
        val r = PyEnv.pyKv("""
import sys, RNS, LXMF
RNS.loglevel = 0
def pk(h):
    p = RNS.Packet(None, None); p.raw = bytes.fromhex(h); assert p.unpack(); return p
a = pk(sys.argv[1]); t = pk(sys.argv[2]); pa = pk(sys.argv[3]); proved = pk(sys.argv[4])
print("a=" + str(bool(RNS.Identity.validate_announce(a, only_validate_signature=True))))
print("t=" + str(bool(RNS.Identity.validate_announce(t, only_validate_signature=True))))
print("pa=" + str(bool(RNS.Identity.validate_announce(pa, only_validate_signature=True))))
print("context=" + str(t.context)); print("hops=" + str(t.hops)); print("cf=" + str(a.context_flag))
ratchet = a.data[64+10+10:64+10+10+32]
print("ratchet=" + ratchet.hex())
app = a.data[64+10+10+32+64:]
print("name=" + LXMF.display_name_from_app_data(app)); print("cost=" + str(LXMF.stamp_cost_from_app_data(app)))
ident = RNS.Identity(create_keys=False); ident.load_public_key(a.data[:64])
print("impl=" + str(bool(ident.validate(bytes.fromhex(sys.argv[5]), proved.packet_hash))))
print("hash=" + proved.packet_hash.hex())
""", a.packet().hex(), tr.hex(), plainA.packet().hex(), proved.hex(), id.sign(ph).hex())
        assertEquals("True", r["a"]); assertEquals("True", r["t"]); assertEquals("True", r["pa"])
        assertEquals("11", r["context"]); assertEquals("2", r["hops"]); assertEquals("1", r["cf"])
        assertEquals(rpub.hex(), r["ratchet"])
        assertEquals("Kotlin peer", r["name"]); assertEquals("8", r["cost"])
        assertEquals("True", r["impl"])
        assertEquals(ph.hex(), r["hash"])
    }

    @Test
    fun `python derives the same link id, key and accepts our link proof`() {
        val id = RnsIdentity.generate()
        val eph = RnsIdentity.generate()
        val req = LinkWire.Request(eph.encPubRaw, eph.sigPubRaw)
        val raw = Header(packetType = Rns.PACKET_LINKREQUEST, destHash = id.destHash("lxmf.delivery"), data = req.pack()).pack()
        val lid = LinkWire.linkIdFromRequestPacket(raw)
        val shared = Rns.randomBytes(32)
        val resp = RnsIdentity.generate()
        val signed = LinkWire.proofSignedData(lid, resp.encPubRaw, id.sigPubRaw, 500, 1)
        val proof = LinkWire.ProofData(id.sign(signed), resp.encPubRaw).pack()
        val r = PyEnv.pyKv("""
import sys, RNS
from RNS.Link import Link
p = RNS.Packet(None, None); p.raw = bytes.fromhex(sys.argv[1]); assert p.unpack()
lid = Link.link_id_from_lr_packet(p); shared = bytes.fromhex(sys.argv[2])
print("link_id=" + lid.hex()); print("sig=" + Link.signalling_bytes(500, Link.MODE_AES256_CBC).hex())
print("mtu=" + str(Link.mtu_from_lr_packet(p))); print("mode=" + str(Link.mode_from_lr_packet(p)))
print("key=" + RNS.Cryptography.hkdf(length=64, derive_from=shared, salt=lid).hex())
pub = bytes.fromhex(sys.argv[3]); data = bytes.fromhex(sys.argv[4])
ident = RNS.Identity(create_keys=False); ident.load_public_key(pub)
SIG = RNS.Identity.SIGLENGTH//8; EC = Link.ECPUBSIZE//2
sig = data[:SIG]; peer_pub = data[SIG:SIG+EC]; mtu_bytes = data[SIG+EC:SIG+EC+3]
mtu = ((mtu_bytes[0] << 16) + (mtu_bytes[1] << 8) + mtu_bytes[2]) & Link.MTU_BYTEMASK
mode = mtu_bytes[0] >> 5
signed = lid + peer_pub + ident.get_public_key()[EC:] + Link.signalling_bytes(mtu, mode)
print("valid=" + str(bool(ident.validate(sig, signed))))
import RNS.vendor.umsgpack as um
print("rtt=" + um.packb(0.734).hex())
""", raw.hex(), shared.hex(), id.publicBytes.hex(), proof.hex())
        assertEquals(lid.hex(), r["link_id"])
        assertEquals(LinkWire.signalling(500, 1).hex(), r["sig"])
        assertEquals("500", r["mtu"]); assertEquals("1", r["mode"])
        assertEquals(LinkWire.deriveLinkKey(shared, lid).hex(), r["key"])
        assertEquals("True", r["valid"])
        assertEquals(LinkWire.rttData(0.734).hex(), r["rtt"])
    }

    @Test
    fun `python validates our ifac packets and we its`() {
        val f = Ifac("meshsat-test", "hunter2")
        val raw = Announce.create(RnsIdentity.generate(), "meshsat.node", null).packet()
        val r = PyEnv.pyKv("""
import sys, RNS
class Iface:
    def ifac_violation(self, *a): pass
i = Iface()
origin = RNS.Identity.full_hash(b"meshsat-test") + RNS.Identity.full_hash(b"hunter2")
i.ifac_key = RNS.Cryptography.hkdf(length=64, derive_from=RNS.Identity.full_hash(origin), salt=RNS.Reticulum.IFAC_SALT, context=None)
i.ifac_identity = RNS.Identity.from_bytes(i.ifac_key); i.ifac_size = 16
raw = bytes.fromhex(sys.argv[1]); wrapped = bytes.fromhex(sys.argv[2])
valid, clean = RNS.Transport.handle_ifac(wrapped, i)
print("valid=" + str(bool(valid))); print("clean=" + (clean.hex() if clean else ""))
print("wrapped=" + RNS.Transport.handle_outgoing_ifac(i, raw).hex())
""", raw.hex(), f.wrap(raw).hex())
        assertEquals("True", r["valid"])
        assertEquals(raw.hex(), r["clean"])
        assertArrayEquals(raw, f.unwrap(r["wrapped"]!!.unhex()))
    }

    @Test
    fun `umsgpack re-encodes our msgpack identically`() {
        val v = MpArray(
            MpFloat(1758814000.123), MpBin("title".toByteArray()), MpBin(ByteArray(300) { 'x'.code.toByte() }),
            MpMap(MpInt(1) to MpStr("hello"), MpInt(16) to MpArray(MpInt(-5), MpInt(200), MpInt(70000), MpInt(-200), MpBool(true), MpNil)),
            MpInt(5000000000L), MpInt(-5000000000L), MpStr("s".repeat(40)),
        )
        val packed = Msgpack.pack(v)
        val r = PyEnv.pyKv("""
import sys
import RNS.vendor.umsgpack as m
b = bytes.fromhex(sys.argv[1]); v = m.unpackb(b)
print("same=" + str(m.packb(v) == b))
""", packed.hex())
        assertEquals("True", r["same"])
        assertTrue(Msgpack.unpack(packed).second == packed.size)
    }
}
