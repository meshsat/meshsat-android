package net.meshsat.android

import net.meshsat.android.hub.BirthStampException
import net.meshsat.android.hub.BirthStampStore
import net.meshsat.android.hub.BirthStamper
import net.meshsat.android.hub.BridgeBirth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * The birth stamps the Hub orders this phone's births by (MESHSAT-1623, R32-R1), the cases the
 * Bridge's stamp tests hold: a clock behind, ahead or stepped back, a restart, a floor that cannot
 * be read, a stamp that cannot be stored, and a stored value that is not a timestamp.
 */
class BirthStamperTest {

    /** A store whose reads and writes fail on cue, and which keeps what it stored like a file. */
    private class Store(var stored: String? = null) : BirthStampStore {
        var failRead = false
        var failSave = false
        val saved = mutableListOf<Instant>()
        override fun lastBirthStamp(): Instant? {
            if (failRead) throw java.io.IOException("read refused")
            return stored?.let { BirthStamper.parse(it) }
        }
        override fun saveBirthStamp(stamp: Instant) {
            if (failSave) throw java.io.IOException("save refused")
            stored = BirthStamper.format(stamp)
            saved += stamp
        }
    }

    private val t0 = Instant.parse("2026-10-21T13:00:00Z")

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("not refused")
        } catch (e: BirthStampException) {
        }
    }

    @Test
    fun `stamps follow the clock and never go back`() {
        val s = BirthStamper(Store())
        val a = s.next(t0)
        assertEquals(t0, a)
        val behind = s.next(t0.minusSeconds(3600))
        assertEquals(t0.plusNanos(1000), behind)
        val same = s.next(t0)
        assertTrue(same.isAfter(behind))
        val ahead = s.next(t0.plusSeconds(60))
        assertEquals(t0.plusSeconds(60), ahead)
    }

    @Test
    fun `a restart stamps later than every birth already sent, with the clock behind`() {
        val store = Store()
        val first = BirthStamper(store)
        val sent = listOf(first.next(t0.plusSeconds(3600)), first.next(t0.plusSeconds(3600)))
        val after = BirthStamper(store).next(t0)
        assertTrue(sent.all { after.isAfter(it) })
        assertEquals(sent.last().plusNanos(1000), after)
    }

    @Test
    fun `an unreadable floor hands out nothing until it is read`() {
        val store = Store(BirthStamper.format(t0))
        store.failRead = true
        val s = BirthStamper(store)
        refused { s.next(t0.minusSeconds(60)) }
        refused { s.next(t0.minusSeconds(60)) }
        assertEquals(emptyList<Instant>(), store.saved)
        store.failRead = false
        assertEquals(t0.plusNanos(1000), s.next(t0.minusSeconds(60)))
    }

    @Test
    fun `a stored value that is not a timestamp is an error, never no stamp`() {
        val store = Store("not a time")
        refused { BirthStamper(store).next(t0) }
        assertEquals("not a time", store.stored)
    }

    @Test
    fun `a stamp that cannot be stored is not handed out, and the floor does not move`() {
        val store = Store()
        val s = BirthStamper(store)
        val a = s.next(t0)
        store.failSave = true
        refused { s.next(t0.minusSeconds(10)) }
        assertEquals(BirthStamper.format(a), store.stored)
        store.failSave = false
        val b = s.next(t0.minusSeconds(10))
        assertEquals(a.plusNanos(1000), b)
        // A restart after the failure still stamps later than everything handed out.
        assertTrue(BirthStamper(store).next(t0.minusSeconds(10)).isAfter(b))
    }

    @Test
    fun `no store keeps the order for this process`() {
        val s = BirthStamper(null)
        val a = s.next(t0)
        assertTrue(s.next(t0.minusSeconds(5)).isAfter(a))
    }

    @Test
    fun `the stamp travels in the birth as RFC 3339 with its microseconds`() {
        val stamp = t0.plusNanos(1000)
        assertEquals("2026-10-21T13:00:00.000001Z", BirthStamper.format(stamp))
        assertEquals(stamp, BirthStamper.parse(BirthStamper.format(stamp)))
        // Sub-microsecond clock digits are cut, so a stored stamp reads back exactly.
        assertEquals(t0.plusNanos(1000), BirthStamper(null).next(t0.plusNanos(1999)))
        val json = BridgeBirth(bridgeId = "msa-test", version = "x", hostname = "h", timestamp = BirthStamper.format(stamp)).toJson()
        assertEquals("2026-10-21T13:00:00.000001Z", json.getString("timestamp"))
    }
}
