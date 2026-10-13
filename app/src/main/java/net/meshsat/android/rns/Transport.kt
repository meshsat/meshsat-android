package net.meshsat.android.rns

import kotlin.concurrent.withLock

/** One row of Transport.link_table: a link request we forwarded. */
internal class LinkEntry(
    var timestampMs: Long,
    val nextHop: ByteArray,
    val nextHopIface: String,
    val remainingHops: Int,
    val receivedIface: String,
    val takenHops: Int,
    val destHash: ByteArray,
    var validated: Boolean = false,
    val proofTimeoutMs: Long,
)

/** One row of Transport.reverse_table: a packet we forwarded whose proof must travel back. */
internal class ReverseEntry(val receivedIface: String, val outboundIface: String, val timestampMs: Long)

/**
 * A HEADER_2 packet addressed to this transport instance (Transport.inbound,
 * "packet.transport_id == identity.hash"). False when no path is known.
 */
internal fun Node.forwardTransport(pkt: Packet): Boolean {
    val h = pkt.hdr
    val entry = paths.get(h.destHash)
    if (entry == null) { log("rns: packet in transport, no path to ${h.destHash.hex()}, dropping"); return false }
    val nowMs = now()
    val out = if (entry.hops > 1) Header.rewriteForTransport(pkt.raw, entry.nextHop) else Header.stripTransport(pkt.raw).copyOf()
    out[1] = h.hops.toByte()

    if (h.packetType == Rns.PACKET_LINKREQUEST) {
        // Clamp the signalled link MTU to the smaller of both interfaces.
        var outPkt = out
        if (h.data.size == LinkWire.EC_PUB_SIZE + LinkWire.MTU_SIZE) {
            val (pathMtu, mode) = LinkWire.parseSignalling(h.data, LinkWire.EC_PUB_SIZE)
            val nhMtu = tx.hwMtu(entry.iface)
            val phMtu = tx.hwMtu(pkt.iface)
            if (nhMtu == 0) {
                outPkt = out.copyOf(out.size - LinkWire.MTU_SIZE)
            } else {
                var m = pathMtu
                if (nhMtu < m) m = nhMtu
                if (phMtu in 1 until m) m = phMtu
                if (m != pathMtu) LinkWire.signalling(m, mode).copyInto(outPkt, outPkt.size - LinkWire.MTU_SIZE)
            }
        }
        val linkId = try { LinkWire.linkIdFromRequestPacket(pkt.raw) } catch (_: WireException) { return true }
        val rem = maxOf(1, entry.hops)
        lock.withLock {
            linkTable[linkId.hex()] = LinkEntry(
                timestampMs = nowMs, nextHop = entry.nextHop, nextHopIface = entry.iface, remainingHops = entry.hops,
                receivedIface = pkt.iface, takenHops = h.hops, destHash = h.destHash,
                proofTimeoutMs = nowMs + Rns.PER_HOP_TIMEOUT * rem * 1000L,
            )
        }
        entry.timestampMs = nowMs
        count { forwarded++ }
        transmit(entry.iface, outPkt)
        return true
    }
    lock.withLock { reverseTable[pkt.truncatedHash.hex()] = ReverseEntry(pkt.iface, entry.iface, nowMs) }
    entry.timestampMs = nowMs
    count { forwarded++ }
    transmit(entry.iface, out)
    return true
}

/**
 * The MeshSat extension: a HEADER_1 packet for a remote destination arriving from
 * an interface whose peers cannot address a transport (the Meshtastic mesh) is
 * inserted into transport here.
 */
internal fun Node.forwardHeader1(pkt: Packet): Boolean {
    if (pkt.iface !in cfg.forwardHeader1From) return false
    val h = pkt.hdr
    val entry = paths.get(h.destHash) ?: return false
    if (entry.iface == pkt.iface) return false
    val out = if (entry.hops > 1) Header.rewriteForTransport(pkt.raw, entry.nextHop) else pkt.raw.copyOf()
    out[1] = h.hops.toByte()
    val nowMs = now()
    lock.withLock {
        if (h.packetType == Rns.PACKET_LINKREQUEST) {
            try {
                val linkId = LinkWire.linkIdFromRequestPacket(pkt.raw)
                linkTable[linkId.hex()] = LinkEntry(
                    timestampMs = nowMs, nextHop = entry.nextHop, nextHopIface = entry.iface, remainingHops = entry.hops,
                    receivedIface = pkt.iface, takenHops = h.hops, destHash = h.destHash,
                    proofTimeoutMs = nowMs + Rns.PER_HOP_TIMEOUT * maxOf(1, entry.hops) * 1000L,
                )
            } catch (_: WireException) {}
        } else {
            reverseTable[pkt.truncatedHash.hex()] = ReverseEntry(pkt.iface, entry.iface, nowMs)
        }
    }
    count { forwarded++ }
    transmit(entry.iface, out)
    return true
}

/** Repeats packets belonging to a link we carry. */
internal fun Node.linkTransit(pkt: Packet): Boolean {
    val h = pkt.hdr
    val outIface: String
    lock.withLock {
        val e = linkTable[h.destHash.hex()] ?: return false
        if (!e.validated) return true   // a link packet before validation: drop
        val hops = h.hops
        var out = ""
        if (e.nextHopIface == e.receivedIface) {
            if (hops == e.remainingHops || hops == e.takenHops) out = e.nextHopIface
        } else if (pkt.iface == e.nextHopIface) {
            if (hops == e.remainingHops) out = e.receivedIface
        } else if (pkt.iface == e.receivedIface) {
            if (hops == e.takenHops) out = e.nextHopIface
        }
        if (out.isEmpty()) return true
        hashlist.add(pkt.hash.hex())
        e.timestampMs = now()
        statsRaw.linkTransit++
        outIface = out
    }
    transmit(outIface, pkt.raw)
    return true
}

/** Validates and forwards a link request proof for a link in the link table. False when the proof is not for a transit link. */
internal fun Node.lrProofTransit(pkt: Packet): Boolean {
    val h = pkt.hdr
    val e: LinkEntry
    val known: KnownIdentity?
    lock.withLock {
        e = linkTable[h.destHash.hex()] ?: return false
        if (h.hops != e.remainingHops || pkt.iface != e.nextHopIface) {
            log("rns: link proof hop or interface mismatch in transit (${h.hops} vs ${e.remainingHops})")
            return true
        }
        known = identities[e.destHash.hex()]
    }
    val lp = try { LinkWire.ProofData.unpack(h.data) } catch (_: WireException) { return true }
    val id = known?.identity ?: return true
    val signed = LinkWire.proofSignedData(h.destHash, lp.ephPub, id.sigPubRaw, lp.mtu, lp.mode, lp.signalled)
    if (!id.verify(signed, lp.signature)) { log("rns: invalid link request proof in transport"); return true }
    lock.withLock { e.validated = true; e.timestampMs = now() }
    transmit(e.receivedIface, pkt.raw)
    return true
}

/** Forwards a packet proof back along the reverse table. */
internal fun Node.proofTransit(pkt: Packet) {
    val h = pkt.hdr
    val e = lock.withLock { reverseTable.remove(h.destHash.hex()) } ?: return
    if (pkt.iface != e.outboundIface) return
    transmit(e.receivedIface, pkt.raw)
}

/** Sweeps the link, reverse and path tables. */
internal fun Node.expireTables(nowMs: Long) {
    lock.withLock {
        reverseTable.entries.removeAll { nowMs > it.value.timestampMs + Rns.REVERSE_TIMEOUT * 1000L }
        linkTable.entries.removeAll { (_, e) ->
            (!e.validated && nowMs > e.proofTimeoutMs) || nowMs > e.timestampMs + Rns.LINK_TIMEOUT * 1000L
        }
    }
    paths.expire(nowMs)
}
