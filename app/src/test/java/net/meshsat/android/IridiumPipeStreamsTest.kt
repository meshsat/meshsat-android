package net.meshsat.android

import net.meshsat.android.ble.IridiumPipeContract
import net.meshsat.android.ble.IridiumPipeContract.Owner
import net.meshsat.android.ble.LineWatcher
import net.meshsat.android.ble.NodeStatsText
import net.meshsat.android.ble.PipeInputStream
import net.meshsat.android.ble.PipeOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** The byte-stream side of the node's BLE Iridium pipe (MESHSAT-1236). */
class IridiumPipeStreamsTest {

    @Test
    fun `STATUS decodes owner, and an unknown contract version reads as unknown`() {
        assertEquals(Owner.None, IridiumPipeContract.parseStatus(byteArrayOf(1, 0)))
        assertEquals(Owner.Phone, IridiumPipeContract.parseStatus(byteArrayOf(1, 1)))
        assertEquals(Owner.Node, IridiumPipeContract.parseStatus(byteArrayOf(1, 2)))
        assertEquals(Owner.Unknown, IridiumPipeContract.parseStatus(byteArrayOf(3, 1)))
        assertEquals(Owner.Unknown, IridiumPipeContract.parseStatus(byteArrayOf(1)))
        assertEquals(Owner.Unknown, IridiumPipeContract.parseStatus(null))
    }

    @Test
    fun `STATUS is read at 2 or 4 bytes, and flags and signal only when they are there`() {
        // Contract v2 (MESHSAT-1378): the firmware bumps the version only after both apps
        // accept either length.
        val v1 = IridiumPipeContract.parseStatusFull(byteArrayOf(1, 2))!!
        assertEquals(Owner.Node, v1.owner)
        assertEquals(null, v1.flags)
        assertEquals(null, v1.csq)

        val v2 = IridiumPipeContract.parseStatusFull(byteArrayOf(2, 1, 0x0B, 4))!!
        assertEquals(Owner.Phone, v2.owner)
        assertEquals(IridiumPipeContract.StatusFlags(sessionInFlight = true, messageWaiting = true, modemAnswers = false, bufferCongested = true), v2.flags)
        assertEquals(4, v2.csq)

        // A v2 node still sending 2 bytes, and 0xFF for "never read".
        assertEquals(Owner.Phone, IridiumPipeContract.parseStatusFull(byteArrayOf(2, 1))!!.owner)
        assertEquals(null, IridiumPipeContract.parseStatusFull(byteArrayOf(2, 1, 4, 0xFF.toByte()))!!.csq)
        assertEquals(Owner.Phone, IridiumPipeContract.parseStatus(byteArrayOf(2, 1, 0, 0)))
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }

    @Test
    fun `STATS decodes the 52 little-endian bytes by offset`() {
        val value = byteArrayOf(2, 2, 0x06, 3) +
            le32(12) + le32(7) + le16(32) + le16(250) + le16(2) + le16(1) + le32(240) +
            le32(8_040) + le32(3) + le32(17) + le32(5) + le32(4) + le32(1) +
            byteArrayOf(4, 10, 0, 0)
        assertEquals(52, value.size)
        val s = IridiumPipeContract.parseStats(value)!!
        assertEquals(Owner.Node, s.owner)
        assertEquals(IridiumPipeContract.StatusFlags(sessionInFlight = false, messageWaiting = true, modemAnswers = true, bufferCongested = false), s.flags)
        assertEquals(3, s.csq)
        assertEquals(12L, s.csqAgeS)
        assertEquals(7L, s.sessions)
        assertEquals(32, s.lastMoStatus)
        assertEquals(250, s.lastMomsn)
        assertEquals(2, s.lastMtStatus)
        assertEquals(1, s.lastMtQueued)
        assertEquals(240L, s.lastSessionAgeS)
        assertEquals(8_040L, s.uptimeS)
        assertEquals(3L, s.watchdogReboots)
        assertEquals(17L, s.phoneBytesDropped)
        assertEquals(5L, s.nodeSessions)
        assertEquals(4L, s.nodeSent)
        assertEquals(1L, s.nodeReceived)
        assertEquals(4, s.daySessionsUsed)
        assertEquals(10, s.daySessionsCap)

        // The node's "unknown" markers, and a longer value still parses by offset.
        val fresh = byteArrayOf(2, 0, 0, 0xFF.toByte()) + le32(0xFFFFFFFFL) + le32(0) + le16(-1 and 0xFFFF) + le16(0) + le16(-1 and 0xFFFF) + le16(0) + le32(0xFFFFFFFFL) +
            ByteArray(28) + byteArrayOf(9, 9, 9)
        val f = IridiumPipeContract.parseStats(fresh)!!
        assertEquals(null, f.csq)
        assertEquals(null, f.csqAgeS)
        assertEquals(null, f.lastMoStatus)
        assertEquals(null, f.lastMtStatus)
        assertEquals(null, f.lastSessionAgeS)
        assertEquals(null, IridiumPipeContract.parseStats(value.copyOf(51)))
        assertEquals(null, IridiumPipeContract.parseStats(byteArrayOf(1) + value.copyOfRange(1, 52)))
    }

    @Test
    fun `PASS is version, count, then 7 little-endian bytes per window, at most eight`() {
        val one = IridiumPipeContract.encodePassList(listOf(IridiumPipeContract.PassWindow(0x6789ABCDL, 0x0102, 47)))
        assertArrayEquals(byteArrayOf(1, 1, 0xCD.toByte(), 0xAB.toByte(), 0x89.toByte(), 0x67, 0x02, 0x01, 47), one)
        assertArrayEquals(byteArrayOf(1, 0), IridiumPipeContract.encodePassList(emptyList()))
        val many = IridiumPipeContract.encodePassList((1..12).map { IridiumPipeContract.PassWindow(it.toLong(), 600, 10) })
        assertEquals(2 + 7 * 8, many.size)
        assertEquals(8, many[1].toInt())
        // Out-of-range values are clamped, never wrapped.
        val clamped = IridiumPipeContract.encodePassList(listOf(IridiumPipeContract.PassWindow(-5, 70_000, 120)))
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 90), clamped)
    }

    @Test
    fun `the node health card's wording`() {
        val s = IridiumPipeContract.NodeStats(
            owner = Owner.Phone, flags = IridiumPipeContract.StatusFlags(false, false, true, false),
            csq = 3, csqAgeS = 12, sessions = 7, lastMoStatus = 32, lastMomsn = 250, lastMtStatus = 2, lastMtQueued = 0,
            lastSessionAgeS = 240, uptimeS = 8_040, watchdogReboots = 0, phoneBytesDropped = 0,
            nodeSessions = 0, nodeSent = 0, nodeReceived = 0, daySessionsUsed = 0, daySessionsCap = 10,
        )
        assertEquals(
            listOf(
                "Modem" to "Held by this phone, answers",
                "Signal" to "3 of 5, 12 s ago",
                "Sessions since boot" to "7",
                "Last session" to "MO 32, no network service, MOMSN 250, 4 min ago",
                "Node's own routing" to "0 of 10 sessions today, sent 0, received 0",
                "Node uptime" to "2 h 14 min",
            ),
            NodeStatsText.rows(s),
        )
        assertEquals(null, NodeStatsText.warning(s))
        val busy = s.copy(
            owner = Owner.Node, flags = IridiumPipeContract.StatusFlags(true, true, true, true), csq = null, csqAgeS = null,
            lastMoStatus = null, lastMtQueued = 2, watchdogReboots = 3, phoneBytesDropped = 17, daySessionsCap = 0, nodeSessions = 0,
        )
        assertEquals(
            listOf(
                "Modem" to "Used by the node, answers",
                "Session" to "In flight now",
                "Signal" to "Never read",
                "Sessions since boot" to "7",
                "Last session" to "None yet",
                "Gateway" to "2 waiting at the gateway",
                "Node uptime" to "2 h 14 min",
                "Bluetooth watchdog reboots" to "3",
                "Bytes the node could not take" to "17",
            ),
            NodeStatsText.rows(busy),
        )
        assertEquals("The node's incoming buffer is nearly full: the phone writes faster than the modem takes.", NodeStatsText.warning(busy))
    }

    @Test
    fun `input keeps binary bytes in order and counts what did not fit`() {
        val input = PipeInputStream(capacity = 4)
        assertEquals(0, input.offer(byteArrayOf(0x00, 0x0D, 0x0A)))
        assertEquals(2, input.offer(byteArrayOf(0xFF.toByte(), 0x94.toByte(), 0xC3.toByte())))
        assertEquals(4, input.available())
        val buf = ByteArray(8)
        assertEquals(4, input.read(buf, 0, 8))
        assertArrayEquals(byteArrayOf(0x00, 0x0D, 0x0A, 0xFF.toByte()), buf.copyOf(4))
        input.close()
        assertEquals(-1, input.read())
    }

    @Test
    fun `output is sent in chunks of the link size, each acknowledged`() {
        val sent = mutableListOf<ByteArray>()
        val out = PipeOutputStream(chunkSize = { 20 }, canWrite = { true }, sendChunk = { sent.add(it); true })
        val payload = ByteArray(342) { it.toByte() }
        out.write(payload)
        assertEquals(0, sent.size)
        out.flush()
        assertEquals(18, sent.size)
        assertArrayEquals(payload, sent.reduce { a, b -> a + b })
    }

    @Test
    fun `output refuses to write while the phone does not own the modem`() {
        val out = PipeOutputStream(chunkSize = { 244 }, canWrite = { false }, sendChunk = { true })
        out.write("AT+SBDIX\r".toByteArray())
        assertThrows(IOException::class.java) { out.flush() }
    }

    @Test
    fun `a failed chunk write surfaces as an IOException`() {
        val out = PipeOutputStream(chunkSize = { 244 }, canWrite = { true }, sendChunk = { false })
        out.write("AT\r".toByteArray())
        assertThrows(IOException::class.java) { out.flush() }
    }

    @Test
    fun `SBDRING is seen even when split across notifications`() {
        var rings = 0
        val watcher = LineWatcher("SBDRING") { rings++ }
        watcher.feed("\r\nSBD".toByteArray())
        watcher.feed("RI".toByteArray())
        watcher.feed("NG\r\n".toByteArray())
        assertEquals(1, rings)
        watcher.feed("OK\r\nSSBDRING\r\n".toByteArray())
        assertEquals(2, rings)
        watcher.feed("+SBDRING: no\r\nSBDRIN\r\n".toByteArray())
        assertEquals(2, rings)
    }
}
