package net.meshsat.android.rns

import kotlin.concurrent.withLock

/**
 * Paid links (the Bridge's MESHSAT-1547/1548, for the phone MESHSAT-1553 P6).
 *
 * A paid bearer (Iridium, SMS) never appears in [Transmitter.floodable], so no
 * announce, path request or pathless packet is ever broadcast on it. That keeps a
 * mesh from spending satellite messages on background traffic, but a destination
 * behind the satellite can then never be learned. A [PaidPolicy] opens one paid
 * interface for a short list of destinations: their announces, path requests and
 * path responses cross it, each at most once per [minIntervalMs] per destination.
 * Both ends should list the same destinations.
 *
 * [extraHops] is added to the hop count of EVERY packet that arrives on the
 * interface, on top of the usual one: the slow hop counts as several. A stock
 * client times a link setup out at its first-hop timeout plus 6 s per hop, far
 * below a satellite round trip; counting the hop as N gives it the time the link
 * takes (6 x (hops + 3) >= 2 x the one-way delay). It applies to every packet type
 * because a transport and the initiator both check that a link proof arrives
 * with the hop count the path promised.
 */
class PaidPolicy(
    dests: List<ByteArray>,
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** Bit rate for the announce cap when the transmitter reports none; 0 leaves the cap off. */
    val bitrate: Int = 0,
    val extraHops: Int = 0,
) {
    val dests: List<ByteArray> = dests.map { it.copyOf() }

    init { require(extraHops in 0..MAX_EXTRA_HOPS) { "extra hops must be 0..$MAX_EXTRA_HOPS" } }

    fun allows(dest: ByteArray): Boolean = dests.any { it.contentEquals(dest) }

    /** `dest1,dest2|minIntervalSec|bitrate|extraHops`, for settings. */
    fun serialize(): String = dests.joinToString(",") { it.hex() } + "|" + (minIntervalMs / 1000) + "|" + bitrate + "|" + extraHops

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 5L * 60 * 1000
        /** Keeps an inflated path well inside PATHFINDER_M. */
        const val MAX_EXTRA_HOPS = 32

        fun parse(s: String): PaidPolicy? {
            val parts = s.split("|")
            if (parts.size != 4) return null
            val dests = parts[0].split(",").filter { it.length == 2 * Rns.HASH_LEN }.map { it.unhex() }.distinctBy { it.hex() }
            val sec = parts[1].toLongOrNull() ?: return null
            val br = parts[2].toIntOrNull() ?: return null
            val eh = parts[3].toIntOrNull() ?: return null
            if (sec < 0 || br < 0 || eh !in 0..MAX_EXTRA_HOPS) return null
            return PaidPolicy(dests, if (sec == 0L) DEFAULT_MIN_INTERVAL_MS else sec * 1000, br, eh)
        }
    }
}

internal enum class PaidKind { ANNOUNCE, PATH_REQUEST }

internal data class PaidKey(val iface: String, val dest: String, val kind: PaidKind)

internal data class CrossingKey(val iface: String, val dest: String, val inbound: Boolean)

/** One data packet that crossed a paid link with a policy. */
data class PaidCrossing(val iface: String, val inbound: Boolean, val dest: ByteArray, val bytes: Int)

/** Installs (or with null or an empty list removes) the policy for one paid interface, which must stay out of floodable(). */
fun Node.setPaidPolicy(ifaceId: String, p: PaidPolicy?) = lock.withLock {
    if (p == null || p.dests.isEmpty()) paid.remove(ifaceId) else paid[ifaceId] = p
}

fun Node.paidPolicies(): Map<String, PaidPolicy> = lock.withLock { HashMap(paid) }

/** The paid interfaces, other than [except], that [dest] may cross now; books the send on each. */
internal fun Node.paidTake(dest: ByteArray, kind: PaidKind, except: String): List<String> {
    val ids = lock.withLock { if (paid.isEmpty()) emptyList() else paid.filter { (id, p) -> id != except && p.allows(dest) }.keys.sorted() }
    if (ids.isEmpty()) return ids
    val nowMs = now()
    return ids.filter { id -> tx.online(id) && paidBook(id, dest, kind, nowMs) }
}

/** One send on a named interface: true without a policy, otherwise only for a listed destination whose interval passed (then booked). */
internal fun Node.paidGate(ifaceId: String, dest: ByteArray, kind: PaidKind): Boolean {
    val p = lock.withLock { paid[ifaceId] } ?: return true
    if (!p.allows(dest)) { count { paidSuppressed++ }; return false }
    return paidBook(ifaceId, dest, kind, now())
}

internal fun Node.paidBook(ifaceId: String, dest: ByteArray, kind: PaidKind, nowMs: Long): Boolean = lock.withLock {
    val p = paid[ifaceId] ?: return false
    val k = PaidKey(ifaceId, dest.hex(), kind)
    val last = paidLast[k]
    if (last != null && nowMs - last < p.minIntervalMs) { statsRaw.paidSuppressed++; return false }
    paidLast[k] = nowMs
    statsRaw.paidTx++
    true
}

/** The bit rate the announce cap uses on an interface. */
internal fun Node.announceBitrate(ifaceId: String): Int {
    val b = tx.bitrate(ifaceId)
    if (b > 0) return b
    return lock.withLock { paid[ifaceId]?.bitrate ?: 0 }
}

/** The hop count added to packets arriving on an interface. */
internal fun Node.paidExtraHops(ifaceId: String): Int = lock.withLock { paid[ifaceId]?.extraHops ?: 0 }

/** Whether the known path to [dest] leaves this node on a paid interface with a policy, i.e. over the satellite. */
fun Node.crossesPaidLink(dest: ByteArray): Boolean {
    val iface = nextHopInterface(dest)
    if (iface.isEmpty()) return false
    return lock.withLock { paid.containsKey(iface) }
}

private const val CROSSING_GAP_MS = 60_000L

/** Reports a DATA packet with no special context on a paid interface that has a policy, once a minute per interface, destination and direction. */
internal fun Node.noteCrossing(ifaceId: String, raw: ByteArray, inbound: Boolean) {
    val cb = onPaidCrossing ?: return
    if (!lock.withLock { paid.containsKey(ifaceId) }) return
    val h = Header.unpackOrNull(raw) ?: return
    if (h.packetType != Rns.PACKET_DATA || h.context != Rns.CTX_NONE) return
    val k = CrossingKey(ifaceId, h.destHash.hex(), inbound)
    val nowMs = now()
    lock.withLock {
        val last = crossings[k]
        if (last != null && nowMs - last < CROSSING_GAP_MS) return
        crossings[k] = nowMs
    }
    cb(PaidCrossing(ifaceId, inbound, h.destHash, raw.size))
}
