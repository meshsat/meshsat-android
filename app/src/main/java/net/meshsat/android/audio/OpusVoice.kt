package net.meshsat.android.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus through Android's own MediaCodec, no native library in the app (MESHSAT-1735). The
 * microphone path is [VoiceRecorder] (MediaRecorder writes the standard Ogg Opus file) and
 * playback is [VoiceNotePlayer] (MediaPlayer); this object encodes PCM a program made, as
 * the tests do, and decodes any Ogg Opus stream to PCM for checking. The parameters match
 * Sideband's: mono, 48 kHz, a 16 kbit/s ceiling, so 30 s weighs about 60 KB and fits the
 * 64 KiB an LXMF message may carry; the packets travel in an Ogg Opus stream ([OggOpus]) as
 * audio mode 0x10. The software encoder exists from Android 10 (API 29); decoding works on
 * every supported Android.
 */
object OpusVoice {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val BIT_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2 * CHANNELS   // 1920
    /** libopus' lookahead at 48 kHz, when the encoder's header does not say. */
    private const val DEFAULT_PRE_SKIP = 312
    private const val SEEK_PRE_ROLL_NS = 80_000_000L
    private const val CODEC_TIMEOUT_US = 20_000L
    private const val WALL_LIMIT_MS = 60_000L
    private val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

    class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray) {
        val frames: Int get() = data.size / (2 * channels)
        val durationMs: Long get() = frames * 1000L / sampleRate
    }

    private fun encodeFormat(): MediaFormat = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
    }

    /** The lookup format: only what the codec list judges a codec by. */
    private fun lookupFormat(): MediaFormat = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
    }

    private fun findEncoder(): String? = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(lookupFormat())

    /** Whether this Android can encode Opus (API 29 and later have the software encoder). */
    fun encoderAvailable(): Boolean = try { findEncoder() != null } catch (_: Exception) { false }

    fun decoderAvailable(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(MediaFormat.createAudioFormat(MIME, OggOpus.RATE_48K, 1)) != null
    } catch (_: Exception) { false }

    /**
     * Encodes 48 kHz mono PCM16 (little endian) into an Ogg Opus stream. Throws when no
     * encoder exists or the codec stalls.
     */
    fun encode(pcm16: ByteArray): ByteArray {
        val name = findEncoder() ?: throw OpusException("no Opus encoder on this Android")
        val codec = MediaCodec.createByCodecName(name)
        val packets = ArrayList<ByteArray>()
        var head: OggOpus.Head? = null
        try {
            codec.configure(encodeFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inPos = 0
            var inputDone = false
            var outputDone = false
            val started = SystemClock.elapsedRealtime()
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() - started > WALL_LIMIT_MS) throw OpusException("encoder stalled")
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx) ?: throw OpusException("no input buffer")
                        val ptsUs = (inPos / 2L) * 1_000_000L / SAMPLE_RATE
                        if (inPos >= pcm16.size) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = minOf(FRAME_BYTES, pcm16.size - inPos, buf.capacity())
                            buf.clear()
                            buf.put(pcm16, inPos, n)
                            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                            inPos += n
                        }
                    }
                }
                val oidx = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (oidx >= 0) {
                    val ob = codec.getOutputBuffer(oidx) ?: throw OpusException("no output buffer")
                    val bytes = ByteArray(info.size)
                    ob.position(info.offset); ob.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        head = headFromCodecConfig(bytes) ?: head
                    } else if (info.size > 0) {
                        packets.add(bytes)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(oidx, false)
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.getByteBuffer("csd-0")?.let { b ->
                        val bytes = ByteArray(b.remaining()); b.get(bytes)
                        head = headFromCodecConfig(bytes) ?: head
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        if (packets.isEmpty()) throw OpusException("encoder produced no packets")
        val h = OggOpus.Head(CHANNELS, head?.preSkip ?: DEFAULT_PRE_SKIP, SAMPLE_RATE)
        return OggOpus.write(h, packets)
    }

    /**
     * The OpusHead inside an encoder's codec-config buffer: Android wraps it in its own
     * "AOPUSHDR" record with the delay and pre-roll after it; a bare header is taken as is.
     */
    internal fun headFromCodecConfig(bytes: ByteArray): OggOpus.Head? {
        val marker = "OpusHead".toByteArray(Charsets.US_ASCII)
        var at = -1
        outer@ for (i in 0..bytes.size - 19) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            at = i; break
        }
        if (at < 0) return null
        return try { OggOpus.Head.parse(bytes.copyOfRange(at, at + 19)) } catch (_: Exception) { null }
    }

    /** Decodes an Ogg Opus stream to PCM16 at the decoder's output rate. */
    fun decode(ogg: ByteArray): Pcm {
        val stream = OggOpus.read(ogg)
        val head = stream.head
        if (head.channels !in 1..2) throw OpusException("${head.channels} channels")
        val format = MediaFormat.createAudioFormat(MIME, OggOpus.RATE_48K, head.channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(head.encode()))
            setByteBuffer("csd-1", le64(head.preSkip * 1_000_000_000L / OggOpus.RATE_48K))
            setByteBuffer("csd-2", le64(SEEK_PRE_ROLL_NS))
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(MediaFormat.createAudioFormat(MIME, OggOpus.RATE_48K, head.channels))
            ?: throw OpusException("no Opus decoder on this Android")
        val codec = MediaCodec.createByCodecName(name)
        val out = ByteArrayOutputStream()
        var rate = OggOpus.RATE_48K
        var channels = head.channels
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var next = 0
            var pts48 = 0L
            var inputDone = false
            var outputDone = false
            val started = SystemClock.elapsedRealtime()
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() - started > WALL_LIMIT_MS) throw OpusException("decoder stalled")
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx) ?: throw OpusException("no input buffer")
                        val ptsUs = pts48 * 1_000_000L / OggOpus.RATE_48K
                        if (next >= stream.packets.size) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val p = stream.packets[next++]
                            buf.clear(); buf.put(p)
                            codec.queueInputBuffer(idx, 0, p.size, ptsUs, 0)
                            pts48 += OggOpus.packetSamples48k(p)
                        }
                    }
                }
                val oidx = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (oidx >= 0) {
                    val ob = codec.getOutputBuffer(oidx) ?: throw OpusException("no output buffer")
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        ob.position(info.offset); ob.get(bytes)
                        out.write(bytes)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(oidx, false)
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        return Pcm(rate, channels, out.toByteArray())
    }

    /** How long an Ogg Opus note plays, from its packets; 0 when it cannot be read. */
    fun durationMs(ogg: ByteArray): Long = try { OggOpus.read(ogg).durationMs } catch (_: Exception) { 0L }

    private fun le64(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).also { it.flip() }

    /** A test tone: [ms] of a sine at [hz], 48 kHz mono PCM16, for the codec tests. */
    fun tone(hz: Double, ms: Int, amplitude: Double = 0.4): ByteArray {
        val n = SAMPLE_RATE * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (Math.sin(2 * Math.PI * hz * i / SAMPLE_RATE) * amplitude * Short.MAX_VALUE).toInt()
            out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** The root mean square of PCM16 samples, 0..1. */
    fun rms(pcm16: ByteArray): Double {
        val n = pcm16.size / 2
        if (n == 0) return 0.0
        var acc = 0.0
        for (i in 0 until n) {
            val s = ((pcm16[2 * i].toInt() and 0xFF) or (pcm16[2 * i + 1].toInt() shl 8)).toShort().toDouble() / Short.MAX_VALUE
            acc += s * s
        }
        return Math.sqrt(acc / n)
    }
}

class OpusException(message: String) : Exception(message)
