package net.meshsat.android.support

/**
 * Blanks what a debug report must not carry before it leaves the phone (MESHSAT-1806): keys and
 * secrets, email addresses, phone numbers, IMEIs, and all but the last byte of a Bluetooth or
 * Wi-Fi address. What stays readable is what support needs to follow a fault: times, states,
 * interface names, error texts, node ids.
 *
 * Pure Kotlin so the JVM tests can drive it. It is a safety net, not a licence to log private
 * data: the app's own log lines are meant to carry none of this in the first place.
 */
object SupportRedactor {

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // PEM blocks first, before the base64 inside them can look like anything else.
        Regex("-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----") to { _ -> "[key removed]" },
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+") to { _ -> "Bearer [removed]" },
        // name=value or "name": "value" for the names a secret goes by.
        Regex("(?i)\\b(password|passwd|token|secret|api[_-]?key|psk)(\"?\\s*[:=]\\s*\"?)([^\\s\"',;&}]+)") to
            { m -> "${m.groupValues[1]}${m.groupValues[2]}[removed]" },
        // Starts only where a run of address characters starts, and never backtracks into it: a
        // long base64 line would otherwise be rescanned from every one of its characters.
        Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}") to { _ -> "[email]" },
        // International numbers: + then 7 to 15 digits, single spaces or dashes allowed between them.
        Regex("(?<![\\w+])\\+\\d(?:[ -]?\\d){6,14}(?!\\d)") to { _ -> "[phone]" },
        // National numbers written with the leading 0 (ten digits); unix seconds never start with 0.
        Regex("(?<![\\w.])0\\d{9}(?![\\w])") to { _ -> "[phone]" },
        // An IMEI is exactly 15 digits; millisecond timestamps have 13.
        Regex("(?<![\\w.])\\d{15}(?![\\w.])") to { _ -> "[imei]" },
        // MAC addresses keep their last byte, enough to tell two devices apart in one report.
        Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}([0-9a-f]{2})\\b") to { m -> "XX:XX:XX:XX:XX:${m.groupValues[1]}" },
    )

    fun redact(text: String): String {
        var out = text
        for ((regex, replace) in rules) out = regex.replace(out, replace)
        return out
    }
}
