package net.meshsat.android.hub

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Keeps the timestamp of the last birth stamped, across restarts. [lastBirthStamp] answers null
 * when none was ever stored, and throws when it cannot be read or what is stored is not a
 * timestamp: an unknown floor is never "no floor". [saveBirthStamp] throws when it cannot store.
 */
interface BirthStampStore {
    fun lastBirthStamp(): Instant?
    fun saveBirthStamp(stamp: Instant)
}

class BirthStampException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reserves the timestamp of the next birth, as the Bridge's hubreporter.birthStamp does: now, but
 * always later than the last stamp stored, also across a restart. The Hub orders a bridge's births
 * by it and writes a declaration only when it is not older than what it stored (HeMB-NG S3-2), so
 * a phone clock that stepped back cannot make a newer birth look older.
 *
 * The floor is read until it is read, and a stamp is stored before it is handed out: one that could
 * not be stored is never handed out, so a restart never hands out again a stamp a birth carried.
 * [MESHSAT-1623]
 */
class BirthStamper(private val store: BirthStampStore?) {
    private var last: Instant = Instant.EPOCH
    private var loaded = false

    @Synchronized
    fun next(now: Instant): Instant {
        if (!loaded) {
            if (store != null) {
                val stored = try {
                    store.lastBirthStamp()
                } catch (e: Exception) {
                    throw BirthStampException("the last birth stamp could not be read: ${e.message}", e)
                }
                if (stored != null && stored.isAfter(last)) last = stored
            }
            loaded = true
        }
        var stamp = now.truncatedTo(ChronoUnit.MICROS)
        if (!stamp.isAfter(last)) stamp = last.plus(1, ChronoUnit.MICROS)
        if (store != null) {
            try {
                store.saveBirthStamp(stamp)
            } catch (e: Exception) {
                throw BirthStampException("the birth stamp could not be stored: ${e.message}", e)
            }
        }
        last = stamp
        return stamp
    }

    companion object {
        /** RFC 3339 in UTC with as many fraction digits as needed, as the Hub parses a birth's timestamp. */
        fun format(stamp: Instant): String = DateTimeFormatter.ISO_INSTANT.format(stamp)

        /** The stored form back; throws when it is not a timestamp. */
        fun parse(stored: String): Instant = Instant.parse(stored)
    }
}
