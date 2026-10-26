package net.meshsat.android.tak

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds CoT v2.0 events matching MeshSat Bridge wire format exactly.
 *
 * Callsign format: "{prefix}-{suffix}" where prefix defaults to "MESHSAT"
 * and suffix is the last 4 chars of the device ID.
 */
object CotBuilder {

    private const val COT_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    private const val DEFAULT_STALE_SEC = 300

    /** A contact reached through its server: what lets a TAK client offer chat with it (MESHSAT-1707). */
    const val TAK_ENDPOINT = "*:-1:stcp"

    /** ATAK's name and id for the room every contact is in. */
    const val CHAT_ROOM = "All Chat Rooms"

    /** A GeoChat lives a day, as ATAK writes it. */
    private const val CHAT_STALE_SEC = 86_400

    private fun nowUtc(): Date = Date()

    private fun formatTime(date: Date): String {
        val sdf = SimpleDateFormat(COT_TIME_FORMAT, Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }

    private fun staleTime(date: Date, staleSec: Int): String {
        return formatTime(Date(date.time + staleSec * 1000L))
    }

    /** Generate a callsign from device ID: "MESHSAT-{last4}". */
    fun callsign(deviceId: String, prefix: String = "MESHSAT"): String {
        val suffix = deviceId.takeLast(4).uppercase()
        return if (suffix.isNotEmpty()) "$prefix-$suffix" else prefix
    }

    /**
     * Position Location Information (PLI) event.
     * Type: a-f-G-U-C (friendly ground unit civilian)
     */
    fun position(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        alt: Double = 0.0,
        course: Double = 0.0,
        speed: Double = 0.0,
        battery: String = "",
        staleSec: Int = DEFAULT_STALE_SEC,
    ): CotEvent {
        val now = nowUtc()
        val ts = formatTime(now)
        return CotEvent(
            uid = uid,
            type = CotType.POSITION,
            how = CotHow.GPS,
            time = ts,
            start = ts,
            stale = staleTime(now, staleSec),
            point = CotPoint(lat = lat, lon = lon, hae = alt, ce = 10.0, le = 10.0),
            detail = CotDetail(
                contact = CotContact(callsign = callsign, endpoint = TAK_ENDPOINT),
                group = CotGroup(),
                precision = CotPrecision(),
                track = CotTrack(course = course, speed = speed),
                status = if (battery.isNotEmpty()) CotStatus(battery = battery) else null,
            ),
        )
    }

    /**
     * SOS/emergency event — extends position with emergency element.
     * Type: a-f-G-U-C with emergency detail.
     */
    fun sos(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        alt: Double = 0.0,
        reason: String = "SOS",
        staleSec: Int = DEFAULT_STALE_SEC,
    ): CotEvent {
        val ev = position(uid, callsign, lat, lon, alt, staleSec = staleSec)
        return ev.copy(
            detail = ev.detail?.copy(
                emergency = CotEmergency(type = "911 Alert", text = reason),
                remarks = CotRemarks(source = "MeshSat", text = "Emergency: $reason"),
            ),
        )
    }

    /**
     * Dead man's switch timeout alarm event.
     * Type: b-a (alarm).
     */
    fun deadman(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        timeoutSec: Int,
        staleSec: Int = DEFAULT_STALE_SEC,
    ): CotEvent {
        val now = nowUtc()
        val ts = formatTime(now)
        return CotEvent(
            uid = "$uid-DEADMAN",
            type = CotType.ALARM,
            how = CotHow.HUMAN_ENTERED,
            time = ts,
            start = ts,
            stale = staleTime(now, staleSec),
            point = CotPoint(lat = lat, lon = lon, hae = 0.0, ce = 100.0, le = 100.0),
            detail = CotDetail(
                contact = CotContact(callsign = callsign),
                remarks = CotRemarks(
                    source = "MeshSat",
                    text = "Dead man's switch timeout — no check-in for ${timeoutSec}s",
                ),
            ),
        )
    }

    /**
     * GeoChat/freetext message event.
     * Type: b-t-f (chat).
     */
    fun chat(
        uid: String,
        callsign: String,
        text: String,
        staleSec: Int = DEFAULT_STALE_SEC,
    ): CotEvent {
        val now = nowUtc()
        val ts = formatTime(now)
        return CotEvent(
            uid = "$uid-CHAT-${java.lang.Long.toString(now.time, 36)}",
            type = CotType.CHAT,
            how = CotHow.HUMAN_GEOCHAT,
            time = ts,
            start = ts,
            stale = staleTime(now, staleSec),
            point = CotPoint(lat = 0.0, lon = 0.0, hae = 0.0, ce = 9999999.0, le = 9999999.0),
            detail = CotDetail(
                contact = CotContact(callsign = callsign),
                remarks = CotRemarks(source = callsign, text = text),
            ),
        )
    }

    /**
     * A GeoChat from one contact to another in the form ATAK writes it (MESHSAT-1707): __chat and
     * chatgrp name both ends, link the sender, remarks the words and the recipient, and a dest asks
     * the server to deliver it to the recipient only. Same shape as the Bridge's BuildGeoChat.
     */
    fun geoChat(
        fromUid: String,
        fromCallsign: String,
        toUid: String,
        toCallsign: String,
        text: String,
        messageId: String = java.util.UUID.randomUUID().toString(),
    ): CotEvent {
        val now = nowUtc()
        val ts = formatTime(now)
        return CotEvent(
            uid = "GeoChat.$fromUid.$toUid.$messageId",
            type = CotType.CHAT,
            how = CotHow.HUMAN_GEOCHAT,
            time = ts,
            start = ts,
            stale = staleTime(now, CHAT_STALE_SEC),
            point = CotPoint(lat = 0.0, lon = 0.0, hae = 9999999.0, ce = 9999999.0, le = 9999999.0),
            detail = CotDetail(
                chat = CotChat(
                    id = toUid, chatroom = toCallsign, senderCallsign = fromCallsign, messageId = messageId,
                    uid0 = fromUid, uid1 = toUid,
                ),
                link = CotLink(uid = fromUid, type = CotType.POSITION, relation = "p-p"),
                remarks = CotRemarks(source = "BAO.F.ATAK.$fromUid", text = text, to = toUid, time = ts),
                marti = listOf(CotDest(uid = toUid, callsign = toCallsign)),
            ),
        )
    }

    /**
     * Sensor/telemetry data event.
     * Type: t-x-d-d (sensor data).
     */
    fun telemetry(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        data: String,
        staleSec: Int = DEFAULT_STALE_SEC,
    ): CotEvent {
        val now = nowUtc()
        val ts = formatTime(now)
        return CotEvent(
            uid = "$uid-SENSOR",
            type = CotType.SENSOR,
            how = CotHow.GPS,
            time = ts,
            start = ts,
            stale = staleTime(now, staleSec),
            point = CotPoint(lat = lat, lon = lon, hae = 0.0, ce = 50.0, le = 50.0),
            detail = CotDetail(
                contact = CotContact(callsign = "$callsign-SENSOR"),
                remarks = CotRemarks(source = "MeshSat", text = data),
            ),
        )
    }

    /** Build a waypoint/marker CoT event. */
    fun waypoint(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        name: String,
        description: String = "",
        staleSec: Int = DEFAULT_STALE_SEC,
    ): CotEvent {
        val now = nowUtc()
        val ts = formatTime(now)
        return CotEvent(
            type = CotType.WAYPOINT,
            uid = "$uid-WP-${System.currentTimeMillis().toString(36)}",
            how = CotHow.HUMAN_ENTERED,
            time = ts,
            start = ts,
            stale = staleTime(now, staleSec),
            point = CotPoint(lat = lat, lon = lon, hae = 0.0, ce = 10.0, le = 10.0),
            detail = CotDetail(
                contact = CotContact(callsign = name),
                remarks = CotRemarks(source = callsign, text = description),
            ),
        )
    }

    /** Build an enriched position with real GPS quality data. */
    fun enrichedPosition(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        alt: Double = 0.0,
        course: Double = 0.0,
        speed: Double = 0.0,
        battery: String = "",
        hdop: Double = 0.0,
        pdop: Double = 0.0,
        staleSec: Int = DEFAULT_STALE_SEC,
    ): CotEvent {
        val ev = position(uid, callsign, lat, lon, alt, course, speed, battery, staleSec)
        // CE from HDOP (CE ~ HDOP * 5)
        val ce = if (hdop > 0) hdop * 5.0 else if (pdop > 0) pdop * 3.0 else 10.0
        val le = if (pdop > 0) pdop * 4.0 else 10.0
        return ev.copy(
            point = ev.point.copy(ce = ce, le = le),
        )
    }
}
