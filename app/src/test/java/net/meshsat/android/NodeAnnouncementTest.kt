package net.meshsat.android

import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.Portnums
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.ble.MeshtasticProtoAdapter
import net.meshsat.android.ble.MeshtasticProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node's name, learned on the air (MESHSAT-1287).
 *
 * The owner wrote to a node and the conversation was headed "Node !4370c1d8". An announcement
 * heard after connecting was parsed with the reader for the radio's own node list, which finds
 * nothing in a packet, so the name was dropped every time.
 */
class NodeAnnouncementTest {

    private fun announcement(from: Long, long: String, short: String): ByteArray {
        val user = MeshProtos.User.newBuilder().setId("!4370c1d8").setLongName(long).setShortName(short).build()
        val data = MeshProtos.Data.newBuilder().setPortnum(Portnums.PortNum.NODEINFO_APP).setPayload(user.toByteString())
        val pkt = MeshProtos.MeshPacket.newBuilder().setFrom(from.toInt()).setTo(-1).setDecoded(data).setRxSnr(6.5f)
        return MeshProtos.FromRadio.newBuilder().setPacket(pkt).build().toByteArray()
    }

    @Test
    fun `an announcement heard on the air carries the node's name`() {
        val result = MeshtasticProtocol.parseFromRadioFull(announcement(0x4370c1d8L, "MeshSat tesseract", "TESS"))
        val info = result?.nodeInfo
        assertNotNull(info)
        assertEquals(0x4370c1d8L, info!!.nodeNum)
        assertEquals("MeshSat tesseract", info.longName)
        assertEquals("TESS", info.shortName)
    }

    @Test
    fun `a node number above 2 to the 31 stays positive`() {
        val info = MeshtasticProtocol.parseFromRadioFull(announcement(0xbf6ee7bcL, "MeshSat flaneur", "FLNR"))?.nodeInfo
        assertEquals(0xbf6ee7bcL, info!!.nodeNum)
    }

    @Test
    fun `an announcement with no name in it is not a name`() {
        assertNull(MeshtasticProtocol.parseFromRadioFull(announcement(0x4370c1d8L, "", ""))?.nodeInfo)
    }

    @Test
    fun `asking who a node is sends our own name and wants an answer`() {
        val bytes = MeshtasticProtoAdapter.encodeNodeInfoRequest(0xbf6ee7bcL, 0x4370c1d8L, "MeshSat flaneur", "FLNR")
        val pkt = MeshProtos.ToRadio.parseFrom(bytes).packet
        assertEquals(0x4370c1d8L, pkt.to.toLong() and 0xFFFFFFFFL)
        assertEquals(Portnums.PortNum.NODEINFO_APP, pkt.decoded.portnum)
        assertTrue(pkt.decoded.wantResponse)
        assertEquals("MeshSat flaneur", MeshProtos.User.parseFrom(pkt.decoded.payload).longName)
    }

    @Test
    fun `one question per node every ten minutes`() {
        val limiter = MeshtasticBle.WhoIsLimiter()
        assertTrue(limiter.mayAsk(1L, 0L))
        assertFalse(limiter.mayAsk(1L, 60_000L))
        assertTrue(limiter.mayAsk(2L, 60_000L))
        assertTrue(limiter.mayAsk(1L, MeshtasticBle.WhoIsLimiter.EVERY_MS))
    }
}
