package net.meshsat.android.rns

import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * RNS Resource transfer over a link (RNS/Resource.py): the sender encrypts the
 * whole payload once with the link key, splits the ciphertext into parts of SDU
 * bytes, advertises hash, size and a map of 4-byte part hashes, and serves parts
 * on request; the receiver requests windows of parts, reassembles, decrypts,
 * verifies the hash and returns a proof. Single segment only (payloads up to
 * 1 MiB), never compressed on send; a compressed resource is refused on receive
 * (the delivery announce says so, LXMF then sends uncompressed).
 */
private const val MAP_HASH_LEN = 4
private const val RANDOM_HASH_LEN = 4
private const val WINDOW = 4
private const val WINDOW_MIN = 2
private const val WINDOW_MAX_SLOW = 10
private const val WINDOW_MAX_FAST = 75
private const val WINDOW_FLEX = 4
private const val ADV_OVERHEAD = 134
private const val MAX_ADV_RETRIES = 4
private const val MAX_RETRIES = 16
private const val MAX_SIZE = 1 * 1024 * 1024 - 1
private const val HASHMAP_NOT_EXHAUSTED = 0x00
private const val HASHMAP_EXHAUSTED = 0xFF
private const val PROCESSING_GRACE_MS = 1000L
private const val RETRY_GRACE_MS = 250L
private const val PER_RETRY_DELAY_MS = 500L
private const val SENDER_GRACE_MS = 10_000L
private const val HASHMAP_MAX_LEN = (431 - ADV_OVERHEAD) / MAP_HASH_LEN   // 74 at MTU 500
private const val COLLISION_GUARD = 2 * WINDOW_MAX_FAST + HASHMAP_MAX_LEN

data class ResourceResult(val delivered: Boolean, val error: String? = null)

/** A resource we are sending on a link. */
class OutgoingResource internal constructor(internal val link: Link, internal val data: ByteArray, internal val dataSize: Int) {
    lateinit var hash: ByteArray
        internal set
    internal val mu = ReentrantLock()
    internal val parts = ArrayList<ByteArray>()
    internal val mapHashes = ArrayList<ByteArray>()
    internal var partSent = BooleanArray(0)
    internal var randomHash = ByteArray(RANDOM_HASH_LEN)
    internal var expectedProof = ByteArray(0)
    internal var sentParts = 0
    internal var advRetries = 0
    internal var advSentMs = 0L
    internal var lastActivityMs = 0L
    internal var rttMs = 0L
    internal var state = 0   // 0 advertised, 1 transferring, 2 awaiting proof, 3 complete, 4 failed
    internal var recvMinHeight = 0
    /** Resolves once the receiver proved the resource or the transfer failed. */
    val result = CompletableFuture<ResourceResult>()
    val hashHex: String get() = hash.hex()
}

/** A resource being received on a link. */
class IncomingResource internal constructor(internal val link: Link, val hash: ByteArray, internal val size: Int, val dataSize: Int, internal val totalParts: Int) {
    internal val mu = ReentrantLock()
    internal val parts = arrayOfNulls<ByteArray>(totalParts)
    internal val hashmap = Array(totalParts) { ByteArray(0) }
    internal val hashmapSet = BooleanArray(totalParts)
    internal var hashHeight = 0
    internal var randomHash = ByteArray(RANDOM_HASH_LEN)
    internal var compressed = false
    internal var received = 0
    internal var outstanding = 0
    internal var window = WINDOW
    internal var windowMax = WINDOW_MAX_SLOW
    internal var windowMin = WINDOW_MIN
    internal var consecutive = -1
    internal var waitingHmu = false
    internal var retries = 0
    internal var lastActivityMs = 0L
    internal var reqSentMs = 0L
    internal var state = 0   // 0 transferring, 1 assembling, 2 complete, 3 failed
    var data: ByteArray? = null
        internal set
    val hashHex: String get() = hash.hex()
}

/** Per-link resource state. */
internal class LinkResources {
    val mu = ReentrantLock()
    val incoming = HashMap<String, IncomingResource>()
    val outgoing = HashMap<String, OutgoingResource>()
}

private fun mapHash(part: ByteArray, random: ByteArray): ByteArray = Rns.sha256(part, random).copyOf(MAP_HASH_LEN)

private fun sduOf(l: Link): Int = l.mtu - Rns.HEADER_MAXSIZE - Rns.IFAC_MIN_SIZE

/** Advertises [payload] on the link and serves it; the result resolves when the receiver proves it. */
fun LinkManager.sendResource(l: Link, payload: ByteArray): OutgoingResource {
    if (l.state != LinkState.ACTIVE && l.state != LinkState.STALE) throw IllegalStateException("link not active")
    if (payload.size > MAX_SIZE) throw IllegalArgumentException("resource larger than one segment (1 MiB)")
    val plain = Rns.randomBytes(RANDOM_HASH_LEN) + payload
    val enc = l.encrypt(plain)
    val sdu = sduOf(l)
    val r = OutgoingResource(l, enc, payload.size)
    r.rttMs = l.rttMs
    while (true) {
        r.randomHash = Rns.randomBytes(RANDOM_HASH_LEN)
        r.hash = Rns.sha256(payload, r.randomHash)
        r.expectedProof = Rns.sha256(payload, r.hash)
        r.parts.clear(); r.mapHashes.clear()
        var collision = false
        val guard = HashSet<String>()
        var i = 0
        while (i < enc.size) {
            val end = minOf(i + sdu, enc.size)
            val part = enc.copyOfRange(i, end)
            val mh = mapHash(part, r.randomHash)
            if (!guard.add(mh.hex())) { collision = true; break }
            r.parts.add(part); r.mapHashes.add(mh)
            i = end
        }
        if (!collision) break
    }
    r.partSent = BooleanArray(r.parts.size)
    val rs = l.res()
    rs.mu.withLock { rs.outgoing[r.hashHex] = r }
    advertise(r)
    return r
}

private fun OutgoingResource.advertisement(segment: Int): MpValue {
    val start = segment * HASHMAP_MAX_LEN
    val end = minOf((segment + 1) * HASHMAP_MAX_LEN, parts.size)
    var hm = ByteArray(0)
    for (i in start until end) hm += mapHashes[i]
    return MpMap(
        MpStr("t") to MpInt(data.size.toLong()),
        MpStr("d") to MpInt(dataSize.toLong()),
        MpStr("n") to MpInt(parts.size.toLong()),
        MpStr("h") to MpBin(hash),
        MpStr("r") to MpBin(randomHash),
        MpStr("o") to MpBin(hash),
        MpStr("i") to MpInt(1),
        MpStr("l") to MpInt(1),
        MpStr("q") to MpNil,
        MpStr("f") to MpInt(0x01),   // encrypted, not compressed, not split, no metadata
        MpStr("m") to MpBin(hm),
    )
}

internal fun LinkManager.advertise(r: OutgoingResource) {
    val adv = Msgpack.pack(r.advertisement(0))
    val ct = r.link.encrypt(adv)
    sendContext(r.link, Rns.PACKET_DATA, Rns.CTX_RESOURCE_ADV, ct)
    r.mu.withLock { r.advSentMs = node().now(); r.lastActivityMs = r.advSentMs }
}

internal fun OutgoingResource.fail(err: String) {
    mu.withLock { if (state >= 3) return; state = 4 }
    val rs = link.res()
    rs.mu.withLock { rs.outgoing.remove(hashHex) }
    result.complete(ResourceResult(false, err))
}

/** Serves a RESOURCE_REQ (decrypted plaintext). */
internal fun LinkManager.handleResourceRequest(l: Link, plain: ByteArray) {
    if (plain.size < 1 + Rns.FULL_HASH_LEN) return
    val exhausted = (plain[0].toInt() and 0xFF) == HASHMAP_EXHAUSTED
    var pad = 1
    var lastMap = ByteArray(0)
    if (exhausted) {
        if (plain.size < 1 + MAP_HASH_LEN + Rns.FULL_HASH_LEN) return
        lastMap = plain.copyOfRange(1, 1 + MAP_HASH_LEN)
        pad = 1 + MAP_HASH_LEN
    }
    val hash = plain.copyOfRange(pad, pad + Rns.FULL_HASH_LEN)
    val rs = l.res()
    val r = rs.mu.withLock { rs.outgoing[hash.hex()] } ?: return
    val toSend = ArrayList<ByteArray>()
    var hmu: ByteArray? = null
    val nowMs = node().now()
    r.mu.withLock {
        if (r.rttMs == 0L) r.rttMs = nowMs - r.advSentMs
        r.state = 1
        r.lastActivityMs = nowMs
        val wanted = plain.copyOfRange(pad + Rns.FULL_HASH_LEN, plain.size)
        val want = HashSet<String>()
        var i = 0
        while (i + MAP_HASH_LEN <= wanted.size) { want.add(wanted.copyOfRange(i, i + MAP_HASH_LEN).hex()); i += MAP_HASH_LEN }
        val start = r.recvMinHeight
        val end = minOf(start + COLLISION_GUARD, r.parts.size)
        for (j in start until end) {
            if (r.mapHashes[j].hex() in want) {
                toSend.add(r.parts[j])
                if (!r.partSent[j]) { r.partSent[j] = true; r.sentParts++ }
            }
        }
        if (exhausted) {
            var idx = r.recvMinHeight
            for (j in r.recvMinHeight until end) {
                idx++
                if (r.mapHashes[j].contentEquals(lastMap)) break
            }
            r.recvMinHeight = maxOf(idx - 1 - WINDOW_MAX_FAST, 0)
            if (idx % HASHMAP_MAX_LEN != 0) {
                node().log("rns: resource sequencing error, cancelling")
                r.mu.unlock(); r.fail("resource sequencing error"); r.mu.lock()
                return
            }
            val segment = idx / HASHMAP_MAX_LEN
            val hs = segment * HASHMAP_MAX_LEN
            val he = minOf((segment + 1) * HASHMAP_MAX_LEN, r.parts.size)
            var hm = ByteArray(0)
            for (j in hs until he) hm += r.mapHashes[j]
            if (hm.isEmpty()) { r.mu.unlock(); r.fail("resource HMU error"); r.mu.lock(); return }
            hmu = r.hash + Msgpack.pack(MpArray(MpInt(segment.toLong()), MpBin(hm)))
        }
        if (r.sentParts == r.parts.size) r.state = 2
    }
    for (p in toSend) sendContext(l, Rns.PACKET_DATA, Rns.CTX_RESOURCE, p)
    hmu?.let { sendContext(l, Rns.PACKET_DATA, Rns.CTX_RESOURCE_HMU, l.encrypt(it)) }
}

/** Validates a RESOURCE_PRF for an outgoing resource. */
internal fun LinkManager.handleResourceProof(l: Link, data: ByteArray) {
    if (data.size != 2 * Rns.FULL_HASH_LEN) return
    val hash = data.copyOfRange(0, Rns.FULL_HASH_LEN)
    val rs = l.res()
    val r = rs.mu.withLock { rs.outgoing[hash.hex()] } ?: return
    if (!data.copyOfRange(Rns.FULL_HASH_LEN, data.size).contentEquals(r.expectedProof)) return
    r.mu.withLock { r.state = 3 }
    rs.mu.withLock { rs.outgoing.remove(r.hashHex) }
    l.noteProof(node().now())
    r.result.complete(ResourceResult(true))
}

/** Accepts an advertisement (decrypted) when the link allows resources, then requests the first window. */
internal fun LinkManager.handleResourceAdv(l: Link, plain: ByteArray) {
    val v = try { Msgpack.unpack(plain).first } catch (_: MsgpackException) { return } as? MpMap ?: return
    val t = v["t"]?.asLong() ?: return
    val d = v["d"]?.asLong() ?: return
    val n = v["n"]?.asLong() ?: return
    val f = v["f"]?.asLong() ?: 0
    val segs = v["l"]?.asLong() ?: 1
    val h = (v["h"] as? MpBin)?.value ?: return
    val rb = (v["r"] as? MpBin)?.value ?: return
    val hm = (v["m"] as? MpBin)?.value ?: ByteArray(0)
    if (h.size != Rns.FULL_HASH_LEN || rb.size != RANDOM_HASH_LEN || n <= 0 || t <= 0 || t > 3L * MAX_SIZE) return
    if (segs > 1 || (f and 0x20L) != 0L) { node().log("rns: multi-segment or metadata resource not supported, rejecting"); rejectResource(l, h); return }
    if ((f and 0x02L) != 0L) { node().log("rns: compressed resource not supported, rejecting"); rejectResource(l, h); return }
    val accept = l.acceptResources
    if (accept == null || !accept(d.toInt())) { rejectResource(l, h); return }
    val sdu = sduOf(l)
    var totalParts = ((t + sdu - 1) / sdu).toInt()
    if (n.toInt() != totalParts) totalParts = n.toInt()
    val r = IncomingResource(l, h.copyOf(), t.toInt(), d.toInt(), totalParts)
    r.randomHash = rb.copyOf()
    r.lastActivityMs = node().now()
    val rs = l.res()
    rs.mu.withLock {
        if (rs.incoming.containsKey(r.hashHex)) return
        rs.incoming[r.hashHex] = r
    }
    node().log("rns: accepting resource on ${l.idHex.take(8)} size=${r.size} parts=${r.totalParts}")
    r.hashmapUpdate(0, hm)
    requestNext(r)
}

internal fun LinkManager.rejectResource(l: Link, hash: ByteArray) {
    sendContext(l, Rns.PACKET_DATA, Rns.CTX_RESOURCE_RCL, l.encrypt(hash))
}

internal fun IncomingResource.hashmapUpdate(segment: Int, hm: ByteArray) = mu.withLock {
    val count = hm.size / MAP_HASH_LEN
    for (i in 0 until count) {
        val idx = i + segment * HASHMAP_MAX_LEN
        if (idx >= totalParts) break
        if (!hashmapSet[idx]) hashHeight++
        hashmap[idx] = hm.copyOfRange(i * MAP_HASH_LEN, (i + 1) * MAP_HASH_LEN)
        hashmapSet[idx] = true
    }
    waitingHmu = false
}

internal fun LinkManager.handleResourceHmu(l: Link, plain: ByteArray) {
    if (plain.size <= Rns.FULL_HASH_LEN) return
    val hash = plain.copyOfRange(0, Rns.FULL_HASH_LEN)
    val rs = l.res()
    val r = rs.mu.withLock { rs.incoming[hash.hex()] } ?: return
    val v = try { Msgpack.unpack(plain, Rns.FULL_HASH_LEN).first } catch (_: MsgpackException) { return } as? MpArray ?: return
    if (v.items.size != 2) return
    val seg = v.items[0].asLong() ?: return
    val hm = (v.items[1] as? MpBin)?.value ?: return
    r.hashmapUpdate(seg.toInt(), hm)
    requestNext(r)
}

internal fun LinkManager.handleResourceCancel(l: Link, plain: ByteArray) {
    if (plain.size != Rns.FULL_HASH_LEN) return
    val rs = l.res()
    val key = plain.hex()
    val inc = rs.mu.withLock { rs.incoming[key] }
    val out = rs.mu.withLock { rs.outgoing[key] }
    inc?.let { concludeIncoming(it, null, "cancelled by sender") }
    out?.fail("rejected by receiver")
}

/** Asks for the next window of parts (Resource.request_next). */
internal fun LinkManager.requestNext(r: IncomingResource) {
    val data: ByteArray
    val l = r.link
    r.mu.withLock {
        if (r.state >= 1 || r.waitingHmu) return
        r.outstanding = 0
        var exhausted = false
        var req = ByteArray(0)
        var pn = r.consecutive + 1
        var i = 0
        while (pn < r.totalParts && i < r.window) {
            if (r.parts[pn] == null) {
                if (!r.hashmapSet[pn]) { exhausted = true; break }
                req += r.hashmap[pn]
                r.outstanding++
                i++
            }
            pn++
        }
        data = if (exhausted) {
            r.waitingHmu = true
            byteArrayOf(HASHMAP_EXHAUSTED.toByte()) + r.hashmap[r.hashHeight - 1] + r.hash + req
        } else {
            byteArrayOf(HASHMAP_NOT_EXHAUSTED.toByte()) + r.hash + req
        }
        r.lastActivityMs = node().now()
        r.reqSentMs = r.lastActivityMs
    }
    sendContext(l, Rns.PACKET_DATA, Rns.CTX_RESOURCE_REQ, l.encrypt(data))
}

/** Stores a RESOURCE part. */
internal fun LinkManager.receivePart(l: Link, part: ByteArray) {
    val rs = l.res()
    val cands = rs.mu.withLock { rs.incoming.values.toList() }
    for (r in cands) {
        var assemble = false
        var request = false
        r.mu.withLock {
            if (r.state >= 1) return@withLock
            val mh = mapHash(part, r.randomHash)
            val start = r.consecutive + 1
            var matched = false
            var idx = start
            while (idx < start + r.window && idx < r.totalParts) {
                if (r.hashmapSet[idx] && r.hashmap[idx].contentEquals(mh) && r.parts[idx] == null) {
                    r.parts[idx] = part.copyOf()
                    r.received++
                    r.outstanding--
                    var cp = r.consecutive + 1
                    while (cp < r.totalParts && r.parts[cp] != null) { r.consecutive = cp; cp++ }
                    matched = true
                    break
                }
                idx++
            }
            if (!matched) return@withLock
            r.lastActivityMs = node().now()
            r.retries = 0
            if (r.received == r.totalParts) { r.state = 1; assemble = true }
            else if (r.outstanding <= 0) {
                if (r.window < r.windowMax) {
                    r.window++
                    if (r.window - r.windowMin > WINDOW_FLEX - 1) r.windowMin++
                }
                request = true
            }
        }
        if (assemble) { assemble(r); return }
        if (request) { requestNext(r); return }
    }
}

internal fun LinkManager.assemble(r: IncomingResource) {
    var stream = ByteArray(0)
    for (p in r.parts) stream += (p ?: ByteArray(0))
    val plain = r.link.decrypt(stream)
    if (plain == null || plain.size < RANDOM_HASH_LEN) { concludeIncoming(r, null, "resource decrypt failed"); return }
    val data = plain.copyOfRange(RANDOM_HASH_LEN, plain.size)
    if (r.compressed) { concludeIncoming(r, null, "compressed resource unsupported"); return }
    val calc = Rns.sha256(data, r.randomHash)
    if (!calc.contentEquals(r.hash)) { concludeIncoming(r, null, "resource hash mismatch"); return }
    val proof = Rns.sha256(data, r.hash)
    sendContext(r.link, Rns.PACKET_PROOF, Rns.CTX_RESOURCE_PRF, r.hash + proof)
    concludeIncoming(r, data, null)
}

internal fun LinkManager.concludeIncoming(r: IncomingResource, data: ByteArray?, err: String?) {
    r.mu.withLock { if (err == null) { r.state = 2; r.data = data } else r.state = 3 }
    val rs = r.link.res()
    rs.mu.withLock { rs.incoming.remove(r.hashHex) }
    if (err != null) { node().log("rns: incoming resource on ${r.link.idHex.take(8)} failed: $err"); return }
    node().log("rns: resource received on ${r.link.idHex.take(8)}, ${data!!.size} bytes")
    r.link.onResource?.invoke(r.link, data)
    r.link.local?.onLinkResource?.invoke(r.link, data)
}

/** Retries requests and advertisements and times transfers out. */
internal fun LinkManager.resourceWatchdog(nowMs: Long) {
    for (l in all()) {
        val rs = l.res()
        val inc = rs.mu.withLock { rs.incoming.values.toList() }
        val out = rs.mu.withLock { rs.outgoing.values.toList() }
        var rtt = l.rttMs
        if (rtt < 100) rtt = 100
        for (r in inc) {
            var retry = false
            var timedOut = false
            r.mu.withLock {
                var wait = 4 * rtt + RETRY_GRACE_MS + r.retries * PER_RETRY_DELAY_MS
                if (wait < 2000) wait = 2000
                if (r.state == 0 && nowMs > r.lastActivityMs + wait) {
                    if (r.retries >= MAX_RETRIES) timedOut = true
                    else {
                        r.retries++
                        if (r.window > r.windowMin) r.window--
                        r.waitingHmu = false
                        r.lastActivityMs = nowMs
                        retry = true
                    }
                }
            }
            if (timedOut) concludeIncoming(r, null, "timed out waiting for parts")
            else if (retry) requestNext(r)
        }
        for (r in out) {
            var readvertise = false
            var failMsg: String? = null
            r.mu.withLock {
                when (r.state) {
                    0 -> if (nowMs > r.advSentMs + 6 * rtt + PROCESSING_GRACE_MS) {
                        if (r.advRetries >= MAX_ADV_RETRIES) failMsg = "no part requests after advertisement"
                        else { r.advRetries++; readvertise = true }
                    }
                    1, 2 -> if (nowMs > r.lastActivityMs + 6 * rtt * MAX_RETRIES + SENDER_GRACE_MS) failMsg = "timed out waiting for the receiver"
                }
            }
            failMsg?.let { r.fail(it) }
            if (readvertise) advertise(r)
        }
    }
}
