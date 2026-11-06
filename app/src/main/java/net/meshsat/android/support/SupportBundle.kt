package net.meshsat.android.support

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The debug.zip a user can attach to feedback (MESHSAT-1806): a few plain-text files, each
 * passed through [SupportRedactor] on the way in. Pure JVM; the Android side that gathers the
 * texts is [SupportReport].
 */
object SupportBundle {

    /** One file in the zip. */
    data class Part(val name: String, val text: String)

    /** Where the desk's mail goes: the MeshSat Support desk's mailbox. */
    const val SUPPORT_ADDRESS = "support@meshsat.net"

    /** The ceiling for any one text, so a runaway log cannot make an attachment mail refuses. */
    const val MAX_PART_CHARS = 4_000_000

    private val nameTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun fileName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "meshsat-debug-${nameTime.format(Instant.ofEpochMilli(nowMs).atZone(zone))}.zip"

    /**
     * Writes [parts] as a zip, every text redacted and cut to [MAX_PART_CHARS] (the newest end
     * kept: a log's last lines are the ones that matter). Entry times are [nowMs].
     */
    fun write(parts: List<Part>, out: OutputStream, nowMs: Long) {
        ZipOutputStream(out).use { zip ->
            for (part in parts) {
                val entry = ZipEntry(part.name)
                entry.time = nowMs
                zip.putNextEntry(entry)
                zip.write(SupportRedactor.redact(cut(part.text)).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun bytes(parts: List<Part>, nowMs: Long): ByteArray =
        ByteArrayOutputStream().also { write(parts, it, nowMs) }.toByteArray()

    private fun cut(text: String): String =
        if (text.length <= MAX_PART_CHARS) text
        else "[the first ${text.length - MAX_PART_CHARS} characters were cut]\n" + text.takeLast(MAX_PART_CHARS)

    /**
     * The mail body under the user's own words: enough for the desk to know the version and the
     * phone even when no zip is attached. No identifier of the phone or its owner.
     */
    fun mailBody(userText: String, versionName: String, versionCode: Int, edition: String, android: String, device: String, attached: Boolean): String =
        buildString {
            append(userText.trim())
            append("\n\n--\n")
            append("MeshSat Android $versionName ($versionCode), $edition\n")
            append("Android $android, $device\n")
            if (attached) append("A debug.zip with the app's logs is attached.\n")
        }

    /** Subject line by topic, so the desk can sort without opening the mail. */
    fun subject(topic: Topic, versionName: String): String = "MeshSat Android $versionName: ${topic.subject}"

    enum class Topic(val label: String, val subject: String) {
        Problem("Something is wrong", "a problem"),
        Idea("An idea", "an idea"),
        Question("A question", "a question"),
    }
}
