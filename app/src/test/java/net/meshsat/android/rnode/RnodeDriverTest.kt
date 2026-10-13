package net.meshsat.android.rnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The RNode driver against the software RNode (the Bridge's driver_test.go). [MESHSAT-1553 P4] */
class RnodeDriverTest {
    private val eu = RnodeProtocol.preset("eu-868")!!.params
    private fun opts(name: String, link: FakeRnode, onPacket: (ByteArray) -> Unit = {}, onError: (String) -> Unit = {}, flow: Boolean = true) =
        RnodeDriver.Options(name = name, params = eu, flowControl = flow, onPacket = onPacket, onError = onError, log = { System.err.println("[$name] $it") }, sleep = { Thread.sleep(minOf(it, 50)) })

    private fun pair(): Pair<FakeRnode, FakeRnode> {
        val a = FakeRnode("A"); val b = FakeRnode("B")
        a.peer = b; b.peer = a
        return a to b
    }

    @Test
    fun kissRoundTrip() {
        val payload = byteArrayOf(1, Kiss.FEND.toByte(), 2, Kiss.FESC.toByte(), 3)
        val frame = Kiss.encode(RnodeProtocol.CMD_DATA, payload)
        assertEquals(Kiss.FEND, frame[0].toInt() and 0xFF)
        assertEquals(Kiss.FEND, frame[frame.size - 1].toInt() and 0xFF)
        assertEquals(payload.size + 2 + 3, frame.size)
        val frames = Kiss.Splitter().feed(frame + frame, 0)
        assertEquals(2, frames.size)
        assertTrue(payload.contentEquals(frames[0].payload)); assertEquals(0, frames[0].cmd)
        // A split across reads
        val s = Kiss.Splitter()
        assertTrue(s.feed(frame.copyOfRange(0, 3), 0).isEmpty())
        assertEquals(1, s.feed(frame.copyOfRange(3, frame.size), 1).size)
        // A stale partial frame is dropped on timeout
        val t = Kiss.Splitter(maxLen = 600, timeoutMs = 100)
        assertTrue(t.feed(frame.copyOfRange(0, 3), 0).isEmpty())
        assertTrue(t.feed(frame, 1000).size == 1)
    }

    @Test
    fun commandEncoders() {
        assertEquals("c0087 3c0500 0c04800c04900c0".replace(" ", ""), RnodeProtocol.detectBurst().joinToString("") { "%02x".format(it) })
        assertEquals("c0 01 33 b0 6c 00 c0".replace(" ", ""), RnodeProtocol.setFrequency(867_200_000).joinToString("") { "%02x".format(it) })
        // A payload byte 0xC0 is escaped: 125000 Hz = 0x0001e848, no escape; bandwidth 0xC0 appears in 49152
        assertEquals("c0 02 00 00 db dc 00 c0".replace(" ", ""), RnodeProtocol.setBandwidth(0xC000).joinToString("") { "%02x".format(it) })
        assertEquals("c0 0b 03 e8 c0".replace(" ", ""), RnodeProtocol.setStaLock(10.0).joinToString("") { "%02x".format(it) })
        assertEquals(RnodeProtocol.bitrate(8, 5, 125_000), RnodeProtocol.Params(867_200_000, 125_000, 14, 8, 5).bitrate(), 0.001)
        assertEquals(3125.0, RnodeProtocol.bitrate(8, 5, 125_000), 0.001)
        assertNotNull(RnodeProtocol.Params(100_000_000, 125_000, 14, 8, 5).validate())
        assertNotNull(RnodeProtocol.Params(867_200_000, 125_000, 40, 8, 5).validate())
        assertNull(eu.validate())
    }

    @Test
    fun opensConfiguresAndExchangesPackets() {
        val (a, b) = pair()
        val gotA = LinkedBlockingQueue<ByteArray>(); val gotB = LinkedBlockingQueue<ByteArray>()
        val da = RnodeDriver(a, opts("A", a, onPacket = { gotA.add(it) })); a.onBytes = { da.feed(it) }
        val db = RnodeDriver(b, opts("B", b, onPacket = { gotB.add(it) })); b.onBytes = { db.feed(it) }
        assertNull(da.open()); assertNull(db.open())
        assertTrue(da.online && db.online)
        val st = da.stats()
        assertEquals(867_200_000L, st.frequency); assertEquals(8, st.sf); assertTrue(st.radioOn); assertTrue(st.detected)
        assertEquals("1.75", st.firmware); assertEquals(RnodeProtocol.PLATFORM_ESP32, st.platform)
        assertNull(da.send("hello B".toByteArray()))
        assertEquals("hello B", String(gotB.poll(2, TimeUnit.SECONDS)!!))
        assertNull(db.send("hello A".toByteArray()))
        assertEquals("hello A", String(gotA.poll(2, TimeUnit.SECONDS)!!))
        assertEquals(1, da.stats().txPackets.toInt()); assertEquals(1, da.stats().rxPackets.toInt())
        // The configuration went out in order, then the radio was switched on.
        val cmds = synchronized(a.written) { a.written.map { it.cmd } }
        val i = cmds.indexOf(RnodeProtocol.CMD_FREQUENCY)
        assertEquals(listOf(RnodeProtocol.CMD_FREQUENCY, RnodeProtocol.CMD_BANDWIDTH, RnodeProtocol.CMD_TXPOWER, RnodeProtocol.CMD_SF, RnodeProtocol.CMD_CR, RnodeProtocol.CMD_RADIO_STATE), cmds.subList(i, i + 6))
        da.close()
        assertFalse(da.online)
        val last = synchronized(a.written) { a.written.takeLast(2).map { it.cmd } }
        assertEquals(listOf(RnodeProtocol.CMD_RADIO_STATE, RnodeProtocol.CMD_LEAVE), last)
    }

    @Test
    fun flowControlQueuesUntilReady() {
        val a = FakeRnode("A", ready = false); val b = FakeRnode("B"); a.peer = b; b.peer = a
        val gotB = LinkedBlockingQueue<ByteArray>()
        val da = RnodeDriver(a, opts("A", a)); a.onBytes = { da.feed(it) }
        val db = RnodeDriver(b, opts("B", b, onPacket = { gotB.add(it) })); b.onBytes = { db.feed(it) }
        assertNull(da.open()); assertNull(db.open())
        assertNull(da.send("one".toByteArray()))
        assertNull(da.send("two".toByteArray()))   // queued: no READY came back for "one"
        assertEquals("one", String(gotB.poll(2, TimeUnit.SECONDS)!!))
        assertNull(gotB.poll(300, TimeUnit.MILLISECONDS))
        a.emit(RnodeProtocol.CMD_READY, byteArrayOf(1))
        assertEquals("two", String(gotB.poll(2, TimeUnit.SECONDS)!!))
        assertEquals(2, da.stats().txPackets.toInt())
    }

    @Test
    fun refusesOldFirmwareAndSilence() {
        val old = FakeRnode("old", firmware = 1 to 40)
        val d1 = RnodeDriver(old, opts("old", old)); old.onBytes = { d1.feed(it) }
        assertTrue(d1.open()!!.contains("older than"))
        val mute = FakeRnode("mute", answerDetect = false)
        val d2 = RnodeDriver(mute, opts("mute", mute)); mute.onBytes = { d2.feed(it) }
        assertEquals("device not detected", d2.open())
        assertFalse(d2.online)
    }

    @Test
    fun hardwareErrorAndResetReachTheOwner() {
        val (a, b) = pair()
        val errs = LinkedBlockingQueue<String>()
        val da = RnodeDriver(a, opts("A", a, onError = { errs.add(it) })); a.onBytes = { da.feed(it) }
        val db = RnodeDriver(b, opts("B", b)); b.onBytes = { db.feed(it) }
        assertNull(da.open()); assertNull(db.open())
        a.emit(RnodeProtocol.CMD_ERROR, byteArrayOf(RnodeProtocol.ERROR_MEMORY_LOW.toByte()))   // non-fatal: logged only
        assertNull(errs.poll(300, TimeUnit.MILLISECONDS))
        a.emit(RnodeProtocol.CMD_ERROR, byteArrayOf(RnodeProtocol.ERROR_TXFAILED.toByte()))
        assertTrue(errs.poll(2, TimeUnit.SECONDS)!!.contains("transmit failure"))
        a.emit(RnodeProtocol.CMD_RESET, byteArrayOf(RnodeProtocol.RESET_MAGIC.toByte()))
        assertTrue(errs.poll(2, TimeUnit.SECONDS)!!.contains("reset"))
        assertFalse(da.online)
        assertNotNull(da.send("x".toByteArray()))
    }
}
