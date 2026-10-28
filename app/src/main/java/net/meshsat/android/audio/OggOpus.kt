package net.meshsat.android.audio

import java.io.ByteArrayOutputStream

/**
 * The Ogg Opus container (RFC 3533 pages, RFC 7845 headers) a voice note travels in as LXMF
 * audio mode 0x10 (MESHSAT-1735): what Sideband writes and plays. Android's Opus codec speaks
 * raw packets, so this file wraps them on the way out and unwraps them on the way in; the
 * iOS port does the same. Packets stay under 65 025 bytes and never span a page, which every
 * note here satisfies by a wide margin.
 */
object OggOpus {
    /** Opus packets always count at 48 kHz, whatever the input rate. */
    const val RATE_48K = 48_000

    /** The identification header (RFC 7845 5.1). */
    data class Head(val channels: Int, val preSkip: Int, val inputSampleRate: Int, val outputGain: Int = 0) {
        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(19)
            out.write("OpusHead".toByteArray(Charsets.US_ASCII))
            out.write(1)
            out.write(channels)
            out.le16(preSkip)
            out.le32(inputSampleRate.toLong())
            out.le16(outputGain)
            out.write(0)   // mapping family 0: mono or stereo, no table
            return out.toByteArray()
        }

        companion object {
            fun parse(b: ByteArray): Head {
                if (b.size < 19 || String(b, 0, 8, Charsets.US_ASCII) != "OpusHead") throw OggException("not an OpusHead")
                val channels = b[9].toInt() and 0xFF
                val preSkip = (b[10].toInt() and 0xFF) or ((b[11].toInt() and 0xFF) shl 8)
                val rate = (b[12].toLong() and 0xFF) or ((b[13].toLong() and 0xFF) shl 8) or ((b[14].toLong() and 0xFF) shl 16) or ((b[15].toLong() and 0xFF) shl 24)
                val gain = ((b[16].toInt() and 0xFF) or ((b[17].toInt() and 0xFF) shl 8)).toShort().toInt()
                return Head(channels, preSkip, rate.toInt(), gain)
            }
        }
    }

    /** A parsed stream: the header, the audio packets in order, and how long it plays. */
    class Stream(val head: Head, val packets: List<ByteArray>) {
        /** Samples at 48 kHz across every packet, from the packets' own table-of-contents bytes. */
        val samples48k: Long get() = packets.sumOf { packetSamples48k(it).toLong() }
        val durationMs: Long get() = ((samples48k - head.preSkip).coerceAtLeast(0) * 1000L) / RATE_48K
    }

    /**
     * Wraps [packets] (each one Opus packet) into an Ogg Opus stream: the two header pages,
     * then audio pages of about [packetsPerPage] packets, the last one marked end of stream.
     */
    fun write(head: Head, packets: List<ByteArray>, serial: Int = 0x4D53_4154, packetsPerPage: Int = 50, vendor: String = "MeshSat"): ByteArray {
        val out = ByteArrayOutputStream()
        var seq = 0
        out.write(page(serial, seq++, 0L, bos = true, eos = false, packets = listOf(head.encode())))
        out.write(page(serial, seq++, 0L, bos = false, eos = false, packets = listOf(tags(vendor))))
        var granule = 0L
        var i = 0
        if (packets.isEmpty()) {
            out.write(page(serial, seq, 0L, bos = false, eos = true, packets = emptyList()))
            return out.toByteArray()
        }
        while (i < packets.size) {
            val end = minOf(i + packetsPerPage, packets.size)
            val slice = packets.subList(i, end)
            for (p in slice) granule += packetSamples48k(p)
            out.write(page(serial, seq++, granule, bos = false, eos = end == packets.size, packets = slice))
            i = end
        }
        return out.toByteArray()
    }

    /** Reads the first logical stream of an Ogg Opus file; throws [OggException] on anything else. */
    fun read(data: ByteArray): Stream {
        var pos = 0
        var serial: Int? = null
        var expectedSeq = 0
        val packets = ArrayList<ByteArray>()
        var partial: ByteArrayOutputStream? = null
        var sawEos = false
        while (pos + 27 <= data.size && !sawEos) {
            if (String(data, pos, 4, Charsets.US_ASCII) != "OggS") throw OggException("no page at $pos")
            if (data[pos + 4].toInt() != 0) throw OggException("ogg version ${data[pos + 4]}")
            val type = data[pos + 5].toInt() and 0xFF
            val pageSerial = le32(data, pos + 14).toInt()
            val seq = le32(data, pos + 18).toInt()
            val crc = le32(data, pos + 22)
            val nseg = data[pos + 26].toInt() and 0xFF
            val headerLen = 27 + nseg
            if (pos + headerLen > data.size) throw OggException("truncated segment table at $pos")
            var bodyLen = 0
            for (s in 0 until nseg) bodyLen += data[pos + 27 + s].toInt() and 0xFF
            val pageLen = headerLen + bodyLen
            if (pos + pageLen > data.size) throw OggException("truncated page at $pos")
            val copy = data.copyOfRange(pos, pos + pageLen)
            for (k in 22..25) copy[k] = 0
            if (crc(copy) != crc) throw OggException("bad page crc at $pos")
            if (serial == null) {
                if (type and 0x02 == 0) throw OggException("first page is not a stream start")
                serial = pageSerial
            }
            if (pageSerial == serial) {
                if (seq != expectedSeq) throw OggException("page sequence $seq, expected $expectedSeq")
                expectedSeq++
                var p = pos + headerLen
                var cur = partial ?: ByteArrayOutputStream()
                if (type and 0x01 == 0) cur = ByteArrayOutputStream()
                for (s in 0 until nseg) {
                    val len = data[pos + 27 + s].toInt() and 0xFF
                    cur.write(data, p, len)
                    p += len
                    if (len < 255) { packets.add(cur.toByteArray()); cur = ByteArrayOutputStream() }
                }
                partial = if (cur.size() > 0) cur else null
                if (type and 0x04 != 0) sawEos = true
            }
            pos += pageLen
        }
        if (packets.size < 2) throw OggException("no opus headers")
        val head = Head.parse(packets[0])
        if (String(packets[1], 0, minOf(8, packets[1].size), Charsets.US_ASCII) != "OpusTags") throw OggException("second packet is not OpusTags")
        return Stream(head, packets.subList(2, packets.size))
    }

    /** True when [data] starts like an Ogg Opus stream. */
    fun looksLikeOggOpus(data: ByteArray): Boolean =
        data.size > 36 && String(data, 0, 4, Charsets.US_ASCII) == "OggS" && String(data, 28, 8, Charsets.US_ASCII) == "OpusHead"

    /**
     * The samples (at 48 kHz) one Opus packet decodes to, from its table-of-contents byte
     * (RFC 6716 3.1): configurations 0-11 are SILK at 10/20/40/60 ms, 12-15 hybrid at 10/20 ms,
     * 16-31 CELT at 2.5/5/10/20 ms; code 3 packets carry their frame count in the next byte.
     */
    fun packetSamples48k(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> intArrayOf(480, 960)[config and 1]
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size > 1) p[1].toInt() and 0x3F else 0
        }
        return frameSamples * frames
    }

    /** The Ogg page checksum: CRC-32 with polynomial 0x04c11db7, no reflection, no initial or final xor. */
    fun crc(b: ByteArray): Long {
        var r = 0L
        for (x in b) {
            r = ((r shl 8) and 0xFFFFFFFFL) xor TABLE[(((r shr 24) xor (x.toLong() and 0xFF)) and 0xFF).toInt()]
        }
        return r
    }

    private val TABLE = LongArray(256).also { t ->
        for (i in 0 until 256) {
            var r = (i.toLong() shl 24) and 0xFFFFFFFFL
            repeat(8) { r = if (r and 0x8000_0000L != 0L) ((r shl 1) and 0xFFFFFFFFL) xor 0x04C1_1DB7L else (r shl 1) and 0xFFFFFFFFL }
            t[i] = r
        }
    }

    private fun tags(vendor: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray(Charsets.US_ASCII))
        val v = vendor.toByteArray(Charsets.UTF_8)
        out.le32(v.size.toLong()); out.write(v)
        out.le32(0)
        return out.toByteArray()
    }

    private fun page(serial: Int, seq: Int, granule: Long, bos: Boolean, eos: Boolean, packets: List<ByteArray>): ByteArray {
        val lacing = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for (p in packets) {
            if (p.size >= 255 * 255) throw OggException("packet of ${p.size} bytes does not fit a page")
            var left = p.size
            while (left >= 255) { lacing.write(255); left -= 255 }
            lacing.write(left)
            body.write(p)
        }
        if (lacing.size() > 255) throw OggException("too many segments for one page")
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.US_ASCII))
        out.write(0)
        out.write((if (bos) 0x02 else 0) or (if (eos) 0x04 else 0))
        out.le64(granule)
        out.le32(serial.toLong() and 0xFFFFFFFFL)
        out.le32(seq.toLong() and 0xFFFFFFFFL)
        out.le32(0)
        out.write(lacing.size())
        out.write(lacing.toByteArray())
        out.write(body.toByteArray())
        val page = out.toByteArray()
        val c = crc(page)
        page[22] = (c and 0xFF).toByte(); page[23] = ((c shr 8) and 0xFF).toByte()
        page[24] = ((c shr 16) and 0xFF).toByte(); page[25] = ((c shr 24) and 0xFF).toByte()
        return page
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.le32(v: Long) { for (s in intArrayOf(0, 8, 16, 24)) write(((v shr s) and 0xFF).toInt()) }
    private fun ByteArrayOutputStream.le64(v: Long) { for (s in intArrayOf(0, 8, 16, 24, 32, 40, 48, 56)) write(((v shr s) and 0xFF).toInt()) }
}

class OggException(message: String) : Exception(message)
