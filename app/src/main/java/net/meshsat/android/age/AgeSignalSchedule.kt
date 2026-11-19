package net.meshsat.android.age

/**
 * When the Google Play edition asks Play for the user's age range (MESHSAT-1908).
 *
 * California's Digital Age Assurance Act (AB 1043 as amended by AB 1856, operative 1 Jan 2027)
 * asks an app's developer to request an age signal when the app is downloaded and launched, and
 * Brazil's Digital ECA has Play share one for every user there from January 2027. The play
 * edition therefore asks at a start of the app, at most once a day: an ask that is a day old is
 * made again, so a user whose region becomes one with mandatory sharing (California on 1 Jan
 * 2027) is asked at the first start after that. A failed ask (no Play Store, no network) is
 * tried again at a start an hour or more later.
 *
 * Nothing here is used to change what the app does: MeshSat is made for adults (its Play target
 * audience is 18 and over) and nothing in it is limited by age. The answer is not stored, logged
 * or sent anywhere; only the time of the last completed ask is kept, in [PREFS].
 *
 * The fdroid edition has no Play services and never asks; [AgeSignals] is a no-op there.
 */
object AgeSignalSchedule {
    /** SharedPreferences file holding [KEY_DONE_MS]. */
    const val PREFS = "age_signals"

    /** Wall-clock milliseconds of the last ask that Play answered. */
    const val KEY_DONE_MS = "done_ms"

    /** A completed ask is made again after this long. */
    const val REFRESH_MS = 24L * 60 * 60 * 1000

    /** A failed ask is not tried again in the same hour. */
    const val RETRY_MS = 60L * 60 * 1000

    /**
     * Whether to ask now. [doneMs] is the last completed ask (0 = never), [triedMs] the last try
     * in this process (0 = none). A clock that went backwards counts as due, so a wrong date set
     * once cannot silence the ask for good.
     */
    fun due(doneMs: Long, triedMs: Long, nowMs: Long): Boolean {
        val refresh = doneMs <= 0L || nowMs < doneMs || nowMs - doneMs >= REFRESH_MS
        val retry = triedMs <= 0L || nowMs < triedMs || nowMs - triedMs >= RETRY_MS
        return refresh && retry
    }
}

/** What one ask came to, in words for the log. Carries no age. */
sealed class AgeSignalOutcome {
    /** Whether Play answered, so the ask is not made again for [AgeSignalSchedule.REFRESH_MS]. */
    abstract val done: Boolean
    abstract fun describe(): String

    /** Play says no law requires sharing for this user; nothing was requested, no prompt shown. */
    data object NotRequired : AgeSignalOutcome() {
        override val done = true
        override fun describe() = "Play reports no mandatory age sharing for this user; nothing requested"
    }

    /** Requested; Play shared an age range, which was read and not kept. */
    data object Shared : AgeSignalOutcome() {
        override val done = true
        override fun describe() = "requested; Play shared an age range (not kept)"
    }

    /** Requested; Play shared none. */
    data object NotShared : AgeSignalOutcome() {
        override val done = true
        override fun describe() = "requested; Play did not share an age range"
    }

    /** Requested; Play wants the user to verify their age in the Play Store first. */
    data object VerificationRequired : AgeSignalOutcome() {
        override val done = true
        override fun describe() = "requested; Play asks the user to verify their age in the Play Store"
    }

    /** Play could not be asked or did not answer; [code] is Play's error code when it gave one. */
    data class Failed(val code: Int?) : AgeSignalOutcome() {
        override val done = false
        override fun describe() =
            "Play could not answer (" + (code?.let { "error $it" } ?: "no error code") + "); asked again at a later start"
    }
}
