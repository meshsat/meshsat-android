package net.meshsat.android.rns

import java.util.concurrent.CompletableFuture
import kotlin.concurrent.withLock

/**
 * Link requests and responses, the requesting side (RNS/Link.py request,
 * handle_response, RequestReceipt): a request is msgpack([time, truncated_hash(path),
 * data]) sent as a link packet with context REQUEST, or as a resource when it is
 * larger than the link MDU, and is identified by the truncated hash of that packet
 * (of the packed request for a resource). The response is msgpack([request id, data])
 * with context RESPONSE, or a resource whose advertisement carries the request id
 * and the response flag. The LXMF propagation node protocol is a client of this.
 * [MESHSAT-1553 P3b]
 */
class RequestReceipt internal constructor(val link: Link, val requestId: ByteArray, val path: String, internal var deadlineMs: Long) {
    /** The response data (MpNil for None); exceptionally with [RequestFailed] on failure or timeout. */
    val result = CompletableFuture<MpValue>()
    /** A response resource is being received: the deadline no longer applies, the resource's own does. */
    @Volatile internal var responseStarted = false
    val requestIdHex: String get() = requestId.hex()
}

class RequestFailed(message: String) : Exception(message)

/** Link.TRAFFIC_TIMEOUT_FACTOR */
internal const val TRAFFIC_TIMEOUT_FACTOR = 6
/** Resource.RESPONSE_MAX_GRACE_TIME, in milliseconds */
internal const val RESPONSE_MAX_GRACE_TIME_MS = 10_000L

/**
 * Sends a request on an active link. [timeoutMs] 0 means upstream's default:
 * rtt x 6 plus 11.25 s.
 */
fun LinkManager.request(l: Link, path: String, data: MpValue = MpNil, timeoutMs: Long = 0): RequestReceipt {
    val now = node().now()
    val packed = Msgpack.pack(MpArray(MpFloat(now / 1000.0), MpBin(Rns.truncatedHash(path.toByteArray(Charsets.UTF_8))), data))
    val timeout = if (timeoutMs > 0) timeoutMs else l.rttMs * TRAFFIC_TIMEOUT_FACTOR + (RESPONSE_MAX_GRACE_TIME_MS * 1.125).toLong()
    val asResource: Boolean
    val r: RequestReceipt
    l.mu.withLock {
        if (l.stateRaw != LinkState.ACTIVE && l.stateRaw != LinkState.STALE) throw IllegalStateException("link not active")
        asResource = packed.size > l.mdu
        r = if (!asResource) {
            val raw = sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_REQUEST, l.token!!.encrypt(packed))
            RequestReceipt(l, Header.truncatedPacketHash(raw), path, now + timeout)
        } else {
            RequestReceipt(l, Rns.truncatedHash(packed), path, now + timeout)
        }
        l.pendingRequests[r.requestIdHex] = r
    }
    if (asResource) {
        val res = sendResource(l, packed, requestId = r.requestId, isResponse = false)
        res.result.thenAccept { out ->
            if (!out.delivered) failRequest(r, "request resource failed: ${out.error}")
            else r.deadlineMs = node().now() + timeout
        }
    }
    node().log("rns: request ${r.requestIdHex.take(8)} $path on ${l.idHex.take(8)} (${packed.size} bytes${if (asResource) ", as resource" else ""})")
    return r
}

/** A RESPONSE packet's plaintext: msgpack([request id, data]). */
internal fun LinkManager.handleResponsePacket(l: Link, plain: ByteArray) {
    val v = try { Msgpack.unpack(plain).first } catch (_: MsgpackException) { return } as? MpArray ?: return
    if (v.items.size < 2) return
    val id = (v.items[0] as? MpBin)?.value ?: return
    completeRequest(l, id, v.items[1])
}

internal fun LinkManager.completeRequest(l: Link, id: ByteArray, data: MpValue) {
    val r = l.mu.withLock { l.pendingRequests.remove(id.hex()) } ?: run {
        node().log("rns: response for unknown request ${id.hex().take(8)} on ${l.idHex.take(8)}")
        return
    }
    node().log("rns: response for ${r.requestIdHex.take(8)} ${r.path}")
    r.result.complete(data)
}

internal fun LinkManager.failRequest(r: RequestReceipt, why: String) {
    val was = r.link.mu.withLock { r.link.pendingRequests.remove(r.requestIdHex) }
    if (was != null) node().log("rns: request ${r.requestIdHex.take(8)} ${r.path} failed: $why")
    r.result.completeExceptionally(RequestFailed(why))
}

internal fun LinkManager.pendingRequest(l: Link, id: ByteArray): RequestReceipt? = l.mu.withLock { l.pendingRequests[id.hex()] }

/** Times out requests that never saw a response start. */
internal fun LinkManager.requestWatchdog(nowMs: Long) {
    for (l in all()) {
        val expired = l.mu.withLock { l.pendingRequests.values.filter { !it.responseStarted && nowMs > it.deadlineMs } }
        for (r in expired) failRequest(r, "request timed out")
    }
}

internal fun LinkManager.failRequestsOn(l: Link, why: String) {
    val rs = l.mu.withLock { l.pendingRequests.values.toList() }
    for (r in rs) failRequest(r, why)
}
