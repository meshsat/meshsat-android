package net.meshsat.android.lxmf

import net.meshsat.android.hubmsg.HubMsgIds
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.MpStr
import java.io.File

/**
 * The HeMB-NG logical message id on LXMF (contract C2, S2-R7; the Hub's fixture
 * lxmf_mid_field.json): FIELD_CUSTOM_TYPE (0xFB) = "meshsat.mid/1" and FIELD_CUSTOM_DATA (0xFC) =
 * the 16-byte mid, so a receiver drops a repeat even after a restart. A message that already
 * carries another custom type goes without it, and its receiver has no mid for it. [MESHSAT-1641]
 */
object LxMid {
    const val FIELD_CUSTOM_TYPE = 0xFBL
    const val FIELD_CUSTOM_DATA = 0xFCL
    const val TYPE = "meshsat.mid/1"

    /** A new mid for a message this phone writes: a UUIDv7, 16 raw bytes. */
    fun newMid(nowMs: Long = System.currentTimeMillis()): ByteArray =
        requireNotNull(HubMsgIds.parseCanonical(HubMsgIds.newV7(nowMs)))

    /** The fields of a message that carries [mid] and nothing else. */
    fun fields(mid: ByteArray): MpMap = withMid(MpMap(emptyList()), mid)

    /** [fields] with the mid added; unchanged when they already name another custom type. */
    fun withMid(fields: MpMap, mid: ByteArray): MpMap {
        require(mid.size == 16) { "a mid is 16 bytes" }
        if (fields[FIELD_CUSTOM_TYPE] != null || fields[FIELD_CUSTOM_DATA] != null) return fields
        return MpMap(fields.entries + listOf(MpInt(FIELD_CUSTOM_TYPE) to MpStr(TYPE), MpInt(FIELD_CUSTOM_DATA) to MpBin(mid)))
    }

    /** The mid a received message carries, or null when it carries none (or another custom type). */
    fun midOf(fields: MpMap): ByteArray? {
        val type = fields[FIELD_CUSTOM_TYPE] as? MpStr ?: return null
        if (type.value != TYPE) return null
        val data = (fields[FIELD_CUSTOM_DATA] as? MpBin)?.value ?: return null
        return data.takeIf { it.size == 16 }?.copyOf()
    }
}

/**
 * The (origin, mid) pairs this phone has taken, kept in a small file so a repeat is dropped after
 * a restart as well (C3 for LXMF). A file, not a Room table: the database falls back to a
 * destructive migration on any schema mismatch, which this does not risk. Entries older than
 * [retentionMs] are dropped when the file is rewritten.
 */
class LxMidInbox(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val retentionMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val compactAt: Int = 20_000,
) {
    private val seen = HashMap<String, Long>()
    private var lines = 0

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val p = line.split('\t')
                val at = p.getOrNull(2)?.toLongOrNull()
                if (p.size == 3 && p[1].length == 32 && at != null) {
                    seen[p[0] + "\t" + p[1]] = at
                    lines++
                }
            }
        }
        prune()
    }

    /** True the first time [origin] sends [mid]; false for a repeat, which the caller drops. */
    @Synchronized
    fun firstTime(origin: String, mid: ByteArray): Boolean {
        val key = origin + "\t" + mid.joinToString("") { "%02x".format(it) }
        val t = now()
        val at = seen[key]
        if (at != null && t - at <= retentionMs) return false
        seen[key] = t
        file.appendText("$key\t$t\n")
        if (++lines >= compactAt) prune()
        return true
    }

    @Synchronized
    private fun prune() {
        val cutoff = now() - retentionMs
        seen.entries.removeAll { it.value < cutoff }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(seen.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        tmp.renameTo(file)
        lines = seen.size
    }
}
