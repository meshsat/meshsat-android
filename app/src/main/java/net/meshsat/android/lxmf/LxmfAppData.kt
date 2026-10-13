package net.meshsat.android.lxmf

import net.meshsat.android.rns.MpArray
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.MpNil
import net.meshsat.android.rns.MpStr
import net.meshsat.android.rns.Msgpack
import net.meshsat.android.rns.asLong

/**
 * The lxmf.delivery announce app data (LXMRouter.get_announce_app_data and the
 * display_name / stamp_cost / compression_support_from_app_data readers):
 * msgpack([display name | nil, stamp cost | nil, [supported functionality]]).
 * [MESHSAT-1553]
 */
object LxmfAppData {
    /** LXMF.SF_COMPRESSION: the peer accepts bzip2-compressed resources. We do not announce it (no bzip2 on the phone). */
    const val SF_COMPRESSION = 0L

    fun encode(displayName: String?, stampCost: Int, compression: Boolean = false): ByteArray {
        val name = if (displayName.isNullOrEmpty()) MpNil else MpBin(displayName.toByteArray(Charsets.UTF_8))
        val cost = if (stampCost in 1..254) MpInt(stampCost.toLong()) else MpNil
        val sf = if (compression) MpArray(MpInt(SF_COMPRESSION)) else MpArray(emptyList())
        return Msgpack.pack(MpArray(name, cost, sf))
    }

    /** What a peer's announce app data says. */
    data class Info(
        val displayName: String? = null,
        val stampCost: Int = 0,          // 0 = none
        val compression: Boolean = true, // the peer accepts compressed resources
        val legacy: Boolean = false,     // pre-0.5.0 raw UTF-8 name
    )

    fun parse(appData: ByteArray?): Info {
        if (appData == null || appData.isEmpty()) return Info()
        val b0 = appData[0].toInt() and 0xFF
        if ((b0 in 0x90..0x9F) || b0 == 0xDC) {
            val v = try { Msgpack.unpack(appData).first } catch (_: Exception) { return Info() } as? MpArray ?: return Info()
            val items = v.items
            var name: String? = null
            if (items.isNotEmpty()) {
                name = when (val n = items[0]) {
                    is MpBin -> String(n.value, Charsets.UTF_8)
                    is MpStr -> n.value
                    else -> null
                }?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }
            }
            var cost = 0
            if (items.size > 1) items[1].asLong()?.let { if (it in 1..254) cost = it.toInt() }
            var compression = true
            if (items.size > 2) {
                val sf = items[2] as? MpArray
                if (sf != null) compression = sf.items.any { it.asLong() == SF_COMPRESSION }
            }
            return Info(name, cost, compression, legacy = false)
        }
        return Info(String(appData, Charsets.UTF_8), 0, true, legacy = true)
    }
}
