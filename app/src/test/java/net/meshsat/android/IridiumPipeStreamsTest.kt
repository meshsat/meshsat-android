package net.meshsat.android

import net.meshsat.android.ble.IridiumPipeContract
import net.meshsat.android.ble.IridiumPipeContract.Owner
import net.meshsat.android.ble.LineWatcher
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
        assertEquals(Owner.Unknown, IridiumPipeContract.parseStatus(byteArrayOf(2, 1)))
        assertEquals(Owner.Unknown, IridiumPipeContract.parseStatus(byteArrayOf(1)))
        assertEquals(Owner.Unknown, IridiumPipeContract.parseStatus(null))
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
