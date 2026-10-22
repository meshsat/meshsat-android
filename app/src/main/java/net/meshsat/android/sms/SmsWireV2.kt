package net.meshsat.android.sms

import net.meshsat.android.codec.Smaz2
import net.meshsat.android.codec.Smaz2Bytes
import net.meshsat.android.codec.Smaz2Dict
import net.meshsat.android.codec.Smaz2Exception
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The MeshSat app SMS wire, version 2 (r40 review R40-R1; the Bridge's codec/smswire.go, the Hub's,
 * iOS's SmsWire). Version 1 put protocol byte 0x01 in front of a compressed or encrypted SMS and
 * left the receiver to guess the codec, which no rule can do: the SMAZ2 of "sara" is the two bytes
 * of "¡". Version 2 says which:
 *
 *     SMS text = base64( 0x02 | mode | payload )
 *     mode 0x00, clear:    payload = codec | body
 *     mode 0x01, AES-GCM:  payload = nonce(12) | AES-256-GCM(codec | body) | tag(16)
 *     codec 0x00 UTF-8 text, 0x01 SMAZ2 with the default table, 0x02 MSVQ-SC
 *
 * With a key the codec is inside the ciphertext, so the tag authenticates how the body is read.
 * A plain SMS (no key, no compression) stays the typed text. A frame whose mode or codec this app
 * does not know is shown as it came, never guessed at. Version 1 and unversioned SMS are still
 * read by the old rule (SmsReceiver), whose ambiguity stays. Shared vectors: the Bridge's
 * internal/engine/testdata/sms-wire-v2-vectors.json (test resources smswire/, under a lock).
 */
object SmsWireV2 {
    const val VERSION: Byte = 0x02
    const val MODE_CLEAR: Byte = 0x00
    const val MODE_AESGCM: Byte = 0x01
    const val CODEC_TEXT: Byte = 0x00
    const val CODEC_SMAZ2: Byte = 0x01
    const val CODEC_MSVQSC: Byte = 0x02

    /**
     * The frame of [text], in SmsSender's order: SMAZ2 when [smaz2] is on and it is shorter, else
     * the MSVQ-SC wire [msvqsc] makes (null when it cannot encode), else the text.
     */
    fun frame(text: String, smaz2: Boolean, msvqsc: (() -> ByteArray?)? = null): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        if (smaz2) {
            val z = Smaz2.compress(text)
            if (z.isNotEmpty() && z.size < utf8.size) return byteArrayOf(CODEC_SMAZ2) + z
        }
        msvqsc?.invoke()?.let { return byteArrayOf(CODEC_MSVQSC) + it }
        return byteArrayOf(CODEC_TEXT) + utf8
    }

    /** The wire bytes of [frame]: clear, or sealed by [seal] (AES-256-GCM) when there is one. */
    fun wire(frame: ByteArray, seal: ((ByteArray) -> ByteArray)?): ByteArray =
        if (seal == null) byteArrayOf(VERSION, MODE_CLEAR) + frame else byteArrayOf(VERSION, MODE_AESGCM) + seal(frame)

    /** What a v2 SMS reads as. */
    sealed class Read {
        /** Not v2: the version 1 rule reads it. */
        object NotV2 : Read()

        /** v2 this app cannot read (an unknown mode or codec, no key that opens it): shown as it came. */
        data class Kept(val encrypted: Boolean) : Read()

        data class Text(val text: String, val encrypted: Boolean, val codec: Byte) : Read()
    }

    /**
     * Reads decoded SMS bytes. [open] tries the sender's keys on a sealed payload (null when none
     * opens it); [msvqsc] decodes an MSVQ-SC body (null without a codebook).
     */
    fun read(raw: ByteArray, open: (ByteArray) -> ByteArray?, msvqsc: ((ByteArray) -> String?)? = null): Read {
        if (raw.size < 3 || raw[0] != VERSION) return Read.NotV2
        val payload = raw.copyOfRange(2, raw.size)
        val encrypted: Boolean
        val frame: ByteArray = when (raw[1]) {
            MODE_CLEAR -> payload.also { encrypted = false }
            MODE_AESGCM -> (open(payload) ?: return Read.Kept(false)).also { encrypted = true }
            else -> return Read.Kept(false)
        }
        if (frame.isEmpty()) return Read.Kept(encrypted)
        val body = frame.copyOfRange(1, frame.size)
        val text = when (frame[0]) {
            CODEC_TEXT -> strictUtf8(body)
            CODEC_SMAZ2 -> try {
                strictUtf8(Smaz2Bytes.decompress(body, Smaz2Dict.DEFAULT))
            } catch (_: Smaz2Exception) {
                null
            } catch (_: RuntimeException) {
                null
            }
            CODEC_MSVQSC -> msvqsc?.invoke(body)
            else -> null
        } ?: return Read.Kept(encrypted)
        return Read.Text(text, encrypted, frame[0])
    }

    /** UTF-8 that decodes without a repair, else null. */
    internal fun strictUtf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}
