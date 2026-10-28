package net.meshsat.android.ui.components

import android.Manifest
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.meshsat.android.audio.OggOpus
import net.meshsat.android.audio.SpeechTranscriber
import net.meshsat.android.audio.VoiceNotePlayer
import net.meshsat.android.audio.VoiceRecorder
import net.meshsat.android.lxmf.AttachmentPolicy
import net.meshsat.android.lxmf.LxAttachment
import net.meshsat.android.lxmf.LxAttachments
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary

/**
 * The microphone on the chat composer (MESHSAT-1735): a note records while the button is held
 * and ends when the finger lifts or at [AttachmentPolicy.MAX_NOTE_MS]. The platform's speech
 * recogniser listens beside it when the phone has one, and what it heard comes back through
 * [onTranscript] for the sender to confirm. With TalkBack, where holding is awkward, the click
 * action starts the note and a second click ends it.
 */
@Composable
fun RecordNoteButton(
    onNote: (ogg: ByteArray, ms: Long) -> Unit,
    onTranscript: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val ask = rememberPermissionAsk(arrayOf(Manifest.permission.RECORD_AUDIO))
    val note by rememberUpdatedState(onNote)
    val transcript by rememberUpdatedState(onTranscript)
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var transcriber by remember { mutableStateOf<SpeechTranscriber?>(null) }
    val elapsed = recorder?.elapsedMs.collectOrNull()?.value ?: 0L
    val holding = recorder != null
    val supported = VoiceRecorder.available()

    fun begin() {
        if (!enabled) return
        if (!supported) { Toast.makeText(context, "Voice notes need Android 10 or later.", Toast.LENGTH_LONG).show(); return }
        if (!ask.granted) { ask.ask(); return }
        if (recorder != null) return
        val r = VoiceRecorder(context, AttachmentPolicy.MAX_NOTE_MS)
        try { r.start() } catch (e: Exception) { Toast.makeText(context, e.message ?: "The microphone could not be opened.", Toast.LENGTH_LONG).show(); return }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        recorder = r
        if (SpeechTranscriber.available(context)) {
            val t = SpeechTranscriber(context)
            transcriber = t
            t.start { heard -> if (!heard.isNullOrBlank()) transcript(heard) }
        }
    }

    fun end() {
        val r = recorder ?: return
        recorder = null
        val ogg = r.stop()
        val ms = r.elapsedMs.value
        transcriber?.stop(); transcriber = null
        if (ogg.isEmpty() || !OggOpus.looksLikeOggOpus(ogg)) {
            Toast.makeText(context, "Hold the microphone while you speak.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ogg.size > LxAttachments.MAX_BYTES) {
            Toast.makeText(context, "Too long for one message (${AttachmentPolicy.size(ogg.size)}); keep it shorter.", Toast.LENGTH_LONG).show()
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        note(ogg, ms)
    }

    DisposableEffect(Unit) { onDispose { recorder?.cancel(); recorder = null; transcriber?.cancel(); transcriber = null } }

    val label = if (holding) "Recording, ${AttachmentPolicy.seconds(elapsed)}" else "Hold to record a voice note"
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (holding) MeshSatRed.copy(alpha = 0.25f) else MeshSatSurfaceLight)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = if (holding) "Stop recording" else "Start recording") { if (holding) end() else begin(); true }
            }
            .pointerInput(enabled, supported) {
                detectTapGestures(onPress = {
                    begin()
                    tryAwaitRelease()
                    end()
                })
            },
    ) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = if (holding) MeshSatRed else if (enabled && supported) MeshSatTeal else MeshSatTextMuted)
    }
}

/** The note and the photo waiting in the composer, each with its size and a way to drop it. */
@Composable
fun PendingAttachments(
    audio: ByteArray?,
    audioMs: Long,
    image: ByteArray?,
    onRemoveAudio: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    if (audio == null && image == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        if (audio != null) {
            PendingRow("Voice note, ${AttachmentPolicy.seconds(audioMs)}, ${AttachmentPolicy.size(audio.size)}", onRemoveAudio) {
                val context = LocalContext.current
                val playing by VoiceNotePlayer.playing.collectAsState()
                IconButton(onClick = { if (playing == PREVIEW_ID) VoiceNotePlayer.stop() else VoiceNotePlayer.play(context, PREVIEW_ID, audio) }, modifier = Modifier.size(32.dp)) {
                    Icon(if (playing == PREVIEW_ID) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = if (playing == PREVIEW_ID) "Stop" else "Play the note", tint = MeshSatTeal)
                }
            }
        }
        if (image != null) {
            PendingRow("Photo, ${AttachmentPolicy.size(image.size)}", onRemoveImage) {
                val bm = remember(image) { BitmapFactory.decodeByteArray(image, 0, image.size) }
                if (bm != null) Image(bm.asImageBitmap(), contentDescription = "The photo to send", modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
            }
        }
    }
}

/** The composer's own preview plays under this id; rows in the chat use their attachment ids. */
private const val PREVIEW_ID = -1L

@Composable
private fun PendingRow(text: String, onRemove: () -> Unit, lead: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().background(MeshSatSurfaceLight, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        lead()
        Text(text, style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove", tint = MeshSatTextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** What the recogniser heard, for the sender to take as the message text or drop. */
@Composable
fun TranscriptProposal(text: String, onUse: () -> Unit, onDiscard: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).background(MeshSatSurfaceLight, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("Heard: “$text”", style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onUse) { Text("Use as text", color = MeshSatTeal) }
            TextButton(onClick = onDiscard) { Text("Discard", color = MeshSatTextMuted) }
        }
    }
}

/**
 * A voice note in a chat bubble: play and stop for an Opus note; a Codec2 note is named and
 * kept, this phone has no decoder for it yet.
 */
@Composable
fun VoiceNoteCard(id: Long, a: LxAttachment) {
    val context = LocalContext.current
    val playing by VoiceNotePlayer.playing.collectAsState()
    val error by VoiceNotePlayer.error.collectAsState()
    val opus = LxAttachments.isOpus(a.mode)
    val durationMs = remember(id) { if (opus) try { OggOpus.read(a.data).durationMs } catch (_: Exception) { 0L } else 0L }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(MeshSatSurfaceLight, RoundedCornerShape(8.dp)).border(0.5f.dp, MeshSatBorder, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (opus) {
            val mine = playing == id
            IconButton(onClick = { if (mine) VoiceNotePlayer.stop() else VoiceNotePlayer.play(context, id, a.data) }, modifier = Modifier.size(36.dp)) {
                Icon(if (mine) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = if (mine) "Stop" else "Play the voice note", tint = MeshSatTeal)
            }
        } else {
            Icon(Icons.Default.Mic, contentDescription = null, tint = MeshSatTextMuted, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (opus) "Voice note, ${AttachmentPolicy.seconds(durationMs)}, ${AttachmentPolicy.size(a.data.size)}"
                else "Voice note (${LxAttachments.audioModeName(a.mode)}), ${AttachmentPolicy.size(a.data.size)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!opus) Text("Playback of ${LxAttachments.audioModeName(a.mode)} is not supported on this phone.", style = MaterialTheme.typography.labelSmall, color = MeshSatTextMuted)
            else if (playing == id && error.isNotEmpty()) Text(error, style = MaterialTheme.typography.labelSmall, color = MeshSatRed)
        }
    }
}

/** A photo in a chat bubble, shown as it came. */
@Composable
fun PhotoCard(a: LxAttachment) {
    val bm = remember(a) { try { BitmapFactory.decodeByteArray(a.data, 0, a.data.size) } catch (_: Exception) { null } }
    if (bm == null) {
        Text("Photo (${a.format}, ${AttachmentPolicy.size(a.data.size)}) could not be shown.", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted, modifier = Modifier.padding(top = 6.dp))
        return
    }
    Image(
        bm.asImageBitmap(),
        contentDescription = "Photo, ${AttachmentPolicy.size(a.data.size)}",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 6.dp).clip(RoundedCornerShape(8.dp)),
    )
}
