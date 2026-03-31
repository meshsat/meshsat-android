package com.cubeos.meshsat.codec

/**
 * SMAZ2 — short string compression using 128 common bigrams + 256 common words.
 * Ported from Go github.com/lib-x/smaz2 for wire compatibility with MeshSat bridges.
 *
 * Wire format:
 *   - 0x80|idx    → bigram (2 chars from bigrams table)
 *   - 0x01..0x05  → verbatim run (next N bytes are literal)
 *   - 0x06        → word (next byte = word index)
 *   - 0x07        → word + trailing space
 *   - 0x08        → leading space + word
 *   - 0x09..0x7F  → literal byte (chars 9-127)
 *
 * [MESHSAT-447]
 */
object Smaz2 {

    private const val BIGRAMS =
        "intherreheanonesorteattistenntartondalitseediseangoulecomeneriroderaioicliofasetvetasihamaecomceelllcaurlachhidihofonsotacnarssoprrtsassusnoiltsemctgeloeebetrnipeiepancpooldaadviunamutwimoshyoaiewowosfiepttmiopiaweagsuiddoooirspplscaywaigeirylytuulivimabty"

    private val WORDS = arrayOf(
        "that", "this", "with", "from", "your", "have", "more", "will", "home",
        "about", "page", "search", "free", "other", "information", "time", "they",
        "what", "which", "their", "news", "there", "only", "when", "contact", "here",
        "business", "also", "help", "view", "online", "first", "been", "would", "were",
        "some", "these", "click", "like", "service", "than", "find", "date", "back",
        "people", "list", "name", "just", "over", "year", "into", "email", "health",
        "world", "next", "used", "work", "last", "most", "music", "data", "make",
        "them", "should", "product", "post", "city", "policy", "number", "such",
        "please", "available", "copyright", "support", "message", "after", "best",
        "software", "then", "good", "video", "well", "where", "info", "right", "public",
        "high", "school", "through", "each", "order", "very", "privacy", "book", "item",
        "company", "read", "group", "need", "many", "user", "said", "does", "under",
        "general", "research", "university", "january", "mail", "full", "review",
        "program", "life", "know", "days", "management", "part", "could", "great",
        "united", "real", "international", "center", "ebay", "must", "store", "travel",
        "comment", "made", "development", "report", "detail", "line", "term", "before",
        "hotel", "send", "type", "because", "local", "those", "using", "result",
        "office", "education", "national", "design", "take", "posted", "internet",
        "address", "community", "within", "state", "area", "want", "phone", "shipping",
        "reserved", "subject", "between", "forum", "family", "long", "based", "code",
        "show", "even", "black", "check", "special", "price", "website", "index",
        "being", "women", "much", "sign", "file", "link", "open", "today", "technology",
        "south", "case", "project", "same", "version", "section", "found", "sport",
        "house", "related", "security", "both", "county", "american", "game", "member",
        "power", "while", "care", "network", "down", "computer", "system", "three",
        "total", "place", "following", "download", "without", "access", "think",
        "north", "resource", "current", "media", "control", "water", "history",
        "picture", "size", "personal", "since", "including", "guide", "shop",
        "directory", "board", "location", "change", "white", "text", "small", "rating",
        "rate", "government", "child", "during", "return", "student", "shopping",
        "account", "site", "level", "digital", "profile", "previous", "form", "event",
        "love", "main", "another", "class", "still",
    )

    /**
     * Decompress SMAZ2-compressed bytes back to the original string.
     * Returns null if the input is invalid or empty.
     */
    fun decompress(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val res = StringBuilder()
        var i = 0
        try {
            while (i < data.size) {
                val b = data[i].toInt() and 0xFF
                when {
                    // High bit set → bigram lookup
                    b and 0x80 != 0 -> {
                        val idx = (b and 0x7F) * 2
                        if (idx + 1 >= BIGRAMS.length) return null
                        res.append(BIGRAMS[idx])
                        res.append(BIGRAMS[idx + 1])
                        i++
                    }
                    // 1..5 → verbatim run of N bytes
                    b in 1..5 -> {
                        if (i + b >= data.size) return null
                        for (j in 1..b) {
                            res.append(data[i + j].toInt().toChar())
                        }
                        i += 1 + b
                    }
                    // 6 → word
                    b == 6 -> {
                        if (i + 1 >= data.size) return null
                        val wordIdx = data[i + 1].toInt() and 0xFF
                        if (wordIdx >= WORDS.size) return null
                        res.append(WORDS[wordIdx])
                        i += 2
                    }
                    // 7 → word + trailing space
                    b == 7 -> {
                        if (i + 1 >= data.size) return null
                        val wordIdx = data[i + 1].toInt() and 0xFF
                        if (wordIdx >= WORDS.size) return null
                        res.append(WORDS[wordIdx])
                        res.append(' ')
                        i += 2
                    }
                    // 8 → leading space + word
                    b == 8 -> {
                        if (i + 1 >= data.size) return null
                        val wordIdx = data[i + 1].toInt() and 0xFF
                        if (wordIdx >= WORDS.size) return null
                        res.append(' ')
                        res.append(WORDS[wordIdx])
                        i += 2
                    }
                    // 9..127 → literal byte
                    else -> {
                        res.append(b.toChar())
                        i++
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return res.toString()
    }

    /**
     * Compress a string using SMAZ2. Returns compressed bytes.
     */
    fun compress(input: String): ByteArray {
        val sb = input.toByteArray(Charsets.UTF_8)
        val dst = mutableListOf<Byte>()
        var verbatimLen = 0
        var pos = 0

        while (pos < sb.size) {
            // Try word match (4+ chars)
            var wordMatch = false
            var wordIdx = 0
            var wordLen = 0
            if (pos + 3 < sb.size) {
                for ((wi, word) in WORDS.withIndex()) {
                    if (pos + word.length <= sb.size) {
                        val slice = String(sb, pos, word.length, Charsets.UTF_8)
                        if (slice == word) {
                            wordMatch = true
                            wordIdx = wi
                            wordLen = word.length
                            break
                        }
                    }
                }
            }

            if (wordMatch) {
                when {
                    sb[pos] == ' '.code.toByte() -> {
                        dst.add(8)
                        dst.add(wordIdx.toByte())
                        pos++
                    }
                    pos + wordLen < sb.size && sb[pos + wordLen] == ' '.code.toByte() -> {
                        dst.add(7)
                        dst.add(wordIdx.toByte())
                        pos++
                    }
                    else -> {
                        dst.add(6)
                        dst.add(wordIdx.toByte())
                    }
                }
                pos += wordLen
                verbatimLen = 0
                continue
            }

            // Try bigram match
            if (pos + 1 < sb.size) {
                val c0 = (sb[pos].toInt() and 0xFF).toChar()
                val c1 = (sb[pos + 1].toInt() and 0xFF).toChar()
                var bigramIdx = -1
                for (bi in 0 until BIGRAMS.length / 2) {
                    if (BIGRAMS[bi * 2] == c0 && BIGRAMS[bi * 2 + 1] == c1) {
                        bigramIdx = bi
                        break
                    }
                }
                if (bigramIdx >= 0) {
                    dst.add((0x80 or bigramIdx).toByte())
                    pos += 2
                    verbatimLen = 0
                    continue
                }
            }

            // Literal char 1-8 range (self-encoding)
            val ch = sb[pos].toInt() and 0xFF
            if (ch in 1..8 && ch < 128) {
                dst.add(ch.toByte())
                pos++
                verbatimLen = 0
                continue
            }

            // Verbatim
            verbatimLen++
            if (verbatimLen == 1) {
                dst.add(verbatimLen.toByte())
                dst.add(sb[pos])
            } else {
                dst.add(sb[pos])
                dst[dst.size - (verbatimLen + 1)] = verbatimLen.toByte()
                if (verbatimLen == 5) {
                    verbatimLen = 0
                }
            }
            pos++
        }

        return dst.toByteArray()
    }
}
