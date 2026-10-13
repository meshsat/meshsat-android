package net.meshsat.android.lxmf

import net.meshsat.android.rns.MpArray
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpBool
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.MpStr
import net.meshsat.android.rns.Msgpack
import net.meshsat.android.rns.asLong
import net.meshsat.android.rns.hex

/**
 * What an lxmf.propagation destination announces (LXMRouter.get_propagation_node_app_data,
 * LXMF 1.2.0): [legacy flag, timebase, enabled, per-transfer limit kB, per-sync limit kB,
 * [stamp cost, flexibility, peering cost], metadata]. [MESHSAT-1553 P3b]
 */
data class PropagationNodeInfo(
    val destHash: ByteArray,
    val timebase: Long,
    val enabled: Boolean,
    val transferLimitKb: Long,
    val syncLimitKb: Long,
    /** The propagation stamp cost a client must meet (PROPAGATION_COST_MIN 13 and up). */
    val stampCost: Int,
    val stampCostFlex: Int,
    val peeringCost: Int,
    val name: String?,
    val hops: Int = -1,
    val iface: String = "",
    val lastSeenMs: Long = 0,
) {
    val destHex: String get() = destHash.hex()
}

object PnAppData {
    const val PN_META_NAME = 0x01L

    /** The parsed announce, or null when it is not a 1.2.0 propagation node announce (pn_announce_data_is_valid). */
    fun parse(dest: ByteArray, appData: ByteArray?): PropagationNodeInfo? {
        if (appData == null || appData.isEmpty()) return null
        val v = try { Msgpack.unpack(appData).first } catch (_: Exception) { return null } as? MpArray ?: return null
        val items = v.items
        if (items.size < 7) return null
        val timebase = items[1].asLong() ?: return null
        val enabled = (items[2] as? MpBool)?.value ?: return null
        val transfer = items[3].asLong() ?: return null
        val sync = items[4].asLong() ?: return null
        val costs = (items[5] as? MpArray)?.items ?: return null
        if (costs.size < 3) return null
        val cost = costs[0].asLong() ?: return null
        val flex = costs[1].asLong() ?: return null
        val peering = costs[2].asLong() ?: return null
        val meta = items[6] as? MpMap ?: return null
        val name = when (val n = meta[PN_META_NAME]) {
            is MpBin -> String(n.value, Charsets.UTF_8)
            is MpStr -> n.value
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        return PropagationNodeInfo(dest.copyOf(), timebase, enabled, transfer, sync, cost.toInt(), flex.toInt(), peering.toInt(), name)
    }
}

/** LXMRouter.PR_* : where a sync from a propagation node stands. */
enum class SyncState(val code: Int) {
    IDLE(0x00), PATH_REQUESTED(0x01), LINK_ESTABLISHING(0x02), LINK_ESTABLISHED(0x03), REQUEST_SENT(0x04),
    RECEIVING(0x05), RESPONSE_RECEIVED(0x06), COMPLETE(0x07),
    NO_PATH(0xf0), LINK_FAILED(0xf1), TRANSFER_FAILED(0xf2), NO_IDENTITY_RCVD(0xf3), NO_ACCESS(0xf4), FAILED(0xfe);

    val failed: Boolean get() = code >= 0xf0
    val busy: Boolean get() = code in 0x01..0x06
}

data class SyncResult(val state: SyncState, val received: Int, val duplicates: Int, val error: String = "")
