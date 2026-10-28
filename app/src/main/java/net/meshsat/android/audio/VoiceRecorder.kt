package net.meshsat.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The microphone while a voice note is held (MESHSAT-1735): MediaRecorder writing a standard
 * Ogg Opus file (RFC 7845), mono, 48 kHz, a 16 kbit/s ceiling, the way Sideband records, so
 * the bytes go into FIELD_AUDIO as they are and play unchanged in Sideband and MeshChat.
 * Stopped by the finger lifting or by [maxMs], whichever comes first. Android 10 (API 29)
 * brought the Ogg writer and the Opus encoder to MediaRecorder; [available] says so. The
 * caller holds RECORD_AUDIO; a microphone that cannot be opened throws from [start].
 */
class VoiceRecorder(private val context: Context, private val maxMs: Long, private val maxBytes: Long = net.meshsat.android.lxmf.LxAttachments.MAX_BYTES.toLong()) {
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    @Volatile private var hitMax = false
    private var ticker: Thread? = null

    companion object {
        const val SAMPLE_RATE = 48_000
        const val BIT_RATE = 16_000
        fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun start() {
        if (!available()) throw IllegalStateException("voice notes need Android 10 or later")
        val f = File.createTempFile("note-", ".ogg", context.cacheDir)
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            // Whichever comes first ends the note: the seconds, or the bytes one message may carry.
            r.setMaxDuration(maxMs.toInt())
            r.setMaxFileSize(maxBytes)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) hitMax = true
            }
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release(); f.delete()
            throw IllegalStateException("the microphone could not be opened: ${e.message}")
        }
        recorder = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
        _elapsedMs.value = 0
        ticker = Thread({
            while (recorder === r && !hitMax) {
                _elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }, "voice-record-tick").apply { isDaemon = true; start() }
    }

    /** True once the recorder stopped by itself at [maxMs]. */
    val reachedMax: Boolean get() = hitMax

    /**
     * Ends the recording and returns the Ogg Opus file's bytes; empty when nothing was
     * recorded (a press shorter than the recorder's own start-up). The temporary file goes.
     */
    fun stop(): ByteArray {
        val r = recorder ?: return ByteArray(0)
        recorder = null
        ticker?.interrupt(); ticker = null
        _elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
        val bytes = try {
            r.stop()
            file?.readBytes() ?: ByteArray(0)
        } catch (_: Exception) {
            // stop() throws when no frames were written: a tap, not a note.
            ByteArray(0)
        } finally {
            r.release()
            file?.delete()
            file = null
        }
        return bytes
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        ticker?.interrupt(); ticker = null
        try { r.stop() } catch (_: Exception) {}
        r.release()
        file?.delete(); file = null
    }
}
