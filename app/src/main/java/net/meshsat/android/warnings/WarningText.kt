package net.meshsat.android.warnings

/**
 * What a MeshSat kit puts on the mesh for a public warning, read back (MESHSAT-1734).
 *
 * The Bridge (`internal/warnings/relay.go`, `MeshText`) sends each verified warning twice: on
 * ALERT_APP (port 11) with a BEL at the end, so a node with its alert setting on buzzes, and as a
 * plain text on the primary channel. Both carry the same words, two lines:
 *
 *     [TEST ][UPDATE: ]WARNING <event> <severity>[ until HH:MM UTC][: <text>]
 *     Relay of <source> warning, issued <d Mon HH:MM> UTC via MeshSat, not an official NL-Alert
 *
 * and a Cancel is
 *
 *     [TEST ]CANCELLED: <event>[ - <text>]
 *     Relay of ...
 *
 * The first line may be cut to fit the mesh packet (200 bytes); the label is kept whole. Events are
 * one word (Wind, Rain, Snow/ice, Thunderstorm, Fog, Heat, Cold, Coastal, Flood, Avalanche,
 * Wildfire, Tornado, Other); severities Unknown, Minor, Moderate, Severe, Extreme.
 *
 * What counts as a warning here, and so never as a chat:
 * - anything on port 11, parsed when it has this shape, otherwise kept whole as an alert;
 * - a port-1 text with this shape **and** the kit's relay line. A person typing "WARNING ..." in
 *   a chat has no "Relay of" line and stays a chat.
 */
object WarningText {

    /** Meshtastic ALERT_APP. */
    const val PORT_ALERT = 11

    /** Meshtastic TEXT_MESSAGE_APP. */
    const val PORT_TEXT = 1

    /** How the kit's second line starts: its mark on a relayed text. */
    const val LABEL_PREFIX = "Relay of "

    private const val BEL = '\u0007'

    val SEVERITIES = listOf("Unknown", "Minor", "Moderate", "Severe", "Extreme")

    enum class Kind(val wire: String) {
        ALERT("alert"), UPDATE("update"), CANCEL("cancel");

        companion object {
            fun of(wire: String): Kind = entries.firstOrNull { it.wire == wire } ?: ALERT
        }
    }

    data class Parsed(
        val kind: Kind,
        val test: Boolean,
        val event: String,
        /** One of [SEVERITIES], or "" when the line carried none (a cut line, a cancel). */
        val severity: String,
        /** "HH:MM" UTC, or "". */
        val until: String,
        val text: String,
        /** The kit's relay line, or "" (a port-11 alert from something else). */
        val label: String,
        /** The first line as it came, without the BEL. */
        val headline: String,
    )

    /** The text without the BEL and trailing whitespace. */
    fun clean(raw: String): String = raw.trimEnd(BEL, ' ', '\n', '\r', '\t')

    /**
     * The warning on [port], or null when the text is not one: a port-1 text needs the shape and
     * the relay line; a port-11 text with another shape is still an alert, kept whole.
     */
    fun recognise(raw: String, port: Int): Parsed? = when (port) {
        PORT_ALERT -> parse(raw) ?: fallbackAlert(raw)
        PORT_TEXT -> parse(raw)?.takeIf { it.label.startsWith(LABEL_PREFIX) }
        else -> null
    }

    /** True when a port-1 text is a relayed warning and not a chat. */
    fun isRelayedText(raw: String): Boolean = recognise(raw, PORT_TEXT) != null

    /** The text in the kit's shape, or null. */
    fun parse(raw: String): Parsed? {
        val text = clean(raw)
        if (text.isBlank()) return null
        val nl = text.indexOf('\n')
        val headline = (if (nl < 0) text else text.substring(0, nl)).trim()
        val label = if (nl < 0) "" else text.substring(nl + 1).trim()
        var rest = headline
        val test = rest.startsWith("TEST ")
        if (test) rest = rest.removePrefix("TEST ")
        return when {
            rest.startsWith("CANCELLED: ") -> {
                val body = rest.removePrefix("CANCELLED: ")
                val sep = body.indexOf(" - ")
                val event = (if (sep < 0) body else body.substring(0, sep)).trim()
                if (event.isEmpty()) return null
                Parsed(Kind.CANCEL, test, event, "", "", if (sep < 0) "" else body.substring(sep + 3).trim(), label, headline)
            }
            rest.startsWith("UPDATE: WARNING ") -> warning(Kind.UPDATE, test, rest.removePrefix("UPDATE: WARNING "), label, headline)
            rest.startsWith("WARNING ") -> warning(Kind.ALERT, test, rest.removePrefix("WARNING "), label, headline)
            else -> null
        }
    }

    private val head = Regex("""^(\S+)(?: (\S+))?(?: until (\d{1,2}:\d{2}) UTC)?$""")

    private fun warning(kind: Kind, test: Boolean, body: String, label: String, headline: String): Parsed? {
        val colon = body.indexOf(": ")
        val lead = (if (colon < 0) body else body.substring(0, colon)).trim()
        val text = if (colon < 0) "" else body.substring(colon + 2).trim()
        if (lead.isEmpty()) return null
        val m = head.matchEntire(lead)
        if (m != null) {
            val sev = m.groupValues[2]
            return Parsed(kind, test, m.groupValues[1], if (sev in SEVERITIES) sev else "", m.groupValues[3], text, label, headline)
        }
        // A line cut mid-way ("WARNING Wind Severe until 10:1"): the event and the severity still read.
        val words = lead.split(' ').filter { it.isNotEmpty() }
        val sev = words.getOrNull(1).takeIf { it in SEVERITIES } ?: ""
        return Parsed(kind, test, words[0], sev, "", text, label, headline)
    }

    private fun fallbackAlert(raw: String): Parsed? {
        val text = clean(raw)
        if (text.isBlank()) return null
        return Parsed(Kind.ALERT, false, "Alert", "", "", text, "", text.lineSequence().first().trim())
    }

    /** The words that identify one relayed text wherever it came from: the two lines, spaces folded. */
    fun dedupKey(raw: String): String = clean(raw).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** "WARNING", "UPDATE", "CANCELLED", with "TEST" in front of a test. */
    fun kindLabel(kind: Kind, test: Boolean): String =
        (if (test) "TEST " else "") + when (kind) {
            Kind.ALERT -> "WARNING"
            Kind.UPDATE -> "UPDATE"
            Kind.CANCEL -> "CANCELLED"
        }

    /** "Wind Severe until 10:11 UTC", the parts that are there. */
    fun summary(event: String, severity: String, until: String): String =
        listOf(event, severity, if (until.isEmpty()) "" else "until $until UTC").filter { it.isNotEmpty() }.joinToString(" ")
}
