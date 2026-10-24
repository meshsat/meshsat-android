package net.meshsat.android.bt

/**
 * One 9704 signal reading and the time it was taken (MESHSAT-1670): from an answered
 * `GET constellationState` or an unsolicited `299 constellationState`. A read the modem did not
 * answer is no reading, so it never shows as 0 bars; an answered 0 is a real 0.
 */
data class SignalReading(val bars: Int, val atMs: Long)

/**
 * How old a reading may be before the screen says so (MESHSAT-1670). The rule and the words are
 * shared with MeshSat iOS: a reading is fresh while its age is at most two poll intervals plus the
 * poll timeout, older is stale (shown with its age), and none since the modem came up is "?".
 * Delivered messages decide whether the satellite works, not the bars.
 */
object SignalFreshness {
    /** The driver asks the modem when no reading came for this long; events usually come sooner. */
    const val POLL_INTERVAL_MS = 30_000L

    /** How long one `GET constellationState` may take over the node's pipe. */
    const val POLL_TIMEOUT_MS = 6_000L

    const val STALE_AFTER_MS = 2 * POLL_INTERVAL_MS + POLL_TIMEOUT_MS

    enum class Kind { None, Fresh, Stale }

    fun kind(reading: SignalReading?, nowMs: Long): Kind = when {
        reading == null -> Kind.None
        nowMs - reading.atMs <= STALE_AFTER_MS -> Kind.Fresh
        else -> Kind.Stale
    }

    /** "70 s", "4 min", "3 h": seconds below two minutes, minutes below two hours. */
    fun age(reading: SignalReading, nowMs: Long): String {
        val s = ((nowMs - reading.atMs) / 1000).coerceAtLeast(0)
        return when {
            s < 120 -> "$s s"
            s < 120 * 60 -> "${s / 60} min"
            else -> "${s / 3600} h"
        }
    }

    /** "3/5", "3/5, last reading 4 min ago", or "?/5, no reading yet". */
    fun describe(reading: SignalReading?, nowMs: Long): String = when (kind(reading, nowMs)) {
        Kind.None -> "?/5, no reading yet"
        Kind.Fresh -> "${reading!!.bars}/5"
        Kind.Stale -> "${reading!!.bars}/5, last reading ${age(reading, nowMs)} ago"
    }

    /** The short form for a lane: "3/5", "3/5 (4 min ago)" or "?/5". */
    fun compact(reading: SignalReading?, nowMs: Long): String = when (kind(reading, nowMs)) {
        Kind.None -> "?/5"
        Kind.Fresh -> "${reading!!.bars}/5"
        Kind.Stale -> "${reading!!.bars}/5 (${age(reading, nowMs)} ago)"
    }
}

/**
 * The round trips of the signal reads on one connection (MESHSAT-1670), for the bench: the
 * timeout over the node's pipe is to be set from these, not by guess.
 */
data class SignalPollStats(
    val answered: Int = 0,
    val missed: Int = 0,
    val minMs: Long = 0,
    val medianMs: Long = 0,
    val maxMs: Long = 0,
) {
    /** "120 / 310 / 2400 ms (14 answered, 1 missed)", or "" before the first read. */
    fun describe(): String =
        if (answered == 0 && missed == 0) "" else "$minMs / $medianMs / $maxMs ms ($answered answered, $missed missed)"

    companion object {
        const val KEEP = 64

        /** [roundTripsMs] are the last [KEEP] answered reads; [answered] counts all of them. */
        fun of(roundTripsMs: List<Long>, answered: Int, missed: Int): SignalPollStats {
            if (roundTripsMs.isEmpty()) return SignalPollStats(answered = answered, missed = missed)
            val sorted = roundTripsMs.sorted()
            return SignalPollStats(
                answered = answered,
                missed = missed,
                minMs = sorted.first(),
                medianMs = sorted[sorted.size / 2],
                maxMs = sorted.last(),
            )
        }
    }
}

/**
 * One MO segment request and the app's answer (MESHSAT-1674): how long after `PUT messageOriginate`
 * the modem asked, and how long the app took from reading the request to handing its answer to the
 * link. The 9704 gives up after about 360 ms, counted at the modem.
 */
data class SegmentTiming(val askedAfterMs: Long, val answeredInMs: Long, val segmentLength: Int)
