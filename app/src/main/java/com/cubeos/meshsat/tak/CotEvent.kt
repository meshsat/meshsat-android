package com.cubeos.meshsat.tak

/**
 * CoT (Cursor on Target) v2.0 XML data model.
 * Wire-compatible with MeshSat Bridge (tak_cot.go) and Hub.
 *
 * XML structure:
 * ```xml
 * <event version="2.0" uid="..." type="..." how="..." time="..." start="..." stale="...">
 *   <point lat="..." lon="..." hae="..." ce="..." le="..."/>
 *   <detail>
 *     <contact callsign="..."/>
 *     <__group name="..." role="..."/>
 *     <precisionlocation altsrc="..." geopointsrc="..."/>
 *     <track course="..." speed="..."/>
 *     <status battery="..."/>
 *     <emergency type="...">...</emergency>
 *     <remarks source="...">...</remarks>
 *   </detail>
 * </event>
 * ```
 */
data class CotEvent(
    val version: String = "2.0",
    val uid: String,
    val type: String,
    val how: String,
    val time: String,
    val start: String,
    val stale: String,
    val point: CotPoint,
    val detail: CotDetail? = null,
)

data class CotPoint(
    val lat: Double,
    val lon: Double,
    val hae: Double = 0.0,
    val ce: Double = 10.0,
    val le: Double = 10.0,
)

data class CotDetail(
    val contact: CotContact? = null,
    val group: CotGroup? = null,
    val precision: CotPrecision? = null,
    val track: CotTrack? = null,
    val status: CotStatus? = null,
    val emergency: CotEmergency? = null,
    val remarks: CotRemarks? = null,
)

data class CotContact(val callsign: String)

data class CotGroup(val name: String = "Cyan", val role: String = "Team Member")

data class CotPrecision(val altSrc: String = "GPS", val geoPointSrc: String = "GPS")

data class CotTrack(val course: Double = 0.0, val speed: Double = 0.0)

data class CotStatus(val battery: String = "")

data class CotEmergency(val type: String, val text: String)

data class CotRemarks(val source: String = "", val text: String)

/** CoT event type constants — must match Bridge/Hub exactly. */
object CotType {
    /** Friendly ground unit position (PLI). */
    const val POSITION = "a-f-G-U-C"
    /** Sensor/telemetry data. */
    const val SENSOR = "t-x-d-d"
    /** Alarm event (dead man's switch, emergency). */
    const val ALARM = "b-a"
    /** GeoChat/freetext message. */
    const val CHAT = "b-t-f"
    /** Waypoint/map marker. */
    const val WAYPOINT = "b-m-p-s-p-loc"
    /** Circle drawing (geofence). */
    const val CIRCLE = "u-d-c-c"
}

/** CoT "how" field constants. */
object CotHow {
    const val GPS = "m-g"
    const val HUMAN_ENTERED = "h-e"
    const val HUMAN_GEOCHAT = "h-g-i-g-o"
}
