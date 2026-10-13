package net.meshsat.android.lxmf

import net.meshsat.android.interop.PyEnv
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.hex
import net.meshsat.android.rns.unhex
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/** Python LXMF 1.2.0 reads what we pack: messages, stamps and announce app data. Skipped without the venv. [MESHSAT-1553] */
class LxmfOracleTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            PyEnv.python()
        }
    }

    @Test
    fun `python verifies our message and we verify its reply`() {
        val src = RnsIdentity.generate(); val dst = RnsIdentity.generate()
        val m = LxMessage(dst.destHash(LxMessage.DELIVERY_NAME), src.destHash(LxMessage.DELIVERY_NAME), title = "hi".toByteArray(), content = "hello from the phone".toByteArray())
        m.pack(src)
        val r = PyEnv.pyKv("""
import sys, RNS, LXMF
RNS.loglevel = 0
src = RNS.Identity.from_bytes(bytes.fromhex(sys.argv[1])); dst = RNS.Identity.from_bytes(bytes.fromhex(sys.argv[2]))
for i in (src, dst):
    RNS.Identity.known_destinations[RNS.Destination.hash_from_name_and_identity("lxmf.delivery", i)] = [0, None, i.get_public_key(), None]
lxm = LXMF.LXMessage.unpack_from_bytes(bytes.fromhex(sys.argv[3]))
d_src = RNS.Destination(src, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
d_dst = RNS.Destination(dst, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
reply = LXMF.LXMessage(d_src, d_dst, b"reply from python", title=b"re", desired_method=LXMF.LXMessage.OPPORTUNISTIC)
reply.pack()
print("hash=" + lxm.hash.hex()); print("valid=" + str(bool(lxm.signature_validated))); print("content=" + lxm.content.decode()); print("packed=" + reply.packed.hex())
""", src.privateBytes().hex(), dst.privateBytes().hex(), m.packed.hex())
        assertEquals(m.hashHex, r["hash"]); assertEquals("True", r["valid"]); assertEquals("hello from the phone", r["content"])
        val back = LxMessage.unpack(r["packed"]!!.unhex()) { s -> if (s.contentEquals(dst.destHash(LxMessage.DELIVERY_NAME))) dst.sigPubRaw else null }
        assertTrue(back.sourceKnown && back.signatureValid)
        assertEquals("reply from python", back.contentString); assertEquals("re", back.titleString)
        // Python's content_size equals ours.
        val cs = PyEnv.pyKv("""
import sys, RNS, LXMF
import RNS.vendor.umsgpack as um
packed = bytes.fromhex(sys.argv[1])
payload = packed[16+16+64:]
print("size=" + str(len(payload) - LXMF.LXMessage.TIMESTAMP_SIZE - LXMF.LXMessage.STRUCT_OVERHEAD))
""", m.packed.hex())
        assertEquals(m.contentSize().toString(), cs["size"])
    }

    @Test
    fun `python accepts our stamp at cost 8 and our announce app data`() {
        val src = RnsIdentity.generate(); val dst = RnsIdentity.generate()
        val m = LxMessage(dst.destHash(LxMessage.DELIVERY_NAME), src.destHash(LxMessage.DELIVERY_NAME), content = "stamped".toByteArray(), fields = MpMap(emptyList()))
        m.pack(src, 8) { h, c -> LxStamper.generateStamp(h, c)?.first }
        val app = LxmfAppData.encode("MeshSat phone", 8)
        val r = PyEnv.pyKv("""
import sys, RNS, LXMF
RNS.loglevel = 0
src = RNS.Identity.from_bytes(bytes.fromhex(sys.argv[1])); dst = RNS.Identity.from_bytes(bytes.fromhex(sys.argv[2]))
for i in (src, dst):
    RNS.Identity.known_destinations[RNS.Destination.hash_from_name_and_identity("lxmf.delivery", i)] = [0, None, i.get_public_key(), None]
lxm = LXMF.LXMessage.unpack_from_bytes(bytes.fromhex(sys.argv[3]))
app = bytes.fromhex(sys.argv[4])
print("stamp_ok=" + str(bool(lxm.validate_stamp(8)) and bool(lxm.signature_validated)))
print("name=" + str(LXMF.display_name_from_app_data(app))); print("cost=" + str(LXMF.stamp_cost_from_app_data(app)))
print("compression=" + str(LXMF.compression_support_from_app_data(app)))
""", src.privateBytes().hex(), dst.privateBytes().hex(), m.packed.hex(), app.hex())
        assertEquals("True", r["stamp_ok"]); assertEquals("MeshSat phone", r["name"]); assertEquals("8", r["cost"]); assertEquals("False", r["compression"])
    }
}
