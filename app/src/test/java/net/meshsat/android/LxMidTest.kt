package net.meshsat.android

import net.meshsat.android.lxmf.LxMessage
import net.meshsat.android.lxmf.LxMid
import net.meshsat.android.lxmf.LxMidInbox
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.MpStr
import net.meshsat.android.rns.Msgpack
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun `a repeat is dropped, also after a restart, and forgotten after the retention`() {
        val dir = Files.createTempDirectory("lxmid").toFile()
        val f = File(dir, "lxmf_mids.tsv")
        var t = 1_000_000L
        val a = LxMidInbox(f, now = { t }, retentionMs = 10_000)
        assertTrue(a.firstTime("aa", mid))
        assertFalse(a.firstTime("aa", mid))
        assertTrue("another sender, the same mid", a.firstTime("bb", mid))
        val restarted = LxMidInbox(f, now = { t }, retentionMs = 10_000)
        assertFalse("after a restart", restarted.firstTime("aa", mid))
        t += 20_000
        val later = LxMidInbox(f, now = { t }, retentionMs = 10_000)
        assertTrue("past the retention", later.firstTime("aa", mid))
        f.appendText("garbage line\n")
        assertFalse(LxMidInbox(f, now = { t }, retentionMs = 10_000).firstTime("aa", mid))
        dir.deleteRecursively()
    }
}
