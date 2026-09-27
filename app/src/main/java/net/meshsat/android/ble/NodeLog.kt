package net.meshsat.android.ble

import com.geeksville.mesh.MeshProtos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The node's live log over Bluetooth (MESHSAT-1374): Meshtastic streams every log line as a
 * `LogRecord` protobuf on the LogRadio characteristic while `security.debug_log_api_enabled`
 * is set and a phone is subscribed. This file is Android-free: the wire decode, the line
 * format and the buffer have tests, and iOS copies them.
 */
data class NodeLogLine(
    /** The node's clock, Unix seconds; 0 when the node has no valid time. */
    val timeSec: Long,
    /** The LogRecord level; null when the record left it unset. */
    val level: MeshProtos.LogRecord.Level?,
    /** The thread that logged it, e.g. IridiumPipe, BleWatchdog; empty when unknown. */
    val source: String,
    val message: String,
    /** When the phone received it, Unix milliseconds: the time shown when [timeSec] is 0. */
    val receivedMs: Long,
)

object NodeLog {
    /** Meshtastic's LogRadio characteristic (notify + read), on the Meshtastic service. */
    const val LOG_RADIO_UUID = "5a3d6e49-06e6-4423-9944-e9de8cdf9547"

    /** How many lines the buffer keeps; a burst on the node is longer than a screen anyway. */
    const val CAPACITY = 2000

    /** Decode one notified value; null when it is not a LogRecord. */
    fun parse(bytes: ByteArray, receivedMs: Long): NodeLogLine? {
        val record = try {
            MeshProtos.LogRecord.parseFrom(bytes)
        } catch (e: Exception) {
            return null
        }
        return NodeLogLine(
            timeSec = record.time.toLong() and 0xFFFFFFFFL,
            level = record.level.takeIf { it != MeshProtos.LogRecord.Level.UNSET && it != MeshProtos.LogRecord.Level.UNRECOGNIZED },
            source = record.source,
            message = record.message.trimEnd('\r', '\n'),
            receivedMs = receivedMs,
        )
    }

    /** DEBUG, INFO, WARN, ERROR, CRIT, TRACE; null when unset. */
    fun levelName(level: MeshProtos.LogRecord.Level?): String? = when (level) {
        MeshProtos.LogRecord.Level.TRACE -> "TRACE"
        MeshProtos.LogRecord.Level.DEBUG -> "DEBUG"
        MeshProtos.LogRecord.Level.INFO -> "INFO"
        MeshProtos.LogRecord.Level.WARNING -> "WARN"
        MeshProtos.LogRecord.Level.ERROR -> "ERROR"
        MeshProtos.LogRecord.Level.CRITICAL -> "CRIT"
        else -> null
    }

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** "HH:mm:ss LEVEL [source] message": the node's time when it has one, else the phone's. */
    fun format(line: NodeLogLine, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = if (line.timeSec > 0) Instant.ofEpochSecond(line.timeSec) else Instant.ofEpochMilli(line.receivedMs)
        return buildString {
            append(clock.format(at.atZone(zone)))
            levelName(line.level)?.let { append(' ').append(it) }
            if (line.source.isNotBlank()) append(" [").append(line.source).append(']')
            append(' ').append(line.message)
        }
    }
}

/**
 * The lines kept for the screen, newest last, at most [capacity]. While paused, new lines are
 * held and appended on resume, so nothing that arrived during a look is lost.
 */
class NodeLogBuffer(private val capacity: Int = NodeLog.CAPACITY) {
    private val lock = Any()
    private val kept = ArrayDeque<NodeLogLine>()
    private val held = ArrayDeque<NodeLogLine>()

    private val _lines = MutableStateFlow<List<NodeLogLine>>(emptyList())
    val lines: StateFlow<List<NodeLogLine>> = _lines

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    fun add(line: NodeLogLine) = synchronized(lock) {
        if (_paused.value) {
            held.addLast(line)
            while (held.size > capacity) held.removeFirst()
            return
        }
        append(line)
        publish()
    }

    fun pause() = synchronized(lock) { _paused.value = true }

    fun resume() = synchronized(lock) {
        _paused.value = false
        while (held.isNotEmpty()) append(held.removeFirst())
        publish()
    }

    fun clear() = synchronized(lock) {
        kept.clear()
        held.clear()
        publish()
    }

    /** Every kept line, formatted, one per row: what Share and Copy hand over. */
    fun text(zone: ZoneId = ZoneId.systemDefault()): String = synchronized(lock) {
        kept.joinToString("\n") { NodeLog.format(it, zone) }
    }

    private fun append(line: NodeLogLine) {
        kept.addLast(line)
        while (kept.size > capacity) kept.removeFirst()
    }

    private fun publish() {
        _lines.value = kept.toList()
    }
}
