package net.meshsat.android.rns

import java.util.concurrent.CompletableFuture
import kotlin.concurrent.withLock

/** The outcome of a [Receipt]. */
data class ReceiptResult(val delivered: Boolean, val rttMs: Long = 0)

/** Tracks a packet sent to a SINGLE destination (or on a link) until its proof arrives or the timeout passes (RNS.PacketReceipt). */
class Receipt internal constructor(
    val hash: ByteArray,
    val dest: ByteArray,
    val sentAtMs: Long,
    var timeoutAtMs: Long,
    internal val sigPub: ByteArray?,   // the key that must sign the proof
    internal val link: Link?,
) {
    /** Completes once, delivered or timed out. */
    val result = CompletableFuture<ReceiptResult>()
    val hashHex: String get() = hash.hex()
}

internal fun Node.addReceipt(r: Receipt) = lock.withLock { receipts[r.hashHex] = r }

internal fun Node.removeReceipt(r: Receipt) = lock.withLock { receipts.remove(r.hashHex) }

/** Matches a PROOF packet against outstanding receipts. */
internal fun Node.handleReceiptProof(pkt: Packet) {
    val data = pkt.hdr.data
    val candidates = ArrayList<Receipt>()
    lock.withLock {
        if (data.size == Proof.EXPLICIT_LEN) {
            receipts[data.copyOfRange(0, Rns.FULL_HASH_LEN).hex()]?.let { candidates.add(it) }
        } else if (data.size == Proof.IMPLICIT_LEN) {
            // A proof is addressed to the truncated packet hash, so those come first.
            val tr = pkt.hdr.destHash
            for (r in receipts.values) if (r.hash.copyOf(Rns.HASH_LEN).contentEquals(tr)) candidates.add(r)
            for (r in receipts.values) if (r.dest.contentEquals(tr) || pkt.hdr.destType == Rns.DEST_LINK) if (r !in candidates) candidates.add(r)
        }
    }
    for (r in candidates) {
        val pub = r.sigPub ?: continue
        if (Proof.validate(data, r.hash, pub)) {
            lock.withLock { receipts.remove(r.hashHex) }
            r.link?.noteProof(now())
            r.result.complete(ReceiptResult(true, now() - r.sentAtMs))
            return
        }
    }
}

internal fun Node.expireReceipts(nowMs: Long) {
    val expired = lock.withLock {
        val out = receipts.values.filter { nowMs > it.timeoutAtMs }
        for (r in out) receipts.remove(r.hashHex)
        out
    }
    for (r in expired) r.result.complete(ReceiptResult(false))
}

// ---------------------------------------------------------------------------
// Outbound
// ---------------------------------------------------------------------------

/**
 * Sends a locally originated non-link packet (Transport.outbound): along the path
 * when one is known (HEADER_2 insertion beyond one hop), otherwise broadcast on
 * every floodable interface. Returns the interfaces used, empty when none.
 */
internal fun Node.outbound(raw: ByteArray, dest: ByteArray, destType: Int): List<String> {
    val hdr = Header.unpackOrNull(raw) ?: return emptyList()
    if (hdr.packetType != Rns.PACKET_ANNOUNCE && destType != Rns.DEST_PLAIN && destType != Rns.DEST_GROUP) {
        val e = paths.get(dest)
        if (e != null && now() < e.expiresMs) {
            val out = if (!e.direct) Header.rewriteForTransport(raw, e.nextHop) else raw
            e.timestampMs = now()
            return if (transmit(e.iface, out)) listOf(e.iface) else emptyList()
        }
    }
    val ifaces = tx.floodable()
    for (id in ifaces) transmit(id, raw)
    return ifaces
}

/** The known hop count to a destination or -1. */
fun Node.hopsTo(dest: ByteArray): Int = paths.hops(dest)

/** The interface a destination is reached on, or "". */
fun Node.nextHopInterface(dest: ByteArray): String = paths.get(dest)?.iface ?: ""

/**
 * Encrypts [plaintext] for a remote SINGLE destination known from an announce
 * and sends it as one DATA packet, to the peer's latest ratchet when it announced
 * one. The receipt resolves when the destination's proof arrives.
 */
fun Node.sendData(dest: ByteArray, plaintext: ByteArray, wantReceipt: Boolean = true): Receipt? {
    val k = recall(dest)
    val id = k?.identity ?: throw IllegalStateException("destination ${dest.hex().take(8)} identity unknown, no announce seen")
    if (plaintext.size > Rns.ENCRYPTED_MDU) throw IllegalArgumentException("${plaintext.size} bytes exceed the single-packet limit of ${Rns.ENCRYPTED_MDU}")
    val ratchet = k.ratchet?.takeIf { it.size == Rns.RATCHET_LEN && now() < k.ratchetAtMs + RATCHET_EXPIRY_MS }
    val ct = id.encrypt(plaintext, ratchet)
    val raw = Header(headerType = Rns.HEADER_1, destType = Rns.DEST_SINGLE, packetType = Rns.PACKET_DATA, destHash = dest, context = Rns.CTX_NONE, data = ct).pack()
    var r: Receipt? = null
    if (wantReceipt) {
        val hops = maxOf(1, paths.hops(dest))
        r = Receipt(Header.packetHash(raw), dest.copyOf(), now(), now() + (Rns.PER_HOP_TIMEOUT * hops + 6) * 1000L, id.sigPubRaw, null)
        addReceipt(r)
    }
    if (outbound(raw, dest, Rns.DEST_SINGLE).isEmpty()) {
        r?.let { removeReceipt(it) }
        throw IllegalStateException("no path to ${dest.hex().take(8)} and nothing to flood on")
    }
    return r
}

/** Transmits an already-built packet for [dest] along the path rules. */
fun Node.sendRaw(raw: ByteArray, dest: ByteArray, destType: Int): Boolean = outbound(raw, dest, destType).isNotEmpty()

/** Identity.RATCHET_EXPIRY: a peer's announced ratchet is used for 30 days. */
const val RATCHET_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000
