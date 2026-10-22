package net.meshsat.android

import android.content.ContextWrapper
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.bt.Iridium9704Spp
import net.meshsat.android.engine.SatelliteChain
import net.meshsat.android.engine.TransformPipeline
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The kits' satellite chain on the app's real IMT path (MESHSAT-1623): the 9704 driver over the
 * byte streams the node's pipe gives it, against a scripted modem. What the app sends is what the
 * modem gets, byte for byte, version byte once (also when the nonce starts with 0x01), across
 * segments; what the satellite brings in reaches the receive path as it was sent, and a Bridge's
 * wire opens to its text.
 */
class Iridium9704ChainTest {

    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private lateinit var modem: FakeJsprModem
    private lateinit var spp: Iridium9704Spp

    @Before
    fun up() = runBlocking {
        modem = FakeJsprModem("300000000000001", segment = 100)
        spp = Iridium9704Spp(object : ContextWrapper(null) {})
        spp.attach(modem)
        withTimeout(30_000) { spp.state.first { it == Iridium9704Spp.State.Ready } }
        assertEquals("300000000000001", spp.modemInfo.value.imei)
    }

    @After
    fun down() = spp.detach()

    private fun withoutCrc(mo: ByteArray): ByteArray {
        val body = mo.copyOfRange(0, mo.size - 2)
        val crc = Iridium9704Spp.crc16(body)
        assertEquals("the CRC the driver appended", crc, ((mo[mo.size - 2].toInt() and 0xFF) shl 8) or (mo[mo.size - 1].toInt() and 0xFF))
        return body
    }

    private fun sendAndTake(wire: ByteArray): ByteArray = runBlocking {
        assertEquals("mo_ack_received", spp.sendMessageBlocking(wire))
        withoutCrc(modem.mos.take())
    }

    @Test
    fun `a sealed Greek text leaves the app as the chain made it, version byte once`() {
        val text = "Είμαστε στο βόρειο φυλάκιο, όλοι καλά".toByteArray(Charsets.UTF_8)
        // A nonce that starts with 0x01 as well: the modem must see exactly one version byte before it.
        val pipeline = TransformPipeline().apply { fixedNonce = ByteArray(12) { if (it == 0) 0x01 else (0x10 + it).toByte() } }
        val wire = SatelliteChain.seal(text, key, pipeline)
        val got = sendAndTake(wire)
        assertArrayEquals(wire, got)
        assertEquals(0x01.toByte(), got[0]); assertEquals(0x01.toByte(), got[1])
        assertArrayEquals(text, SatelliteChain.open(got, key))
    }

    @Test
    fun `any bytes go out unchanged across segments`() {
        val all = ByteArray(1024) { it.toByte() }
        assertArrayEquals(all, sendAndTake(all))
        val sealed = SatelliteChain.seal(ByteArray(3000) { (it * 7).toByte() }, key)
        assertArrayEquals(sealed, sendAndTake(sealed))
    }

    @Test
    fun `a Bridge's sealed wire comes in whole and opens to its text`() = runBlocking {
        val dir = File(requireNotNull(javaClass.classLoader?.getResource("hchain/vectors.lock")).toURI()).parentFile
        val v = JSONObject(File(dir, "h-chain-vectors-v1.json").readText()).getJSONArray("envelopes")
        val x = (0 until v.length()).map { v.getJSONObject(it) }.first { it.getString("name") == "el_sentence" }
        val wire = x.getString("wire_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val got = async { withTimeout(30_000) { spp.receivedMessages.first() } }
        delay(200)
        modem.deliverMt(wire)
        val payload = got.await()
        assertArrayEquals(wire, payload)
        assertEquals(SatelliteChain.Mt.Text(x.getString("text"), sealed = true, chain = SatelliteChain.Chain.H), SatelliteChain.readMt(payload, x.getString("key_hex")))
    }

    /**
     * The coordinated switch and the rollback (r33 review R33-R2), through what the service does:
     * the setting picks the chain (`chainOf`), the driver carries the wire both ways, and the
     * receive side opens it on the chain the phone is set to. H, then legacy, then H again.
     */
    @Test
    fun `H, rollback to the legacy chain, and H again, out and in through the driver`() = runBlocking {
        val texts = mapOf(
            SatelliteChain.MODE_SEALED to "first on the H chain, όλα καλά",
            SatelliteChain.MODE_LEGACY to "rolled back to the old chain, όλα καλά",
        )
        for (mode in listOf(SatelliteChain.MODE_SEALED, SatelliteChain.MODE_LEGACY, SatelliteChain.MODE_SEALED)) {
            val chain = requireNotNull(SatelliteChain.chainOf(mode))
            val text = texts.getValue(mode).toByteArray(Charsets.UTF_8)
            val wire = SatelliteChain.seal(text, key, chain = chain)
            val got = sendAndTake(wire)
            assertArrayEquals("$mode out", wire, got)
            assertArrayEquals("$mode opens on its chain", text, SatelliteChain.open(got, key, chain = chain))

            val incoming = async { withTimeout(30_000) { spp.receivedMessages.first() } }
            delay(200)
            modem.deliverMt(wire, id = 20 + chain.ordinal)
            val payload = incoming.await()
            assertArrayEquals("$mode in", wire, payload)
            assertEquals(
                "$mode reads on the phone's setting",
                SatelliteChain.Mt.Text(String(text, Charsets.UTF_8), sealed = true, chain = chain),
                SatelliteChain.readMt(payload, key, preferred = chain),
            )
        }
    }
}
