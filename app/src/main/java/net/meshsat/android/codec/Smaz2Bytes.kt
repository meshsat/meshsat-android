package net.meshsat.android.codec

/** The SMAZ2 word table a smaz2 or smaz2g step names (its `dict` param). */
enum class Smaz2Dict {
    DEFAULT,
    MESHTASTIC;

    companion object {
        /** The step param as the Bridge reads it: "meshtastic", anything else the default. */
        fun of(name: String?): Smaz2Dict = if (name == "meshtastic") MESHTASTIC else DEFAULT
    }
}

/** A SMAZ2 body that does not decode: the bytes are not what a sender writes. */
class Smaz2Exception(message: String) : Exception(message)

/**
 * SMAZ2 on bytes, byte for byte as the MeshSat Bridge writes and reads it
 * (meshsat `internal/compress`): the default table is github.com/lib-x/smaz2,
 * the Meshtastic table is the Bridge's own compressor. Both work on the text's
 * UTF-8 bytes; nothing is turned into characters on the way.
 *
 * [Smaz2] keeps the SMS path's existing behaviour and is left as it is.
 * [MESHSAT-1623]
 */
object Smaz2Bytes {

    /** SMAZ2 of [src] with [dict]; empty for empty input, as the Bridge's. */
    fun compress(src: ByteArray, dict: Smaz2Dict): ByteArray {
        if (src.isEmpty()) return ByteArray(0)
        return when (dict) {
            Smaz2Dict.MESHTASTIC -> compressMeshtastic(src)
            Smaz2Dict.DEFAULT -> compressDefault(src)
        }
    }

    /** The bytes [c] compressed with [dict]; throws [Smaz2Exception] on a body no sender writes. */
    fun decompress(c: ByteArray, dict: Smaz2Dict): ByteArray {
        if (c.isEmpty()) return ByteArray(0)
        return when (dict) {
            Smaz2Dict.MESHTASTIC -> decompressMeshtastic(c)
            Smaz2Dict.DEFAULT -> decompressDefault(c)
        }
    }

    // --- default table: github.com/lib-x/smaz2 v0.1.0, its quirks included ---

    private fun compressDefault(s: ByteArray): ByteArray {
        val dst = Bytes()
        var verbatimLen = 0
        var pos = 0
        while (pos < s.size) {
            // The first word of the table the text starts with, not the longest.
            var wordIdx = -1
            if (s.size - pos >= 4) {
                for (i in DEFAULT_WORDS.indices) {
                    if (startsWith(s, pos, DEFAULT_WORD_BYTES[i])) {
                        wordIdx = i
                        break
                    }
                }
            }
            if (wordIdx >= 0) {
                val len = DEFAULT_WORD_BYTES[wordIdx].size
                when {
                    // Never true (a word does not start with a space), kept as lib-x has it.
                    s[pos] == SPACE -> {
                        dst.add(8); dst.add(wordIdx)
                        pos++
                    }
                    s.size - pos > len && s[pos + len] == SPACE -> {
                        dst.add(7); dst.add(wordIdx)
                        pos++
                    }
                    else -> {
                        dst.add(6); dst.add(wordIdx)
                    }
                }
                pos += len
                verbatimLen = 0
                continue
            }
            if (s.size - pos >= 2) {
                val b = bigramIndex(DEFAULT_BIGRAMS, s[pos], s[pos + 1])
                if (b >= 0) {
                    dst.add(0x80 or b)
                    pos += 2
                    verbatimLen = 0
                    continue
                }
            }
            val ch = s[pos].toInt() and 0xFF
            // lib-x writes bytes 1-8 as they are (its decoder reads them as escapes).
            if (ch in 1..8) {
                dst.add(ch)
                pos++
                verbatimLen = 0
                continue
            }
            verbatimLen++
            if (verbatimLen == 1) {
                dst.add(verbatimLen); dst.add(ch)
            } else {
                dst.add(ch)
                dst.set(dst.size - (verbatimLen + 1), verbatimLen)
                if (verbatimLen == 5) verbatimLen = 0
            }
            pos++
        }
        return dst.toByteArray()
    }

    private fun decompressDefault(c: ByteArray): ByteArray {
        val res = Bytes()
        var i = 0
        while (i < c.size) {
            val b = c[i].toInt() and 0xFF
            when {
                b and 0x80 != 0 -> {
                    val idx = (b and 0x7F) * 2
                    res.add(DEFAULT_BIGRAMS[idx].code); res.add(DEFAULT_BIGRAMS[idx + 1].code)
                    i++
                }
                b in 1..5 -> {
                    if (i + 1 + b > c.size) throw Smaz2Exception("smaz2: verbatim length $b overflows at position $i")
                    for (j in 1..b) res.add(c[i + j].toInt() and 0xFF)
                    i += 1 + b
                }
                b in 6..8 -> {
                    if (i + 1 >= c.size) throw Smaz2Exception("smaz2: word escape at end of input")
                    if (b == 8) res.add(SPACE.toInt())
                    res.addAll(DEFAULT_WORD_BYTES[c[i + 1].toInt() and 0xFF])
                    if (b == 7) res.add(SPACE.toInt())
                    i += 2
                }
                else -> {
                    res.add(b)
                    i++
                }
            }
        }
        return res.toByteArray()
    }

    // --- Meshtastic table: meshsat internal/compress/dict_meshtastic.go ---

    private fun compressMeshtastic(s: ByteArray): ByteArray {
        val dst = Bytes()
        var verbatimStart = -1
        var pos = 0
        while (pos < s.size) {
            // Space + word (8): the longest word of 4 or more bytes after the space.
            if (s[pos] == SPACE && pos + 1 < s.size) {
                var bestLen = 0
                var bestIdx = 0
                var end = pos + 1
                while (end <= s.size && end - pos - 1 <= 40) {
                    MESHTASTIC_INDEX[latin1(s, pos + 1, end)]?.let { idx ->
                        if (end - pos - 1 > bestLen) {
                            bestLen = end - pos - 1
                            bestIdx = idx
                        }
                    }
                    end++
                }
                if (bestLen >= 4) {
                    verbatimStart = -1
                    dst.add(8); dst.add(bestIdx)
                    pos += 1 + bestLen
                    continue
                }
            }
            // Word + space (7) or word (6): the longest word of 4 or more bytes.
            run {
                var bestLen = 0
                var bestIdx = 0
                val maxLen = minOf(s.size - pos, 40)
                var end = pos + 4
                while (end <= pos + maxLen) {
                    MESHTASTIC_INDEX[latin1(s, pos, end)]?.let { idx ->
                        if (end - pos > bestLen) {
                            bestLen = end - pos
                            bestIdx = idx
                        }
                    }
                    end++
                }
                if (bestLen >= 4) {
                    verbatimStart = -1
                    if (pos + bestLen < s.size && s[pos + bestLen] == SPACE) {
                        dst.add(7); dst.add(bestIdx)
                        pos += bestLen + 1
                    } else {
                        dst.add(6); dst.add(bestIdx)
                        pos += bestLen
                    }
                    return@run
                }
                // Bigram: the first pair of the table that matches.
                if (pos + 1 < s.size) {
                    val b = bigramIndex(MESHTASTIC_BIGRAMS, s[pos], s[pos + 1])
                    if (b >= 0) {
                        verbatimStart = -1
                        dst.add(0x80 or b)
                        pos += 2
                        return@run
                    }
                }
                val ch = s[pos].toInt() and 0xFF
                if (ch == 0 || ch in 9..127) {
                    verbatimStart = -1
                    dst.add(ch)
                } else if (verbatimStart >= 0 && dst.get(verbatimStart) < 5) {
                    // Bytes 1-8 and 128-255 travel in verbatim runs of up to 5.
                    dst.set(verbatimStart, dst.get(verbatimStart) + 1)
                    dst.add(ch)
                    if (dst.get(verbatimStart) == 5) verbatimStart = -1
                } else {
                    verbatimStart = dst.size
                    dst.add(1); dst.add(ch)
                }
                pos++
            }
        }
        return dst.toByteArray()
    }

    private fun decompressMeshtastic(c: ByteArray): ByteArray {
        val res = Bytes()
        var i = 0
        while (i < c.size) {
            val b = c[i].toInt() and 0xFF
            when {
                b >= 128 -> {
                    val idx = b and 0x7F
                    if (idx * 2 + 1 >= MESHTASTIC_BIGRAMS.length) throw Smaz2Exception("smaz2: bigram index $idx out of range")
                    res.add(MESHTASTIC_BIGRAMS[idx * 2].code); res.add(MESHTASTIC_BIGRAMS[idx * 2 + 1].code)
                    i++
                }
                b in 1..5 -> {
                    if (i + 1 + b > c.size) throw Smaz2Exception("smaz2: verbatim length $b overflows at position $i")
                    for (j in 1..b) res.add(c[i + j].toInt() and 0xFF)
                    i += 1 + b
                }
                b in 6..8 -> {
                    if (i + 1 >= c.size) throw Smaz2Exception("smaz2: word escape at end of input")
                    val idx = c[i + 1].toInt() and 0xFF
                    if (b == 8) res.add(SPACE.toInt())
                    res.addAll(MESHTASTIC_WORD_BYTES[idx])
                    if (b == 7) res.add(SPACE.toInt())
                    i += 2
                }
                else -> {
                    res.add(b)
                    i++
                }
            }
        }
        return res.toByteArray()
    }

    // --- shared helpers ---

    private const val SPACE: Byte = 0x20

    private fun startsWith(s: ByteArray, pos: Int, word: ByteArray): Boolean {
        if (s.size - pos < word.size) return false
        for (k in word.indices) if (s[pos + k] != word[k]) return false
        return true
    }

    private fun bigramIndex(table: String, b0: Byte, b1: Byte): Int {
        val c0 = b0.toInt() and 0xFF
        val c1 = b1.toInt() and 0xFF
        var i = 0
        while (i < table.length) {
            if (table[i].code == c0 && table[i + 1].code == c1) return i / 2
            i += 2
        }
        return -1
    }

    /** Bytes as a Latin-1 string: one char per byte, so a map lookup compares bytes. */
    private fun latin1(s: ByteArray, from: Int, to: Int): String = String(s, from, to - from, Charsets.ISO_8859_1)

    /** A growable byte buffer with in-place writes (the verbatim length byte). */
    private class Bytes {
        private var buf = ByteArray(64)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v.toByte()
        }

        fun addAll(b: ByteArray) = b.forEach { add(it.toInt() and 0xFF) }
        fun get(i: Int): Int = buf[i].toInt() and 0xFF
        fun set(i: Int, v: Int) { buf[i] = v.toByte() }
        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    private const val DEFAULT_BIGRAMS =
        "intherreheanonesorteattistenntartondalitseediseangoulecomeneriroderaioicliofasetvetasihamaecomceelllcaurlachhidihofonsotacnarssoprrtsassusnoiltsemctgeloeebetrnipeiepancpooldaadviunamutwimoshyoaiewowosfiepttmiopiaweagsuiddoooirspplscaywaigeirylytuulivimabty"

    /** lib-x/smaz2's 256 words, the same list [Smaz2] carries. */
    internal val DEFAULT_WORDS: List<String> = Smaz2.WORDS.toList()
    private val DEFAULT_WORD_BYTES: Array<ByteArray> = DEFAULT_WORDS.map { it.toByteArray(Charsets.ISO_8859_1) }.toTypedArray()

    /** The Meshtastic table in table order, duplicates included (the Bridge's). */
    private const val MESHTASTIC_BIGRAMS =
        "intherreheanonesorteattistenntartondalitseediseangoulecomeneriroderaioicliofasetvetasihamaecomceelllcaurlachhidihofonsotacnarssoprrtsassusnoiltsemctgeloeebetrnipeiepancpooldaadviunamutwimoshyoaiewowosfiepttmiopiaweagsuiddoooirspplscaywaigeirylytuulivimabty"
    private val MESHTASTIC_WORDS = arrayOf(
        "mesh", "node", "message", "channel", "signal", "radio", "frequency", "power",
        "antenna", "range", "gateway", "relay", "bridge", "network", "packet", "payload",
        "status", "battery", "voltage", "level", "percent", "position", "latitude", "longitude",
        "altitude", "heading", "speed", "distance", "bearing", "elevation", "terrain", "route",
        "satellite", "iridium", "modem", "serial", "port", "device", "sensor", "timestamp",
        "meshtastic", "location", "coordinates", "waypoint", "temperature", "humidity", "pressure", "weather",
        "connected", "offline", "online", "update", "received", "transmitted", "error", "warning",
        "critical", "enabled", "disabled", "configuration", "settings", "interface", "protocol", "timeout",
        "emergency", "rescue", "medical", "assistance", "helicopter", "vehicle", "boat", "shelter",
        "water", "food", "camp", "base", "standing", "moving", "stopped", "request",
        "respond", "acknowledge", "confirmed", "denied", "approved", "copy", "over", "roger",
        "affirmative", "negative", "clear", "blocked", "north", "south", "east", "west",
        "success", "failed", "pending", "queued", "delivered", "expired", "forwarded", "dropped",
        "retry", "noise", "interference", "coverage", "degrees", "meters", "kilometers", "knots",
        "duration", "interval", "threshold", "maximum", "minimum", "average", "total", "count",
        "report", "checksum", "baud", "cellular", "satellite", "webhook", "mqtt", "iridium",
        "that", "this", "with", "from", "your", "have", "more", "will",
        "home", "about", "time", "they", "what", "which", "their", "there",
        "only", "when", "here", "also", "help", "been", "would", "were",
        "some", "these", "like", "than", "find", "back", "just", "over",
        "into", "them", "should", "then", "good", "well", "where", "right",
        "high", "through", "each", "very", "read", "need", "many", "said",
        "does", "under", "full", "part", "could", "great", "send", "type",
        "because", "local", "those", "using", "result", "before", "make", "data",
        "area", "want", "show", "even", "check", "open", "today", "state",
        "both", "down", "system", "three", "total", "place", "without", "access",
        "think", "current", "control", "change", "small", "rate", "number", "line",
        "name", "list", "work", "last", "next", "used", "free", "other",
        "first", "world", "after", "best", "long", "based", "code", "being",
        "while", "care", "same", "found", "link", "text", "site", "form",
        "event", "love", "main", "still", "information", "service", "people", "year",
        "email", "group", "detail", "design", "board", "point", "test", "track",
    )

    internal val MESHTASTIC_WORD_LIST: List<String> get() = MESHTASTIC_WORDS.toList()
    internal val MESHTASTIC_BIGRAM_TABLE: String get() = MESHTASTIC_BIGRAMS

    private val MESHTASTIC_WORD_BYTES: Array<ByteArray> = MESHTASTIC_WORDS.map { it.toByteArray(Charsets.ISO_8859_1) }.toTypedArray()

    /** Word to index; a word listed twice maps to its last entry, as the Bridge's map does. */
    private val MESHTASTIC_INDEX: Map<String, Int> = HashMap<String, Int>().also { m ->
        MESHTASTIC_WORDS.forEachIndexed { i, w -> m[w] = i }
    }
}

/**
 * The smaz2g frame (HeMB-NG S3-1): one mode byte, then the text's UTF-8 bytes
 * as they are (0x00) or their SMAZ2 (0x01), taken only when strictly shorter.
 * The Bridge's `encodeSmaz2g` / `decodeSmaz2g`. [MESHSAT-1623]
 */
object Smaz2g {
    const val MODE_RAW: Byte = 0x00
    const val MODE_SMAZ2: Byte = 0x01

    fun encode(text: ByteArray, dict: Smaz2Dict): ByteArray {
        val z = Smaz2Bytes.compress(text, dict)
        return if (z.size < text.size) byteArrayOf(MODE_SMAZ2) + z else byteArrayOf(MODE_RAW) + text
    }

    /** The text of [frame]; throws [Smaz2Exception] for an empty frame, an unknown mode, an empty or malformed body. */
    fun decode(frame: ByteArray, dict: Smaz2Dict): ByteArray {
        if (frame.isEmpty()) throw Smaz2Exception("smaz2g: empty frame, no mode byte")
        return when (frame[0]) {
            MODE_RAW -> frame.copyOfRange(1, frame.size)
            MODE_SMAZ2 -> {
                if (frame.size == 1) throw Smaz2Exception("smaz2g: smaz2 mode with no data")
                Smaz2Bytes.decompress(frame.copyOfRange(1, frame.size), dict)
            }
            else -> throw Smaz2Exception("smaz2g: unknown mode 0x%02x".format(frame[0].toInt() and 0xFF))
        }
    }
}
