package net.meshsat.android

import com.geeksville.mesh.MeshProtos
import net.meshsat.android.ble.NodeLog
import net.meshsat.android.ble.NodeLogBuffer
import net.meshsat.android.ble.NodeLogLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The node's log over LogRadio (MESHSAT-1374): decode, line format, buffer. */
class NodeLogTest {
    private fun record(level: MeshProtos.LogRecord.Level, source: String, message: String, time: Int = 0): ByteArray =
        MeshProtos.LogRecord.newBuilder().setLevel(level).setSource(source).setMessage(message).setTime(time).build().toByteArray()

    @Test
    fun `a LogRecord decodes to a line, the node's time when it has one`() {
        val line = NodeLog.parse(record(MeshProtos.LogRecord.Level.INFO, "IridiumPipe", "session 3 started (phone)\n", 1_790_000_000), receivedMs = 5_000)!!
        assertEquals(1_790_000_000L, line.timeSec)
        assertEquals(MeshProtos.LogRecord.Level.INFO, line.level)
        assertEquals("IridiumPipe", line.source)
        assertEquals("session 3 started (phone)", line.message)
        assertNull(NodeLog.parse(byteArrayOf(0x0F, 0xFF.toByte(), 0x80.toByte()), 0))
    }

    @Test
    fun `the line is time, level, source in brackets, message`() {
        val utc = ZoneOffset.UTC
        val line = NodeLog.parse(record(MeshProtos.LogRecord.Level.WARNING, "BleWatchdog", "no traffic for 60 s", 1_790_000_000), receivedMs = 0)!!
        assertEquals("14:13:20 WARN [BleWatchdog] no traffic for 60 s", NodeLog.format(line, utc))
        // No valid time on the node: the phone's receive time; no source, no level: nothing in their place.
        val bare = NodeLog.parse(record(MeshProtos.LogRecord.Level.UNSET, "", "boot"), receivedMs = 3_600_000)!!
        assertEquals("01:00:00 boot", NodeLog.format(bare, utc))
        assertEquals("CRIT", NodeLog.levelName(MeshProtos.LogRecord.Level.CRITICAL))
        assertEquals("DEBUG", NodeLog.levelName(MeshProtos.LogRecord.Level.DEBUG))
        assertEquals("TRACE", NodeLog.levelName(MeshProtos.LogRecord.Level.TRACE))
        assertEquals("ERROR", NodeLog.levelName(MeshProtos.LogRecord.Level.ERROR))
        assertNull(NodeLog.levelName(null))
    }

    private fun line(n: Int) = NodeLogLine(0, MeshProtos.LogRecord.Level.INFO, "t", "line $n", n.toLong())

    @Test
    fun `the buffer keeps the newest lines, holds while paused, appends on resume`() {
        val buf = NodeLogBuffer(capacity = 3)
        (1..4).forEach { buf.add(line(it)) }
        assertEquals(listOf("line 2", "line 3", "line 4"), buf.lines.value.map { it.message })

        buf.pause()
        buf.add(line(5))
        assertTrue(buf.paused.value)
        assertEquals(listOf("line 2", "line 3", "line 4"), buf.lines.value.map { it.message })
        buf.resume()
        assertFalse(buf.paused.value)
        assertEquals(listOf("line 3", "line 4", "line 5"), buf.lines.value.map { it.message })

        assertEquals(3, buf.text(ZoneOffset.UTC).lines().size)
        buf.clear()
        assertTrue(buf.lines.value.isEmpty())
        assertEquals("", buf.text())
    }
}
