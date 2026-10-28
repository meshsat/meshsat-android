package net.meshsat.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Ogg Opus container around a note's packets (MESHSAT-1735). */
class OggOpusTest {
    /** A 20 ms SILK packet (TOC config 1, code 0) of [n] bytes. */
    private fun silk20(n: Int, seed: Int) = ByteArray(n) { if (it == 0) 0x08 else ((it * 31 + seed) and 0xFF).toByte() }

    @Test
    fun crcTableIsTheOggPolynomial() {
        // CRC-32 with 0x04c11db7, no reflection: the table's first entries, and a one-byte check.
        assertEquals(0L, OggOpus.crc(byteArrayOf(0)))
        assertEquals(0x04C11DB7L, OggOpus.crc(byteArrayOf(1)))
        assertEquals(0x09823B6EL, OggOpus.crc(byteArrayOf(2)))
        assertEquals(0x0D4326D9L, OggOpus.crc(byteArrayOf(3)))
        assertEquals(0xB1F740B4L, OggOpus.crc(byteArrayOf(0xFF.toByte())))
        // Computed bit by bit from the polynomial, outside this code: the start of a page header.
        assertEquals(0xF1637C67L, OggOpus.crc("OggS\u0000\u0002".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun headRoundTrip() {
        val h = OggOpus.Head(channels = 1, preSkip = 312, inputSampleRate = 48_000, outputGain = -256)
        val b = h.encode()
        assertEquals(19, b.size)
        assertEquals("OpusHead", String(b, 0, 8, Charsets.US_ASCII))
        assertEquals(1, b[8].toInt())
        assertEquals(h, OggOpus.Head.parse(b))
    }

    @Test
    fun packetSamplesFromTheToc() {
        assertEquals(960, OggOpus.packetSamples48k(byteArrayOf(0x08)))            // SILK NB 20 ms
        assertEquals(2880, OggOpus.packetSamples48k(byteArrayOf(0x18)))           // SILK NB 60 ms (Sideband)
        assertEquals(1920, OggOpus.packetSamples48k(byteArrayOf(0x09)))           // code 1: two 20 ms frames
        assertEquals(480, OggOpus.packetSamples48k(byteArrayOf(0x60)))            // hybrid 10 ms
        assertEquals(120, OggOpus.packetSamples48k(byteArrayOf(0x80.toByte())))   // CELT 2.5 ms
        assertEquals(2880, OggOpus.packetSamples48k(byteArrayOf(0x0B, 0x03)))     // code 3: three 20 ms frames
        assertEquals(0, OggOpus.packetSamples48k(ByteArray(0)))
    }

    @Test
    fun writeThenReadGivesTheSamePacketsAndDuration() {
        val head = OggOpus.Head(1, 312, 48_000)
        val packets = (0 until 123).map { silk20(20 + it % 50, it) }
        val ogg = OggOpus.write(head, packets, packetsPerPage = 50)
        assertTrue(OggOpus.looksLikeOggOpus(ogg))
        val s = OggOpus.read(ogg)
        assertEquals(head, s.head)
        assertEquals(packets.size, s.packets.size)
        packets.forEachIndexed { i, p -> assertArrayEquals("packet $i", p, s.packets[i]) }
        assertEquals(123L * 960, s.samples48k)
        assertEquals((123L * 960 - 312) * 1000 / 48_000, s.durationMs)
        // Pages: 2 headers + ceil(123 / 50) = 3 audio pages; the last one carries the end flag.
        var pages = 0; var pos = 0; var lastType = 0
        while (pos < ogg.size) {
            val nseg = ogg[pos + 26].toInt() and 0xFF
            var body = 0; for (k in 0 until nseg) body += ogg[pos + 27 + k].toInt() and 0xFF
            lastType = ogg[pos + 5].toInt(); pages++; pos += 27 + nseg + body
        }
        assertEquals(5, pages); assertEquals(0x04, lastType)
    }

    @Test
    fun longPacketsLaceAcrossSegments() {
        val head = OggOpus.Head(1, 0, 48_000)
        val packets = listOf(silk20(255, 1), silk20(510, 2), silk20(700, 3), silk20(1, 4))
        val s = OggOpus.read(OggOpus.write(head, packets))
        packets.forEachIndexed { i, p -> assertArrayEquals("packet $i", p, s.packets[i]) }
    }

    @Test
    fun corruptionIsRefused() {
        val ogg = OggOpus.write(OggOpus.Head(1, 312, 48_000), listOf(silk20(30, 1)))
        val bad = ogg.copyOf(); bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x55).toByte()
        assertTrue(runCatching { OggOpus.read(bad) }.exceptionOrNull() is OggException)
        assertTrue(runCatching { OggOpus.read("not ogg at all, really not".toByteArray()) }.exceptionOrNull() is OggException)
        assertTrue(runCatching { OggOpus.read(ogg.copyOfRange(0, ogg.size - 3)) }.exceptionOrNull() is OggException)
    }
}
