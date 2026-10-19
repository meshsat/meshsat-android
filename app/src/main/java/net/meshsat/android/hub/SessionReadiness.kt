package net.meshsat.android.hub

import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the Hub session's subscriptions hold (hub_msg, MESHSAT-1584, r13 review PH-R2). A session
 * is ready only once every filter of its subscribe was granted; a session that is lost or comes back
 * is not ready again until its own subscribe succeeds. A refused subscribe is tried again on the same
 * session, backing off from [minMs] to [maxMs], and the attempts stop the moment their session is
 * replaced, lost or stopped. Readiness starts the route's walk, never a bare transport connect.
 */
class SessionReadiness(
    private val minMs: Long = 2_000,
    private val maxMs: Long = 60_000,
) {
    private val gen = AtomicInteger()

    @Volatile
    var ready = false
        private set

    /** A session begins, is lost or comes back: what was subscribed no longer holds. */
    fun invalidate(): Int = synchronized(this) {
        ready = false
        gen.incrementAndGet()
    }

    /** Marks [session] ready, unless a newer one began meanwhile. */
    fun markReady(session: Int): Boolean = synchronized(this) {
        if (gen.get() != session) return false
        ready = true
        true
    }

    /**
     * One subscribe for [session]: true once every filter was granted, the session marked ready and
     * [onReady] called; false when a filter was refused ([subscribe] throws) or a newer session began.
     */
    fun attempt(session: Int, subscribe: () -> Unit, onReady: () -> Unit): Boolean {
        try {
            subscribe()
        } catch (e: Exception) {
            Log.w(TAG, "Hub subscribe refused or failed, trying again: ${e.message}")
            return false
        }
        if (!markReady(session)) return false
        onReady()
        return true
    }

    /**
     * Subscribes for [session] until it holds or the session is gone, then marks it ready and calls
     * [onReady] once; false when the session was replaced or lost first. [waitFirst] when the first
     * attempt was just refused.
     */
    suspend fun establish(
        session: Int, alive: () -> Boolean, subscribe: () -> Unit, onReady: () -> Unit, waitFirst: Boolean = false,
    ): Boolean {
        var wait = minMs
        if (waitFirst) {
            delay(wait)
            wait = minOf(wait * 2, maxMs)
        }
        while (gen.get() == session && alive()) {
            if (attempt(session, subscribe, onReady)) return true
            delay(wait)
            wait = minOf(wait * 2, maxMs)
        }
        return false
    }

    companion object {
        private const val TAG = "HubReporter"

        /**
         * Whether a SUBACK granted every filter: one grant per filter, none 0x80 (MQTT 3.1.1 §3.9.3).
         * Paho reports the grants in order; a missing answer counts as refused.
         */
        fun allGranted(filters: Int, grants: IntArray?): Boolean =
            grants != null && grants.size == filters && grants.all { it in 0..2 }
    }
}
