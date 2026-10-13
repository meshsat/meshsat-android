package net.meshsat.android.rns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CrossTalk's IMT framing: a packet is "RNSI" 0x01 + raw, a text is anything else. [MESHSAT-1553 P6] */
class ImtFramingTest {
    private val packet = Header(destHash = ByteArray(16) { 7 }, data = "payload".toByteArray()).pack()

    @Test
    fun `a framed packet round-trips and the header is CrossTalk's`() {
        val wire = ImtFraming.encode(packet)
        assertEquals("524e534901", wire.copyOf(5).hex())
        assertArrayEquals(packet, ImtFraming.decode(wire))
        assertTrue(ImtFraming.isFramed(wire))
    }

    @Test
    fun `texts stay texts, even ones that start with the magic`() {
        assertNull(ImtFraming.decode("hello from the park, 30 bytes of text".toByteArray()))
        assertNull(ImtFraming.decode("RNSI\u0001".toByteArray()))                     // nothing after the header
        assertNull(ImtFraming.decode("RNSI\u0002".toByteArray() + packet))             // another version
        assertNull(ImtFraming.decode("RNSI is the name of our club tonight".toByteArray()))   // no packet behind the magic
        assertFalse(ImtFraming.isFramed(ByteArray(0)))
    }

    @Test
    fun `repeated frames are dropped inside the window`() {
        val d = ImtDedup(ttlMs = 1000, max = 2)
        val a = "a".toByteArray(); val b = "b".toByteArray(); val c = "c".toByteArray()
        assertFalse(d.seen(a, 0)); assertTrue(d.seen(a, 10))
        assertFalse(d.seen(b, 20)); assertFalse(d.seen(c, 30))   // a was evicted (max 2)
        assertFalse(d.seen(a, 40))
        assertFalse(d.seen(a, 2000))   // the window passed
    }
}
