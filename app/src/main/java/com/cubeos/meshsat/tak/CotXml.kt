package com.cubeos.meshsat.tak

/**
 * CoT XML serializer/deserializer.
 * Produces wire-compatible XML matching Bridge's xml.Marshal output.
 */
object CotXml {

    /** Serialize a CotEvent to CoT v2.0 XML string. */
    fun marshal(ev: CotEvent): String {
        val sb = StringBuilder()
        sb.append("<event")
        sb.attr("version", ev.version)
        sb.attr("uid", ev.uid)
        sb.attr("type", ev.type)
        sb.attr("how", ev.how)
        sb.attr("time", ev.time)
        sb.attr("start", ev.start)
        sb.attr("stale", ev.stale)
        sb.append(">")

        sb.append("<point")
        sb.attr("lat", ev.point.lat.toString())
        sb.attr("lon", ev.point.lon.toString())
        sb.attr("hae", ev.point.hae.toString())
        sb.attr("ce", ev.point.ce.toString())
        sb.attr("le", ev.point.le.toString())
        sb.append("></point>")

        val d = ev.detail
        if (d != null) {
            sb.append("<detail>")

            d.contact?.let {
                sb.append("<contact")
                sb.attr("callsign", it.callsign)
                sb.append("></contact>")
            }

            d.group?.let {
                sb.append("<__group")
                sb.attr("name", it.name)
                sb.attr("role", it.role)
                sb.append("></__group>")
            }

            d.precision?.let {
                sb.append("<precisionlocation")
                sb.attr("altsrc", it.altSrc)
                sb.attr("geopointsrc", it.geoPointSrc)
                sb.append("></precisionlocation>")
            }

            d.track?.let {
                sb.append("<track")
                sb.attr("course", it.course.toString())
                sb.attr("speed", it.speed.toString())
                sb.append("></track>")
            }

            d.status?.let {
                if (it.battery.isNotEmpty()) {
                    sb.append("<status")
                    sb.attr("battery", it.battery)
                    sb.append("></status>")
                }
            }

            d.emergency?.let {
                sb.append("<emergency")
                sb.attr("type", it.type)
                sb.append(">")
                sb.append(escapeXml(it.text))
                sb.append("</emergency>")
            }

            d.remarks?.let {
                sb.append("<remarks")
                if (it.source.isNotEmpty()) sb.attr("source", it.source)
                sb.append(">")
                sb.append(escapeXml(it.text))
                sb.append("</remarks>")
            }

            sb.append("</detail>")
        }

        sb.append("</event>")
        return sb.toString()
    }

    /** Parse CoT XML into a CotEvent. Returns null on parse failure. */
    fun parse(xml: String): CotEvent? {
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xml.byteInputStream())
            val root = doc.documentElement

            val point = root.getElementsByTagName("point").item(0)
            val detail = root.getElementsByTagName("detail").item(0)

            CotEvent(
                version = root.getAttribute("version"),
                uid = root.getAttribute("uid"),
                type = root.getAttribute("type"),
                how = root.getAttribute("how"),
                time = root.getAttribute("time"),
                start = root.getAttribute("start"),
                stale = root.getAttribute("stale"),
                point = CotPoint(
                    lat = point?.attr("lat")?.toDoubleOrNull() ?: 0.0,
                    lon = point?.attr("lon")?.toDoubleOrNull() ?: 0.0,
                    hae = point?.attr("hae")?.toDoubleOrNull() ?: 0.0,
                    ce = point?.attr("ce")?.toDoubleOrNull() ?: 10.0,
                    le = point?.attr("le")?.toDoubleOrNull() ?: 10.0,
                ),
                detail = if (detail != null) parseDetail(detail) else null,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDetail(node: org.w3c.dom.Node): CotDetail {
        var contact: CotContact? = null
        var group: CotGroup? = null
        var precision: CotPrecision? = null
        var track: CotTrack? = null
        var status: CotStatus? = null
        var emergency: CotEmergency? = null
        var remarks: CotRemarks? = null

        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) ?: continue
            when (child.nodeName) {
                "contact" -> contact = CotContact(child.attr("callsign") ?: "")
                "__group" -> group = CotGroup(
                    name = child.attr("name") ?: "Cyan",
                    role = child.attr("role") ?: "Team Member",
                )
                "precisionlocation" -> precision = CotPrecision(
                    altSrc = child.attr("altsrc") ?: "GPS",
                    geoPointSrc = child.attr("geopointsrc") ?: "GPS",
                )
                "track" -> track = CotTrack(
                    course = child.attr("course")?.toDoubleOrNull() ?: 0.0,
                    speed = child.attr("speed")?.toDoubleOrNull() ?: 0.0,
                )
                "status" -> status = CotStatus(battery = child.attr("battery") ?: "")
                "emergency" -> emergency = CotEmergency(
                    type = child.attr("type") ?: "",
                    text = child.textContent ?: "",
                )
                "remarks" -> remarks = CotRemarks(
                    source = child.attr("source") ?: "",
                    text = child.textContent ?: "",
                )
            }
        }

        return CotDetail(contact, group, precision, track, status, emergency, remarks)
    }

    private fun org.w3c.dom.Node.attr(name: String): String? {
        return attributes?.getNamedItem(name)?.nodeValue
    }

    private fun StringBuilder.attr(name: String, value: String) {
        append(" ").append(name).append("=\"").append(escapeXml(value)).append("\"")
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
