package net.meshsat.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Plays one voice note at a time (MESHSAT-1735): the Ogg Opus bytes go to a temporary file
 * and MediaPlayer plays it, which every Android since 5.0 does for Ogg Opus, Sideband's
 * 60 ms packets included. Starting another note stops the one playing. [playing] names the
 * row (an attachment id) that sounds now, or 0.
 */
object VoiceNotePlayer {
    private const val TAG = "VoiceNotePlayer"
    private val _playing = MutableStateFlow(0L)
    val playing: StateFlow<Long> = _playing
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    private var player: MediaPlayer? = null
    private var file: File? = null

    /** Plays [ogg] as note [id]; a failure lands in [error]. Main thread. */
    @Synchronized
    fun play(context: Context, id: Long, ogg: ByteArray) {
        stop()
        _error.value = ""
        val f = try { File.createTempFile("play-", ".ogg", context.cacheDir).also { it.writeBytes(ogg) } } catch (e: Exception) { _error.value = e.message ?: "cannot write"; return }
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { finish(p, 0) }
            p.setOnErrorListener { _, what, extra -> Log.w(TAG, "voice note $id: error $what/$extra"); finish(p, 0, "cannot play this note ($what)"); true }
            p.setOnPreparedListener { it.start() }
            player = p
            file = f
            _playing.value = id
            p.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "voice note $id: ${e.message}")
            finish(p, 0, e.message ?: "cannot play this note")
        }
    }

    @Synchronized
    fun stop() {
        player?.let { p -> try { p.stop() } catch (_: Exception) {}; p.release() }
        player = null
        file?.delete(); file = null
        _playing.value = 0
    }

    @Synchronized
    private fun finish(p: MediaPlayer, id: Long, error: String = "") {
        if (player !== p) return
        try { p.release() } catch (_: Exception) {}
        player = null
        file?.delete(); file = null
        _playing.value = id
        if (error.isNotEmpty()) _error.value = error
    }
}
