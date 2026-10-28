package net.meshsat.android.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The platform's speech recogniser listening beside the voice recorder (MESHSAT-1735), so a
 * note can go as text where the bytes cannot (SMS, satellite, the mesh). What it heard is a
 * proposal the sender confirms; a phone without a recogniser, or one that refuses the shared
 * microphone, gives nothing and the text stays as typed. Main thread only, as Android requires.
 */
class SpeechTranscriber(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var partial: String = ""
    private var onDone: ((String?) -> Unit)? = null
    private var finished = false

    companion object {
        private const val TAG = "SpeechTranscriber"
        fun available(context: Context): Boolean = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Exception) { false }
    }

    /** Starts listening; [onResult] gets the transcript, or null, once (after [stop] or an error). */
    fun start(onResult: (String?) -> Unit) {
        if (!available(context)) { onResult(null); return }
        onDone = onResult
        finished = false
        partial = ""
        val r = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (e: Exception) { Log.w(TAG, "no recogniser: ${e.message}"); onResult(null); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.d(TAG, "recogniser error $error")
                finish(partial.ifBlank { null })
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                finish(list?.firstOrNull()?.takeIf { it.isNotBlank() } ?: partial.ifBlank { null })
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try { r.startListening(intent) } catch (e: Exception) { Log.w(TAG, "startListening: ${e.message}"); finish(null) }
    }

    /** The finger lifted: ask for the final result. The callback follows from the recogniser. */
    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) { finish(partial.ifBlank { null }) }
    }

    fun cancel() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        release()
        onDone = null
    }

    private fun finish(text: String?) {
        if (finished) return
        finished = true
        val cb = onDone
        onDone = null
        release()
        cb?.invoke(text)
    }

    private fun release() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
}
