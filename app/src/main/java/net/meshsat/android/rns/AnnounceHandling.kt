package net.meshsat.android.rns

import kotlin.concurrent.withLock

private const val MAX_RANDOM_BLOBS = 64

/** One row of Transport.announce_table. */
internal class AnnounceEntry(
    val timestampMs: Long,
    var retransmitTimeoutMs: Long,
    var retries: Int = 0,
    val receivedFrom: ByteArray,
    val hops: Int,
    val ann: Announce,
    val sourceIface: String = "",
    var localRebroadcasts: Int = 0,
    val blockRebroadcasts: Boolean = false,   // true: send as PATH_RESPONSE on attachedIface only
    val attachedIface: String = "",
)

/** The announce branch of Transport.inbound. */
internal fun Node.handleAnnounce(pkt: Packet): Boolean {
    val h = pkt.hdr
    val ann = try { Announce.unpackPayload(h.data, h.destHash, h.hops, h.contextFlag, h.context) } catch (_: WireException) { return true }
    ann.verify()?.let { log("rns: invalid announce on ${pkt.iface}: $it"); return true }
    count { announcesRx++ }
    if (localDestination(h.destHash) != null) return true   // our own announce echoed back

    val nowMs = now()
    val receivedFrom = if (h.headerType == Rns.HEADER_2) h.transportId!! else h.destHash
    val hops = h.hops
    val randomBlob = ann.random.copyOf()
    val emitted = Announce.blobTimestamp(randomBlob)
    val destKey = h.destHash.hex()

    var entry: PathEntry
    val answerOn = ArrayList<String>()
    val waiters: List<java.util.concurrent.CompletableFuture<Boolean>>
    lock.withLock {
        // A rebroadcast by another node of an announce we hold for retransmission.
        if (h.headerType == Rns.HEADER_2) {
            announceTable[destKey]?.let { e ->
                if (hops - 1 == e.hops) {
                    e.localRebroadcasts++
                    if (e.retries > 0 && e.localRebroadcasts >= Rns.LOCAL_REBROADCASTS_MAX) announceTable.remove(destKey)
                }
                if (hops - 1 == e.hops + 1 && e.retries > 0 && nowMs < e.retransmitTimeoutMs) announceTable.remove(destKey)
            }
        }

        var shouldAdd = false
        val existing = paths.get(h.destHash)
        var blobs: MutableList<ByteArray> = ArrayList()
        if (hops < Rns.PATHFINDER_M + 1) {
            if (existing == null) {
                shouldAdd = true
            } else {
                blobs = existing.randomBlobs
                if (hops <= existing.hops) {
                    if (!containsBlob(blobs, randomBlob) && emitted > existing.emittedAt()) shouldAdd = true
                } else {
                    var pathEmitted = 0L
                    for (b in blobs) {
                        val ts = Announce.blobTimestamp(b)
                        if (ts > pathEmitted) pathEmitted = ts
                        if (pathEmitted >= emitted) break
                    }
                    if (nowMs >= existing.expiresMs) shouldAdd = !containsBlob(blobs, randomBlob)
                    else if (emitted > pathEmitted) shouldAdd = !containsBlob(blobs, randomBlob)
                    else if (emitted == pathEmitted && existing.unresponsive) shouldAdd = true
                }
            }
        }
        if (!shouldAdd) return true

        if (!containsBlob(blobs, randomBlob)) {
            blobs = ArrayList(blobs).also { it.add(randomBlob) }
            while (blobs.size > MAX_RANDOM_BLOBS) blobs.removeAt(0)
        }
        entry = PathEntry(
            destHash = h.destHash, timestampMs = nowMs, nextHop = receivedFrom.copyOf(), hops = hops,
            expiresMs = nowMs + paths.ttlMs, randomBlobs = blobs, iface = pkt.iface, announce = ann, packetHash = pkt.hash,
        )
        if (h.context != Rns.CTX_PATH_RESPONSE) {
            announceTable[destKey] = AnnounceEntry(
                timestampMs = nowMs, retransmitTimeoutMs = nowMs + (Math.random() * Rns.PATHFINDER_RW * 1000).toLong(),
                receivedFrom = receivedFrom.copyOf(), hops = hops, ann = ann, sourceIface = pkt.iface,
            )
        }
        // Answer waiting discovery path requests at once.
        discoveryPRs.remove(destKey)?.let { answerOn.addAll(it.requestingIfaces) }
        paths.put(entry)
        // Remember the identity.
        val k = identities.getOrPut(destKey) { KnownIdentity(h.destHash.copyOf()) }
        k.publicKey = ann.publicKey.copyOf()
        k.identity = RnsIdentity.fromPublicBytesOrNull(ann.publicKey)
        k.appData = ann.appData.copyOf()
        if (ann.contextFlag != 0 && ann.ratchet != null) { k.ratchet = ann.ratchet.copyOf(); k.ratchetAtMs = nowMs }
        k.lastSeenMs = nowMs
        waiters = pathWaiters.remove(destKey) ?: emptyList()
        inflightPRs.remove(destKey)
    }

    for (ifc in answerOn) {
        if (!paidGate(ifc, h.destHash, PaidKind.ANNOUNCE)) continue
        transmit(ifc, ann.transportPacket(idHash, hops, Rns.CTX_PATH_RESPONSE))
    }
    for (w in waiters) w.complete(true)
    log("rns: path learned ${destKey.take(8)} hops=$hops iface=${pkt.iface} via=${receivedFrom.hex().take(8)}")
    onPathChanged?.invoke(h.destHash, entry)
    onAnnounce?.invoke(ann, pkt)
    return true
}

private fun containsBlob(blobs: List<ByteArray>, b: ByteArray): Boolean = blobs.any { it.contentEquals(b) }

/** Retransmits queued announces (Transport.jobs). */
internal fun Node.processAnnounceTable(nowMs: Long) {
    class Out(val raw: ByteArray, val ifaces: List<String>?, val dest: ByteArray, val source: String)
    val outgoing = ArrayList<Out>()
    lock.withLock {
        val it = announceTable.entries.iterator()
        while (it.hasNext()) {
            val (destKey, e) = it.next()
            when {
                e.retries > 0 && e.retries >= Rns.LOCAL_REBROADCASTS_MAX -> it.remove()
                e.retries > Rns.PATHFINDER_R -> it.remove()
                nowMs > e.retransmitTimeoutMs -> {
                    e.retransmitTimeoutMs = nowMs + ((Rns.PATHFINDER_G + Rns.PATHFINDER_RW) * 1000).toLong()
                    e.retries++
                    val ctx = if (e.blockRebroadcasts) Rns.CTX_PATH_RESPONSE else Rns.CTX_NONE
                    val raw = e.ann.transportPacket(idHash, e.hops, ctx)
                    // A rebroadcast goes out on every floodable interface, the one it arrived on
                    // included: on a shared medium that repetition is the point, and on a
                    // multi-peer TCP interface the other peers only hear it that way.
                    outgoing.add(Out(raw, if (e.attachedIface.isNotEmpty()) listOf(e.attachedIface) else null, e.ann.destHash, e.sourceIface))
                    heldAnnounces.remove(destKey)?.let { held -> announceTable[destKey] = held }
                }
            }
        }
    }
    for (o in outgoing) {
        var ifaces = o.ifaces
        if (ifaces == null) {
            // Paid interfaces open to this destination join the flood, never the one the
            // announce came in on: on a paid link that echo is a paid message for nothing.
            ifaces = tx.floodable() + paidTake(o.dest, PaidKind.ANNOUNCE, o.source)
        } else if (!paidGate(ifaces[0], o.dest, PaidKind.ANNOUNCE)) {
            continue   // a path response on a paid link for an unlisted destination
        }
        log("rns: rebroadcasting announce for ${o.dest.hex().take(8)} on $ifaces")
        for (id in ifaces) sendAnnounceOn(id, o.raw, nowMs)
        count { announcesTx++ }
    }
}

/** Delays announces on a slow interface to the 2 % cap. */
internal class AnnounceQueue {
    var allowedAtMs = 0L
    val pending = ArrayList<ByteArray>()
    var timerSet = false
}

/**
 * The announce cap: an interface with a known bit rate may only spend
 * ANNOUNCE_CAP percent of its time on announces (Transport.outbound). Announces
 * beyond the cap are queued, newest last, and released as the budget allows.
 */
internal fun Node.sendAnnounceOn(ifaceId: String, raw: ByteArray, nowMs: Long) {
    val bitrate = announceBitrate(ifaceId)
    if (bitrate <= 0) { transmit(ifaceId, raw); return }
    val txTimeSec = raw.size * 8.0 / bitrate
    val waitMs = (txTimeSec / (Rns.ANNOUNCE_CAP / 100.0) * 1000).toLong()
    val sendNow: Boolean
    var delayMs = 0L
    lock.withLock {
        val q = announceQueues.getOrPut(ifaceId) { AnnounceQueue() }
        if (q.pending.isEmpty() && nowMs > q.allowedAtMs) {
            q.allowedAtMs = nowMs + waitMs
            sendNow = true
        } else {
            sendNow = false
            if (q.pending.size >= 16) { log("rns: announce queue on $ifaceId full, dropping"); return }
            q.pending.add(raw)
            if (!q.timerSet) { q.timerSet = true; delayMs = maxOf(0L, q.allowedAtMs - nowMs) }
            else return
        }
    }
    if (sendNow) transmit(ifaceId, raw) else schedule(delayMs) { flushAnnounceQueue(ifaceId) }
}

internal fun Node.flushAnnounceQueue(ifaceId: String) {
    val nowMs = now()
    val bitrate = announceBitrate(ifaceId)
    val raw: ByteArray
    lock.withLock {
        val q = announceQueues[ifaceId]
        if (q == null || q.pending.isEmpty()) { q?.timerSet = false; return }
        raw = q.pending.removeAt(0)
        q.allowedAtMs = nowMs
        if (bitrate > 0) q.allowedAtMs = nowMs + (raw.size * 8.0 / bitrate / (Rns.ANNOUNCE_CAP / 100.0) * 1000).toLong()
        if (q.pending.isNotEmpty()) { val d = q.allowedAtMs - nowMs; schedule(d) { flushAnnounceQueue(ifaceId) } }
        else q.timerSet = false
    }
    transmit(ifaceId, raw)
}

/**
 * Emits an announce for a local destination on every floodable interface and
 * every paid interface open to it (or only on [ifaceId] when set, which a paid
 * interface still gates). The destination's ratchets rotate first.
 */
fun Node.announce(d: Destination, ifaceId: String = "", pathResponse: Boolean = false): Boolean {
    val nowMs = now()
    val ratchetPub = d.rotateRatchets(nowMs)
    val ann = Announce.create(d.identity, d.name, d.appData?.invoke(), ratchetPub, nowMs / 1000, pathResponse)
    val raw = ann.packet()
    if (ifaceId.isNotEmpty()) {
        if (!paidGate(ifaceId, d.hash, PaidKind.ANNOUNCE)) return false
        sendAnnounceOn(ifaceId, raw, nowMs)
    } else {
        val ifaces = tx.floodable() + paidTake(d.hash, PaidKind.ANNOUNCE, "")
        if (ifaces.isEmpty()) return false
        for (id in ifaces) sendAnnounceOn(id, raw, nowMs)
    }
    count { announcesTx++ }
    return true
}
