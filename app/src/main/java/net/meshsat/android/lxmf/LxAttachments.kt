package net.meshsat.android.lxmf

import net.meshsat.android.rns.MpArray
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.MpStr
import net.meshsat.android.rns.MpValue
import net.meshsat.android.rns.asBytes
import net.meshsat.android.rns.asLong

/**
 * A voice note or a photo riding in an LXMF message (MESHSAT-1735), exactly as Sideband and
 * MeshChat send them, so nothing MeshSat-specific is on the wire: FIELD_AUDIO (0x07) is
 * `[mode, bytes]`, FIELD_IMAGE (0x06) is `[format, bytes]`. The Bridge and the Hub read and
 * write the same shapes (`meshsat/internal/lxmf/fields.go`); the iOS port mirrors this file.
 */
class LxAttachment(
    val kind: String,
    /** Audio: the LXMF audio mode. 0 for an image. */
    val mode: Int = 0,
    /** Image: the format name, lower case ("webp"). Empty for audio. */
    val format: String = "",
    val data: ByteArray,
) {
    val isAudio: Boolean get() = kind == LxAttachments.KIND_AUDIO
    val isImage: Boolean get() = kind == LxAttachments.KIND_IMAGE

    /** Checks the shape: a known kind, a known audio mode or a plain image format name, and some bytes. */
    fun validate(): String? {
        if (data.isEmpty()) return "attachment has no bytes"
        return when (kind) {
            LxAttachments.KIND_AUDIO -> if (LxAttachments.audioModeKnown(mode)) null else "unknown audio mode 0x%02x".format(mode)
            LxAttachments.KIND_IMAGE -> when {
                format.isEmpty() || format.length > 8 -> "image format must be 1 to 8 characters"
                !format.all { it in 'a'..'z' || it in '0'..'9' } -> "image format must be lower-case letters and digits"
                else -> null
            }
            else -> "unknown attachment kind \"$kind\""
        }
    }

    /** The media type a client plays or shows the bytes as. */
    val contentType: String
        get() = when (kind) {
            LxAttachments.KIND_AUDIO -> when (mode) {
                in LxAttachments.AUDIO_CODEC2_FIRST..LxAttachments.AUDIO_CODEC2_LAST -> "audio/codec2"
                in LxAttachments.AUDIO_OPUS_FIRST..LxAttachments.AUDIO_OPUS_LAST -> "audio/ogg"
                else -> "application/octet-stream"
            }
            LxAttachments.KIND_IMAGE -> when (format) {
                "webp", "png", "gif", "bmp", "avif" -> "image/$format"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "application/octet-stream"
            }
            else -> "application/octet-stream"
        }

    /** A short line for a log or a ledger row: "audio Opus OGG 1024 B", "image webp 2048 B". */
    fun describe(): String = when (kind) {
        LxAttachments.KIND_AUDIO -> "audio ${LxAttachments.audioModeName(mode)} ${data.size} B"
        LxAttachments.KIND_IMAGE -> "image $format ${data.size} B"
        else -> "$kind ${data.size} B"
    }

    companion object {
        fun audio(mode: Int, data: ByteArray) = LxAttachment(LxAttachments.KIND_AUDIO, mode = mode, data = data)
        fun image(format: String, data: ByteArray) = LxAttachment(LxAttachments.KIND_IMAGE, format = format.lowercase(), data = data)
    }
}

object LxAttachments {
    const val KIND_AUDIO = "audio"
    const val KIND_IMAGE = "image"
    const val IMAGE_WEBP = "webp"

    // Audio modes (LXMF.py AM_*): Codec2 0x01-0x09 (700C, the satellite default, is 0x03),
    // Opus 0x10-0x19 (the bytes are an Ogg Opus stream), 0xFF a custom codec.
    const val AUDIO_CODEC2_FIRST = 0x01
    const val AUDIO_CODEC2_700C = 0x03
    const val AUDIO_CODEC2_LAST = 0x09
    const val AUDIO_OPUS_FIRST = 0x10
    /** What this phone records: an Ogg Opus stream, the mode every client plays. */
    const val AUDIO_OPUS_OGG = 0x10
    const val AUDIO_OPUS_LAST = 0x19
    const val AUDIO_CUSTOM = 0xFF

    /**
     * What one message's attachments may weigh together (the Bridge's lxmf.MaxAttachmentBytes):
     * a 30 s Opus note at 12 kbit/s is about 46 KB, a 640 px WebP a few KB.
     */
    const val MAX_BYTES = 64 * 1024

    private val codec2Names = mapOf(
        0x01 to "450PWB", 0x02 to "450", 0x03 to "700C", 0x04 to "1200", 0x05 to "1300",
        0x06 to "1400", 0x07 to "1600", 0x08 to "2400", 0x09 to "3200",
    )
    private val opusNames = mapOf(
        0x10 to "OGG", 0x11 to "LBW", 0x12 to "MBW", 0x13 to "PTT", 0x14 to "RT_HDX",
        0x15 to "RT_FDX", 0x16 to "STANDARD", 0x17 to "HQ", 0x18 to "BROADCAST", 0x19 to "LOSSLESS",
    )

    /** The mode's name as LXMF.py spells it ("Codec2 700C", "Opus OGG", "custom"), or "mode 0x.." for one it does not know. */
    fun audioModeName(mode: Int): String = codec2Names[mode]?.let { "Codec2 $it" }
        ?: opusNames[mode]?.let { "Opus $it" }
        ?: if (mode == AUDIO_CUSTOM) "custom" else "mode 0x%02x".format(mode)

    fun audioModeKnown(mode: Int): Boolean = mode in codec2Names || mode in opusNames || mode == AUDIO_CUSTOM

    fun isOpus(mode: Int): Boolean = mode in AUDIO_OPUS_FIRST..AUDIO_OPUS_LAST
    fun isCodec2(mode: Int): Boolean = mode in AUDIO_CODEC2_FIRST..AUDIO_CODEC2_LAST

    fun totalBytes(atts: List<LxAttachment>): Int = atts.sumOf { it.data.size }

    fun describe(atts: List<LxAttachment>): String = atts.joinToString(", ") { it.describe() }

    /**
     * The audio and image fields of a received message, the image first (field order), each
     * only when it has the documented shape; a field of another shape is left where it is.
     */
    fun attachmentsOf(fields: MpMap): List<LxAttachment> {
        val out = ArrayList<LxAttachment>(2)
        (fields[LxMessage.FIELD_IMAGE] as? MpArray)?.items?.takeIf { it.size == 2 }?.let { v ->
            val format = v[0].asBytes()?.let { String(it, Charsets.UTF_8) }?.lowercase() ?: ""
            val bytes = (v[1] as? MpBin)?.value
            if (format.isNotEmpty() && bytes != null && bytes.isNotEmpty()) out.add(LxAttachment.image(format, bytes))
        }
        (fields[LxMessage.FIELD_AUDIO] as? MpArray)?.items?.takeIf { it.size == 2 }?.let { v ->
            val mode = v[0].asLong()
            val bytes = (v[1] as? MpBin)?.value
            if (mode != null && bytes != null && bytes.isNotEmpty()) out.add(LxAttachment.audio(mode.toInt(), bytes))
        }
        return out
    }

    /**
     * [fields] with the attachments in, one audio and one image at most, replacing a field of
     * the same kind. Every attachment is validated and their total must fit [MAX_BYTES];
     * throws [LxmfException] and leaves nothing changed otherwise.
     */
    fun withAttachments(fields: MpMap, atts: List<LxAttachment>): MpMap {
        if (atts.isEmpty()) return fields
        if (totalBytes(atts) > MAX_BYTES) throw LxmfException("attachments exceed $MAX_BYTES bytes")
        val seen = HashSet<String>()
        for (a in atts) {
            a.validate()?.let { throw LxmfException(it) }
            if (!seen.add(a.kind)) throw LxmfException("more than one ${a.kind} attachment")
        }
        val entries = ArrayList<Pair<MpValue, MpValue>>(withoutAttachments(fields).entries)
        for (a in atts) {
            when (a.kind) {
                KIND_AUDIO -> entries.add(MpInt(LxMessage.FIELD_AUDIO) to MpArray(MpInt(a.mode.toLong()), MpBin(a.data)))
                KIND_IMAGE -> entries.add(MpInt(LxMessage.FIELD_IMAGE) to MpArray(MpStr(a.format), MpBin(a.data)))
            }
        }
        return MpMap(entries)
    }

    /** The fields without their audio and image entries. */
    fun withoutAttachments(fields: MpMap): MpMap =
        MpMap(fields.entries.filter { (k, _) -> !(k is MpInt && (k.value == LxMessage.FIELD_AUDIO || k.value == LxMessage.FIELD_IMAGE)) })

    /** What a message with no text shows as its preview: "[audio Opus OGG 1024 B]". */
    fun preview(text: String, atts: List<LxAttachment>): String =
        if (text.isNotBlank() || atts.isEmpty()) text else "[${describe(atts)}]"
}
