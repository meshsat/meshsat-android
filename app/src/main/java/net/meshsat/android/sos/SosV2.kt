package net.meshsat.android.sos

import org.json.JSONArray
import org.json.JSONObject

/**
 * An SOS under its own identity (HeMB-NG stage 2, C10, MESHSAT-1584): the contract is the Hub's
 * docs/bridge-hub-protocol.md "SOS identity (C10)", the shared cases the fixture release
 * fixtures-v1.1 (sos.json); the Bridge's internal/api/sos_legs.go is the same behaviour.
 *
 * While the Hub's retained hub/caps says sos_v2, a run keeps a sos_id (a UUIDv7 of its start), the
 * satellite frame is the 0x07, the Hub is told by the v2 event on this phone's sos/event, published
 * again until the Hub's sos/ack, and the cancel goes until its ack, or for a day, on each route that
 * carried the start: the event, and on the satellite the 0x08, once the ack has not come within
 * [CANCEL_ACK_GRACE_MS] (at once with no Hub session). The Hub pages once per run whichever routes
 * carry it, and a cancel there is final. The decisions are here, pure, for the JVM tests;
 * [SosController] acts on them.
 */
object SosV2 {
    const val CAP = "sos_v2"

    /** A start or a cancel is published again until its ack: 15 s, doubling to a minute. */
    const val REPEAT_FIRST_MS = 15_000L
    const val REPEAT_MAX_MS = 60_000L

    /** The cancel is followed for a day at most. */
    const val CANCEL_FOR_MS = 24 * 3600_000L

    /** With the Hub's session up, the satellite's 0x08 waits this long for the Hub's ack first. */
    const val CANCEL_ACK_GRACE_MS = 30_000L

    /** Whether a hub/caps payload says sos_v2; an empty one (the retained message cleared) does not. */
    fun capsSayV2(payload: String): Boolean = try {
        if (payload.isBlank()) false else {
            val o = JSONObject(payload)
            val caps = o.optJSONArray("caps") ?: JSONArray()
            o.optInt("v") == 1 && (0 until caps.length()).any { caps.optString(it) == CAP }
        }
    } catch (_: Exception) {
        false
    }

    /** The Hub's ack: it has the start or the cancel; [state] is the run's after it. */
    data class Ack(val sosId: String, val kind: String, val state: String, val rev: Int)

    fun parseAck(payload: String): Ack? = try {
        val o = JSONObject(payload)
        val kind = o.optString("kind")
        val id = o.optString("sos_id")
        if (o.optInt("v") != 2 || id.isEmpty() || (kind != "start" && kind != "cancel")) null
        else Ack(id, kind, o.optString("state"), o.optInt("rev"))
    } catch (_: Exception) {
        null
    }

    /** The run's start as the v2 event: run_start_ms always, the time the run's start in seconds. */
    fun startEvent(r: SosRun, text: String): JSONObject = JSONObject().apply {
        put("v", 2)
        put("kind", "start")
        put("sos_id", r.sosId)
        put("run_start_ms", r.id)
        if (r.deviceId.isNotEmpty()) put("device_id", r.deviceId)
        put("text", text)
        r.fix?.let { put("lat", it.lat); put("lon", it.lon) }
        put("timestamp", r.id / 1000)
    }

    /** The run's cancel as the v2 event, reason 0 (the person), at the time it was cancelled. */
    fun cancelEvent(r: SosRun): JSONObject = JSONObject().apply {
        put("v", 2)
        put("kind", "cancel")
        put("sos_id", r.sosId)
        put("run_start_ms", r.id)
        put("reason", SosMessages.CANCEL_OPERATOR.toInt())
        put("timestamp", (r.cancelledAt ?: r.id) / 1000)
    }

    /** The wait before the next publish after [published] publishes without an ack. */
    fun repeatAfter(published: Int): Long {
        var d = REPEAT_FIRST_MS
        repeat((published - 1).coerceAtLeast(0)) { d = (2 * d).coerceAtMost(REPEAT_MAX_MS) }
        return d
    }

    /** The hub leg of [r] goes as the v2 event now: a real SOS with its id, and the Hub says sos_v2. */
    fun hubLegV2(r: SosRun, capsV2: Boolean): Boolean = !r.test && r.sosId.isNotEmpty() && capsV2

    /** The cancel under the run's identity is still owed: not acked, not given up. */
    fun cancelPending(r: SosRun): Boolean =
        r.cancelV2Owed && r.cancelledAt != null && r.cancelHubAckedAt == null && !r.cancelGaveUp

    /** A day has passed since the cancel: it is no longer repeated. */
    fun cancelOver(r: SosRun, nowMs: Long): Boolean =
        r.cancelledAt != null && nowMs - r.cancelledAt >= CANCEL_FOR_MS

    /** The satellite's 0x08 is due: its start went as the 0x07, and the ack has not come in the grace. */
    fun cancelFrameDue(r: SosRun, nowMs: Long, hubUp: Boolean, satStartWent: Boolean): Boolean {
        val at = r.cancelledAt ?: return false
        return cancelPending(r) && r.satV2 && satStartWent && !r.cancelFrameDone && (!hubUp || nowMs - at >= CANCEL_ACK_GRACE_MS)
    }
}
