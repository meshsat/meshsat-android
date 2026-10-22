package net.meshsat.android

import net.meshsat.android.lxmf.LxMessage
import net.meshsat.android.lxmf.LxMid
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.MpStr
import net.meshsat.android.rns.Msgpack
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * The HeMB-NG mid on LXMF (contract C2, S2-R7) against the Hub's fixture lxmf_mid_field.json,
 * byte for byte, and the receiver's (origin, mid) record across a restart. [MESHSAT-1641]
 */
class LxMidTest {

    private val fixture = JSONObject(
        File(requireNotNull(javaClass.classLoader?.getResource("hemb-ng/v1/lxmf_mid_field.json")).toURI()).readText()
    )
    private val mid = requireNotNull(net.meshsat.android.hubmsg.HubMsgIds.parseCanonical(fixture.getString("mid")))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `the fields encode as the fixture, byte for byte`() {
        assertEquals(fixture.getString("fields_msgpack_hex"), hex(Msgpack.pack(LxMid.fields(mid))))
    }

    @Test
    fun `the mid reads back from the fixture bytes`() {
        val bytes = fixture.getString("fields_msgpack_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val fields = Msgpack.unpack(bytes).first as MpMap
        assertArrayEquals(mid, LxMid.midOf(fields))
    }

    @Test
    fun `another custom type is never overwritten, and gives no mid`() {
        val other = MpMap(listOf(MpInt(LxMid.FIELD_CUSTOM_TYPE) to MpStr("sideband.thing/2"), MpInt(LxMid.FIELD_CUSTOM_DATA) to MpBin(byteArrayOf(1))))
        assertSame(other, LxMid.withMid(other, mid))
        assertNull(LxMid.midOf(other))
    }

    @Test
    fun `other fields stay and the mid is added after them`() {
        val title = MpMap(listOf(MpInt(0x01) to MpStr("x")))
        val out = LxMid.withMid(title, mid)
        assertEquals(3, out.entries.size)
        assertEquals(MpStr("x"), out[0x01])
        assertArrayEquals(mid, LxMid.midOf(out))
    }

    @Test
    fun `a wrong type or length gives no mid`() {
        assertNull(LxMid.midOf(MpMap(listOf(MpInt(0xFB) to MpStr("meshsat.mid/2"), MpInt(0xFC) to MpBin(mid)))))
        assertNull(LxMid.midOf(MpMap(listOf(MpInt(0xFB) to MpStr(LxMid.TYPE), MpInt(0xFC) to MpBin(mid.copyOf(15))))))
        assertNull(LxMid.midOf(MpMap(emptyList())))
    }

    @Test
    fun `a message keeps its mid through pack and unpack`() {
        val id = net.meshsat.android.rns.RnsIdentity.generate()
        val m = LxMessage(ByteArray(16) { 7 }, ByteArray(16) { 9 }, content = "Γεια".toByteArray(), fields = LxMid.fields(mid))
        m.pack(id)
        assertArrayEquals(mid, LxMid.midOf(LxMessage.unpack(m.packed).fields))
    }

    /**
     * The mid's room at this app's packet limits (r40 review, decision 4; LXMF 1.2.0): with it, 252
     * bytes of content fill a single packet and 253 do not (below 256 bytes msgpack's bin header is
     * one byte shorter); 283 fill a link packet and 284 do not. Without it the limits are 287 and
     * 319. (The Bridge follows LXMF 1.1.0: 295, so 259 there.)
     */
    @Test
    fun `the mid's room at the packet limits`() {
        fun size(n: Int, withMid: Boolean) =
            LxMessage(ByteArray(16) { 7 }, ByteArray(16) { 9 }, content = ByteArray(n) { 'x'.code.toByte() },
                fields = if (withMid) LxMid.fields(mid) else net.meshsat.android.rns.MpMap(emptyList())).contentSize()
        assertEquals(LxMessage.ENCRYPTED_PACKET_MAX_CONTENT, size(252, true))
        assertEquals(LxMessage.ENCRYPTED_PACKET_MAX_CONTENT + 1, size(253, true))
        assertEquals(LxMessage.LINK_PACKET_MAX_CONTENT, size(283, true))
        assertEquals(LxMessage.LINK_PACKET_MAX_CONTENT + 1, size(284, true))
        assertEquals(LxMessage.ENCRYPTED_PACKET_MAX_CONTENT, size(287, false))
        assertEquals(287, LxMessage.ENCRYPTED_PACKET_MAX_CONTENT)
        assertEquals(319, LxMessage.LINK_PACKET_MAX_CONTENT)
    }
}
