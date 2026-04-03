package com.cubeos.meshsat.tak

import com.cubeos.meshsat.takproto.CotEvent as PbCotEvent
import com.cubeos.meshsat.takproto.Detail as PbDetail
import com.cubeos.meshsat.takproto.TakMessage as PbTakMessage
import com.cubeos.meshsat.takproto.Contact as PbContact
import com.cubeos.meshsat.takproto.Group as PbGroup
import com.cubeos.meshsat.takproto.Track as PbTrack
import com.cubeos.meshsat.takproto.Status as PbStatus
import com.cubeos.meshsat.takproto.Takv as PbTakv
import com.cubeos.meshsat.takproto.PrecisionLocation as PbPrecision
import java.text.SimpleDateFormat
import java.util.*

/**
 * TAK Protocol v1 (protobuf) conversion utilities.
 *
 * Converts between CotEvent (XML-compatible data class) and TAK protobuf TakMessage.
 * Wire framing: 0xBF <varint length> <protobuf> for TCP stream,
 *               0xBF 0x01 0xBF <protobuf> for UDP multicast.
 */
object TakProto {

    private const val MAGIC: Byte = 0xBF.toByte()
    private const val VERSION: Byte = 0x01

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Convert a CotEvent to TAK Protocol v1 TakMessage protobuf bytes (no framing). */
    fun cotEventToProto(ev: CotEvent): ByteArray {
        val sendTime = parseTime(ev.time)
        val startTime = parseTime(ev.start)
        val staleTime = parseTime(ev.stale)

        val cotBuilder = PbCotEvent.newBuilder()
            .setType(ev.type)
            .setUid(ev.uid)
            .setHow(ev.how)
            .setSendTime(sendTime)
            .setStartTime(startTime)
            .setStaleTime(staleTime)
            .setLat(ev.point.lat)
            .setLon(ev.point.lon)
            .setHae(ev.point.hae)
            .setCe(ev.point.ce)
            .setLe(ev.point.le)

        val detailBuilder = PbDetail.newBuilder()
        ev.detail?.contact?.let {
            detailBuilder.setContact(PbContact.newBuilder().setCallsign(it.callsign))
        }
        ev.detail?.group?.let {
            detailBuilder.setGroup(PbGroup.newBuilder().setName(it.name).setRole(it.role))
        }
        ev.detail?.precision?.let {
            detailBuilder.setPrecisionLocation(PbPrecision.newBuilder().setGeopointsrc(it.geopointsrc).setAltsrc(it.altsrc))
        }
        ev.detail?.track?.let {
            detailBuilder.setTrack(PbTrack.newBuilder().setSpeed(it.speed).setCourse(it.course))
        }
        ev.detail?.status?.let {
            val bat = it.battery.toIntOrNull() ?: 0
            if (bat > 0) detailBuilder.setStatus(PbStatus.newBuilder().setBattery(bat))
        }

        // Carry emergency/remarks as xmlDetail
        val xmlExtra = buildString {
            ev.detail?.emergency?.let { append("""<emergency type="${it.type}">${it.text}</emergency>""") }
            ev.detail?.remarks?.let { append("""<remarks source="${it.source}">${it.text}</remarks>""") }
        }
        if (xmlExtra.isNotEmpty()) detailBuilder.xmlDetail = xmlExtra

        cotBuilder.setDetail(detailBuilder)

        val takMsg = PbTakMessage.newBuilder()
            .setCotEvent(cotBuilder)
            .build()

        return takMsg.toByteArray()
    }

    /** Frame protobuf payload for TCP stream: 0xBF <varint length> <payload>. */
    fun frameForStream(payload: ByteArray): ByteArray {
        val varint = encodeVarint(payload.size.toLong())
        return byteArrayOf(MAGIC) + varint + payload
    }

    /** Frame protobuf payload for UDP multicast: 0xBF 0x01 0xBF <payload>. */
    fun frameForMulticast(payload: ByteArray): ByteArray {
        return byteArrayOf(MAGIC, VERSION, MAGIC) + payload
    }

    /** Parse a TAK protobuf TakMessage back to CotEvent. */
    fun protoToCotEvent(data: ByteArray): CotEvent? {
        return try {
            val msg = PbTakMessage.parseFrom(data)
            val c = msg.cotEvent ?: return null
            val d = c.detail

            val contact = if (d != null && d.contact != null && d.contact.callsign.isNotEmpty()) CotContact(d.contact.callsign) else null
            val group = if (d != null && d.group != null && d.group.name.isNotEmpty()) CotGroup(d.group.name, d.group.role) else null
            val pl = d?.precisionLocation
            val precision = if (pl != null && pl.getGeopointsrc().isNotEmpty()) CotPrecision(pl.getGeopointsrc(), pl.getAltsrc()) else null
            val track = if (d != null && d.track != null && (d.track.speed != 0.0 || d.track.course != 0.0)) CotTrack(d.track.speed, d.track.course) else null
            val status = if (d != null && d.status != null && d.status.battery > 0) CotStatus(d.status.battery.toString()) else null

            CotEvent(
                type = c.type,
                uid = c.uid,
                how = c.how,
                time = formatTime(c.sendTime),
                start = formatTime(c.startTime),
                stale = formatTime(c.staleTime),
                point = CotPoint(
                    lat = c.lat,
                    lon = c.lon,
                    hae = c.hae,
                    ce = c.ce,
                    le = c.le,
                ),
                detail = CotDetail(
                    contact = contact,
                    group = group,
                    precision = precision,
                    track = track,
                    status = status,
                ),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTime(iso: String): Long {
        return try { dateFormat.parse(iso)?.time ?: 0L } catch (_: Exception) { 0L }
    }

    private fun formatTime(millis: Long): String {
        return if (millis > 0) dateFormat.format(Date(millis)) else ""
    }

    private fun encodeVarint(value: Long): ByteArray {
        var v = value
        val result = mutableListOf<Byte>()
        while (v > 0x7F) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        result.add((v and 0x7F).toByte())
        return result.toByteArray()
    }
}
