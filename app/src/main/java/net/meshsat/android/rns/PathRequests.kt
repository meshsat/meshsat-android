package net.meshsat.android.rns

import java.util.concurrent.CompletableFuture
import kotlin.concurrent.withLock

/** One row of Transport.discovery_path_requests. */
internal class DiscoveryEntry(var timeoutMs: Long, val requestingIfaces: MutableList<String> = ArrayList(), var engaged: Boolean = false)

/**
 * The path-request branch of Transport.inbound and Transport.path_request for a
 * transport instance that searches on every interface. Data: dest (16) ||
 * [requestor transport id (16)] || tag.
 */
internal fun Node.handlePathRequest(pkt: Packet) {
    val data = pkt.hdr.data
    if (data.size < Rns.HASH_LEN) return
    val dest = data.copyOfRange(0, Rns.HASH_LEN)
    var requestor: ByteArray? = null
    var tag: ByteArray = when {
        data.size > 2 * Rns.HASH_LEN -> { requestor = data.copyOfRange(Rns.HASH_LEN, 2 * Rns.HASH_LEN); data.copyOfRange(2 * Rns.HASH_LEN, data.size) }
        data.size > Rns.HASH_LEN -> data.copyOfRange(Rns.HASH_LEN, data.size)
        else -> return   // tagless
    }
    if (tag.size > Rns.HASH_LEN) tag = tag.copyOf(Rns.HASH_LEN)
    val unique = dest.hex() + tag.hex()
    val destKey = dest.hex()

    val local: Destination?
    lock.withLock {
        if (unique in prTags || unique in prTagsPrev) return
        prTags.add(unique)
        statsRaw.pathRequestsRx++
        if (inflightPRs.containsKey(destKey)) {
            // Batch onto the existing discovery.
            val d = discoveryPRs.getOrPut(destKey) { DiscoveryEntry(now() + Rns.PATH_REQUEST_TIMEOUT * 1000L) }
            if (pkt.iface !in d.requestingIfaces) d.requestingIfaces.add(pkt.iface)
            return
        }
        inflightPRs[destKey] = now()
        local = dests[destKey]
    }

    // Local destination: answer with an announce on the requesting interface.
    if (local != null) {
        if (announce(local, pkt.iface, pathResponse = true)) log("rns: answered path request for ${local.name} on ${pkt.iface}")
        resolveInflight(destKey)
        return
    }

    // Known path: queue the cached announce as a path response after the grace period.
    val e = paths.get(dest)
    if (e != null) {
        if (requestor != null && e.nextHop.contentEquals(requestor)) {
            log("rns: not answering path request, next hop is the requestor")
            resolveInflight(destKey)
            return
        }
        val nowMs = now()
        lock.withLock {
            announceTable[destKey]?.let { held -> if (!held.blockRebroadcasts) heldAnnounces[destKey] = held }
            announceTable[destKey] = AnnounceEntry(
                timestampMs = nowMs, retransmitTimeoutMs = nowMs + (Rns.PATH_REQUEST_GRACE * 1000).toLong(),
                retries = Rns.PATHFINDER_R, receivedFrom = e.nextHop, hops = e.hops, ann = e.announce,
                blockRebroadcasts = true, attachedIface = pkt.iface,
            )
        }
        resolveInflight(destKey)
        return
    }

    // Unknown: search on every other floodable interface with the same tag.
    lock.withLock {
        var d = discoveryPRs[destKey]
        if (d != null && d.engaged) {
            if (pkt.iface !in d.requestingIfaces) d.requestingIfaces.add(pkt.iface)
            return
        }
        if (d == null) { d = DiscoveryEntry(0); discoveryPRs[destKey] = d }
        d.timeoutMs = now() + Rns.PATH_REQUEST_TIMEOUT * 1000L
        d.engaged = true
        if (pkt.iface !in d.requestingIfaces) d.requestingIfaces.add(pkt.iface)
    }
    val tag16 = tag.copyOf(Rns.HASH_LEN)
    for (id in tx.floodable() + paidTake(dest, PaidKind.PATH_REQUEST, pkt.iface)) {
        if (id != pkt.iface) sendPathRequest(dest, id, tag16)
    }
}

internal fun Node.resolveInflight(destKey: String) = lock.withLock { inflightPRs.remove(destKey) }

internal fun Node.expireDiscovery(nowMs: Long) = lock.withLock {
    val it = discoveryPRs.entries.iterator()
    while (it.hasNext()) { val (k, d) = it.next(); if (nowMs > d.timeoutMs) { it.remove(); inflightPRs.remove(k) } }
    inflightPRs.entries.removeAll { nowMs > it.value + Rns.PATH_REQUEST_TIMEOUT * 1000L }
}

/** One path request packet on an interface: dest || our transport id || tag, to the PLAIN path request destination. */
internal fun Node.sendPathRequest(dest: ByteArray, ifaceId: String, tag: ByteArray) {
    val h = Header(
        headerType = Rns.HEADER_1, transportType = Rns.TRANSPORT_BROADCAST, destType = Rns.DEST_PLAIN,
        packetType = Rns.PACKET_DATA, destHash = Rns.PATH_REQUEST_DEST_HASH, context = Rns.CTX_NONE,
        data = dest + idHash + tag,
    )
    transmit(ifaceId, h.pack())
}

/**
 * Asks the network for a path. The future completes true when an announce for
 * [dest] arrives, or with whether a path is known once [timeoutMs] passes.
 */
fun Node.requestPath(dest: ByteArray, timeoutMs: Long = Rns.PATH_REQUEST_TIMEOUT * 1000L): CompletableFuture<Boolean> {
    if (paths.has(dest)) return CompletableFuture.completedFuture(true)
    val f = CompletableFuture<Boolean>()
    val key = dest.hex()
    lock.withLock { pathWaiters.getOrPut(key) { ArrayList() }.add(f) }
    val tag = Rns.randomBytes(Rns.HASH_LEN)
    for (id in tx.floodable() + paidTake(dest, PaidKind.PATH_REQUEST, "")) sendPathRequest(dest, id, tag)
    schedule(timeoutMs) {
        lock.withLock { pathWaiters[key]?.remove(f) }
        f.complete(paths.has(dest))
    }
    return f
}

/** [requestPath], blocking the calling thread. */
fun Node.requestPathBlocking(dest: ByteArray, timeoutMs: Long = Rns.PATH_REQUEST_TIMEOUT * 1000L): Boolean =
    try { requestPath(dest, timeoutMs).get(timeoutMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { paths.has(dest) }
