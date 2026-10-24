package net.meshsat.android

import net.meshsat.android.bt.SignalFreshness
import net.meshsat.android.bt.SignalPollStats
import net.meshsat.android.bt.SignalReading
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rule and the words for a 9704 reading's age, shared with MeshSat iOS (MESHSAT-1670). */
class SignalFreshnessTest {

    private val t0 = 1_000_000L

    @Test
    fun `the threshold is two poll intervals plus the poll timeout`() {
        assertEquals(66_000L, SignalFreshness.STALE_AFTER_MS)
        val r = SignalReading(4, t0)
        assertEquals(SignalFreshness.Kind.Fresh, SignalFreshness.kind(r, t0))
        assertEquals(SignalFreshness.Kind.Fresh, SignalFreshness.kind(r, t0 + 66_000))
        assertEquals(SignalFreshness.Kind.Stale, SignalFreshness.kind(r, t0 + 66_001))
        assertEquals(SignalFreshness.Kind.None, SignalFreshness.kind(null, t0))
    }

    @Test
    fun `the words`() {
        val r = SignalReading(3, t0)
        assertEquals("3/5", SignalFreshness.describe(r, t0 + 10_000))
        assertEquals("3/5, last reading 70 s ago", SignalFreshness.describe(r, t0 + 70_000))
        assertEquals("3/5, last reading 4 min ago", SignalFreshness.describe(r, t0 + 4 * 60_000 + 30_000))
        assertEquals("3/5, last reading 3 h ago", SignalFreshness.describe(r, t0 + 3 * 3_600_000 + 1))
        assertEquals("?/5, no reading yet", SignalFreshness.describe(null, t0))
        assertEquals("3/5", SignalFreshness.compact(r, t0))
        assertEquals("3/5 (2 min ago)", SignalFreshness.compact(r, t0 + 150_000))
        assertEquals("?/5", SignalFreshness.compact(null, t0))
        assertEquals("0/5", SignalFreshness.describe(SignalReading(0, t0), t0))
    }

    @Test
    fun `round trip statistics`() {
        assertEquals("", SignalPollStats().describe())
        val s = SignalPollStats.of(listOf(300L, 120L, 2400L), answered = 3, missed = 1)
        assertEquals(120L, s.minMs)
        assertEquals(300L, s.medianMs)
        assertEquals(2400L, s.maxMs)
        assertEquals("120 / 300 / 2400 ms (3 answered, 1 missed)", s.describe())
        assertEquals("0 / 0 / 0 ms (0 answered, 2 missed)", SignalPollStats.of(emptyList(), 0, 2).describe())
    }
}
