package net.meshsat.android.lxmf

import net.meshsat.android.rns.Destination
import net.meshsat.android.rns.Link
import net.meshsat.android.rns.LinkState
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.Node
import net.meshsat.android.rns.Packet
import net.meshsat.android.rns.Receipt
import net.meshsat.android.rns.Rns
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.announce
import net.meshsat.android.rns.crossesPaidLink
import net.meshsat.android.rns.hex
import net.meshsat.android.rns.requestPath
import net.meshsat.android.rns.sendData
import net.meshsat.android.rns.sendResource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The LXMF endpoint (LXMF/LXMRouter.py 1.2.0): one lxmf.delivery destination on
 * the node with ratchets and a display name, inbound delivery with stamp checks
 * and dedup, and an outbound queue that delivers opportunistically (one packet),
 * directly (a link, reused, a resource for large messages) or through a
 * propagation node, with upstream's attempt limits and retry scheduling.
 * [MESHSAT-1553]
 *
 * One message the app sends is one [Outbound] whose [Outbound.state] moves
 * OUTBOUND -> SENDING -> SENT -> DELIVERED, or FAILED; the app's own ledger
 * records that, it does not retry the LXMF message itself (LXMF already did).
 */
class LxmfRouter(val node: Node, cfg: Config) {
    class Config(
        val identity: RnsIdentity,
        val displayName: String,
        /** Announced for inbound messages (0 = none). */
        val stampCost: Int = 0,
        /** Drops inbound messages with a missing or invalid stamp. */
        val enforceStamps: Boolean = false,
        /** Refuses destinations that demand more work than this. */
        val maxOutboundStampCost: Int = 16,
        /** The largest inbound resource accepted, bytes. */
        val deliveryLimit: Int = 1000 * 1000,
        /** Ratchets persisted from an earlier run (newest first) and when the newest was made. */
        val ratchets: List<ByteArray> = emptyList(),
        val ratchetsLatestMs: Long = 0,
        val onRatchetsChanged: ((List<ByteArray>, Long) -> Unit)? = null,
        /** Every accepted inbound message. */
        val onMessage: ((LxMessage) -> Unit)? = null,
        /** An outbound message changed state. */
        val onOutboundState: ((Outbound) -> Unit)? = null,
        val log: (String) -> Unit = { android.util.Log.d("LXMF", it) },
    )

    /** One message in the outbound queue. */
    class Outbound internal constructor(val message: LxMessage, val desiredMethod: LxMethod?) {
        @Volatile var state: LxState = LxState.OUTBOUND
            internal set
        @Volatile var method: LxMethod = desiredMethod ?: LxMethod.DIRECT
            internal set
        @Volatile var representation: Representation = Representation.PACKET
            internal set
        @Volatile var attempts = 0
            internal set
        @Volatile var maxAttempts = MAX_DELIVERY_ATTEMPTS
            internal set
        @Volatile var nextAttemptMs = 0L
            internal set
        @Volatile var awaitingPath = false
            internal set
        @Volatile var sentOnPath = false
            internal set
        @Volatile var pathRequestRetried = false
            internal set
        @Volatile var lastError: String = ""
            internal set
        @Volatile var deliveredAtMs = 0L
            internal set
        internal var receipt: Receipt? = null
        /** Completes with the final state (DELIVERED, FAILED, REJECTED or CANCELLED). */
        val result = CompletableFuture<LxState>()
        val hashHex: String get() = message.hashHex
    }

    enum class Representation { PACKET, RESOURCE }

    private val cfg = cfg
    private val log = cfg.log
    private val lock = ReentrantLock()
    private val seen = LinkedHashMap<String, Long>()
    private val directLinks = HashMap<String, Link>()        // links we initiated, by delivery hash
    private val backchannelLinks = HashMap<String, Link>()   // links peers initiated and identified on
    private val pendingOutbound = ArrayList<Outbound>()
    private val pathRequestTimes = HashMap<String, Long>()
    private var scheduler: ScheduledExecutorService? = null
    private var processing = false

    /** Our lxmf.delivery destination. */
    val dest: Destination = node.addDestination(Destination(LxMessage.DELIVERY_NAME, cfg.identity).also { d ->
        d.acceptLinks = true
        d.proveAll = true
        d.appData = { LxmfAppData.encode(cfg.displayName, cfg.stampCost, compression = false) }
        d.enableRatchets(cfg.ratchets, cfg.ratchetsLatestMs)
        d.onRatchetsChanged = cfg.onRatchetsChanged
        d.onPacket = { plain, pkt ->
            // Opportunistic: the packet carried packed[16:], prepend our hash (delivery_packet).
            inbound(d.hash + plain, LxMethod.OPPORTUNISTIC, pkt.iface)
        }
        d.onLink = { l -> l.onIdentified = { link, pub -> onIdentified(link, pub) } }
        d.onLinkPacket = { _, plain, pkt -> inbound(plain, LxMethod.DIRECT, pkt.iface) }
        d.onLinkClosed = { l -> forgetLink(l) }
        d.acceptResources = { size -> size in 1..cfg.deliveryLimit }
        d.onLinkResource = { l, data -> inbound(data, LxMethod.DIRECT, l.iface) }
    })

    val hash: ByteArray get() = dest.hash
    val hashHex: String get() = dest.hashHex
    val displayName: String get() = cfg.displayName
    val stampCost: Int get() = cfg.stampCost

    fun start() {
        if (scheduler != null) return
        val s = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lxmf-jobs").apply { isDaemon = true } }
        scheduler = s
        s.scheduleWithFixedDelay({ try { processOutbound() } catch (e: Exception) { log("lxmf: outbound processing failed: $e") } }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    /** Emits the delivery announce on every floodable interface and every paid interface open to it. */
    fun announce(): Boolean = node.announce(dest)

    // ---------------------------------------------------------------------------
    // Inbound
    // ---------------------------------------------------------------------------

    private fun sourceSigPub(source: ByteArray): ByteArray? = node.recall(source)?.identity?.sigPubRaw

    /** Validates and delivers a packed message (LXMRouter.lxmf_delivery). Returns it, or null when dropped. */
    fun inbound(packed: ByteArray, method: LxMethod, iface: String): LxMessage? {
        val m = try { LxMessage.unpack(packed, ::sourceSigPub) } catch (e: LxmfException) { log("lxmf: could not assemble message: ${e.message}"); return null }
        if (!m.dest.contentEquals(dest.hash)) { log("lxmf: message for ${m.destHex.take(8)}, not for us"); return null }
        m.method = method
        m.iface = iface
        m.receivedAtMs = System.currentTimeMillis()
        if (cfg.stampCost > 0) {
            m.stampChecked = true
            m.stamp?.let { s ->
                val wb = LxStamper.workblock(m.hash)
                m.stampValid = LxStamper.stampValid(wb, s, cfg.stampCost)
                if (m.stampValid) m.stampValue = LxStamper.stampValue(wb, s)
            }
            if (!m.stampValid && cfg.enforceStamps) { log("lxmf: dropping $m with invalid stamp"); return null }
        }
        lock.withLock {
            if (seen.containsKey(m.hashHex)) return m   // already delivered
            seen[m.hashHex] = System.currentTimeMillis()
            if (seen.size > 10_000) { val iter = seen.entries.iterator(); repeat(1000) { if (iter.hasNext()) { iter.next(); iter.remove() } } }
        }
        if (!m.sourceKnown) {
            // Ask for the source so the next message verifies; this one is still delivered, flagged unverified, as upstream does.
            requestPathThrottled(m.source)
        }
        log("lxmf: message $m from ${m.sourceHex.take(8)} via ${method.name.lowercase()} valid=${m.signatureValid} bytes=${m.content.size}")
        cfg.onMessage?.invoke(m)
        return m
    }

    /** A peer's link became a backchannel to its delivery destination (delivery_remote_identified). */
    private fun onIdentified(l: Link, pub: ByteArray) {
        val id = RnsIdentity.fromPublicBytesOrNull(pub) ?: return
        val peer = id.destHash(LxMessage.DELIVERY_NAME)
        lock.withLock { backchannelLinks[peer.hex()] = l }
        node.remember(peer, pub)
        log("lxmf: backchannel from ${peer.hex().take(8)} on ${l.idHex.take(8)}")
        trigger()
    }

    private fun forgetLink(l: Link) = lock.withLock {
        directLinks.entries.removeAll { it.value === l }
        backchannelLinks.entries.removeAll { it.value === l }
    }

    /** Whether a link to [peer] exists (delivery_link_available). */
    fun deliveryLinkAvailable(peer: ByteArray): Boolean = lock.withLock {
        val k = peer.hex()
        directLinks[k]?.state == LinkState.ACTIVE || backchannelLinks[k]?.state == LinkState.ACTIVE
    }

    // ---------------------------------------------------------------------------
    // Outbound
    // ---------------------------------------------------------------------------

    /** What a peer announced: name and stamp cost, from the node's known identities. */
    fun peerInfo(peer: ByteArray): LxmfAppData.Info? = node.recall(peer)?.let { LxmfAppData.parse(it.appData) }

    /**
     * Queues one message. [method] null picks like Sideband: opportunistic when it
     * fits one packet, no delivery link exists and the peer announced a ratchet, else
     * direct; and always direct when the path crosses a paid link (the satellite),
     * because opportunistic retries would each be a paid packet and a stock client's
     * receipt timeout is shorter than the round trip. The stamp, when the peer
     * demands one, is generated here before the message is queued.
     */
    fun send(peer: ByteArray, content: ByteArray, title: ByteArray = ByteArray(0), fields: MpMap = MpMap(emptyList()), method: LxMethod? = null): Outbound {
        val info = peerInfo(peer)
        val cost = info?.stampCost ?: 0
        if (cost > cfg.maxOutboundStampCost) throw LxmfException("destination requires stamp cost $cost, above the limit of ${cfg.maxOutboundStampCost}")
        val m = LxMessage(peer.copyOf(), dest.hash, title = title, content = content, fields = fields)
        m.pack(cfg.identity, cost) { h, c -> LxStamper.generateStamp(h, c)?.first }
        val o = Outbound(m, method)
        val size = m.contentSize()
        var chosen = method
        if (chosen == null) {
            chosen = LxMethod.DIRECT
            val k = node.recall(peer)
            if (size <= LxMessage.ENCRYPTED_PACKET_MAX_CONTENT && !deliveryLinkAvailable(peer) && k?.ratchet != null && !node.crossesPaidLink(peer)) {
                chosen = LxMethod.OPPORTUNISTIC
            }
        }
        if (chosen == LxMethod.OPPORTUNISTIC && size > LxMessage.ENCRYPTED_PACKET_MAX_CONTENT) chosen = LxMethod.DIRECT
        o.method = chosen
        o.representation = if (chosen == LxMethod.DIRECT && size > LxMessage.LINK_PACKET_MAX_CONTENT) Representation.RESOURCE else Representation.PACKET
        if (chosen == LxMethod.PROPAGATED) throw LxmfException("propagated delivery is not available yet")
        lock.withLock { pendingOutbound.add(o) }
        // Pre-emptively request an unknown path for an opportunistic message (handle_outbound).
        if (chosen == LxMethod.OPPORTUNISTIC && !node.paths.has(peer)) {
            requestPathThrottled(peer)
            scheduleAttempt(o, peer, pathRequest = true)
        }
        log("lxmf: queued $m to ${peer.hex().take(8)} ${chosen.name.lowercase()} ${o.representation.name.lowercase()} stamp_cost=$cost")
        cfg.onOutboundState?.invoke(o)
        trigger()
        return o
    }

    fun cancel(o: Outbound) {
        if (o.state.terminal) return
        setState(o, LxState.CANCELLED)
    }

    fun pending(): List<Outbound> = lock.withLock { pendingOutbound.toList() }

    private fun trigger() { scheduler?.execute { try { processOutbound() } catch (e: Exception) { log("lxmf: outbound processing failed: $e") } } }

    private fun setState(o: Outbound, s: LxState, error: String = "") {
        if (o.state == s) return
        o.state = s
        if (error.isNotEmpty()) o.lastError = error
        if (s == LxState.DELIVERED) o.deliveredAtMs = System.currentTimeMillis()
        cfg.onOutboundState?.invoke(o)
        if (s.terminal) o.result.complete(s)
    }

    private fun failMessage(o: Outbound, why: String) {
        lock.withLock { pendingOutbound.remove(o) }
        log("lxmf: ${o.message} failed: $why")
        setState(o, if (o.state == LxState.REJECTED) LxState.REJECTED else LxState.FAILED, why)
    }

    /** The round trip a path promises: 6 s a hop each way (destination_round_trip, a kit's 9704 hops count). */
    private fun roundTripMs(peer: ByteArray): Long {
        val hops = node.paths.hops(peer)
        if (hops < 1) return 0
        return 2L * Rns.PER_HOP_TIMEOUT * hops * 1000
    }

    private fun pathRequestWaitMs(): Long = maxOf(PATH_REQUEST_WAIT_MS, mediumPathTimeoutMs())

    /** The longest round trip among known paths, capped: what a path request may take (medium_path_timeout). */
    private fun mediumPathTimeoutMs(): Long {
        val worst = node.paths.all().maxOfOrNull { it.hops } ?: 1
        return minOf(2L * Rns.PER_HOP_TIMEOUT * worst * 1000, 120_000L)
    }

    private fun scheduleAttempt(o: Outbound, peer: ByteArray, pathRequest: Boolean = false) {
        val now = System.currentTimeMillis()
        val roundTrip: Long
        if (pathRequest) {
            roundTrip = mediumPathTimeoutMs()
            o.nextAttemptMs = now + maxOf(PATH_REQUEST_WAIT_MS, roundTrip)
            o.awaitingPath = true
        } else {
            roundTrip = roundTripMs(peer)
            o.nextAttemptMs = now + maxOf(DELIVERY_RETRY_WAIT_MS, roundTrip)
        }
        o.maxAttempts = if (roundTrip > DELIVERY_RETRY_WAIT_MS) maxOf(3, Math.round(MAX_DELIVERY_ATTEMPTS * DELIVERY_RETRY_WAIT_MS.toDouble() / roundTrip).toInt()) else MAX_DELIVERY_ATTEMPTS
    }

    private fun attemptDue(o: Outbound, peer: ByteArray): Boolean {
        if (o.nextAttemptMs == 0L) return true
        if (System.currentTimeMillis() > o.nextAttemptMs) return true
        return o.awaitingPath && node.paths.has(peer)
    }

    private fun requestPathThrottled(peer: ByteArray): Boolean {
        val window = pathRequestWaitMs()
        val k = peer.hex()
        val now = System.currentTimeMillis()
        lock.withLock {
            val last = pathRequestTimes[k]
            if (last != null && now - last < window) return false
            pathRequestTimes.entries.removeAll { now - it.value >= window }
            pathRequestTimes[k] = now
        }
        node.requestPath(peer, window)
        return true
    }

    private fun pathRequestPending(peer: ByteArray): Boolean = lock.withLock {
        val t = pathRequestTimes[peer.hex()] ?: return false
        System.currentTimeMillis() - t < pathRequestWaitMs()
    }

    /** LXMRouter.process_outbound. */
    internal fun processOutbound() {
        lock.withLock { if (processing) return; processing = true }
        try {
            val list = lock.withLock { pendingOutbound.toList() }
            for (o in list) {
                when {
                    o.state == LxState.DELIVERED -> {
                        lock.withLock { pendingOutbound.remove(o) }
                        // Backchannel: identify on the link we opened so the peer can answer on it.
                        val peerKey = o.message.destHex
                        val l = lock.withLock { directLinks[peerKey] }
                        if (o.method == LxMethod.DIRECT && l != null && l.initiator && l.remoteIdentity == null && !identified.contains(l.idHex) && l.state == LinkState.ACTIVE) {
                            try { node.links.identify(l, cfg.identity); identified.add(l.idHex); log("lxmf: identified on ${l.idHex.take(8)} for the backchannel") } catch (e: Exception) { log("lxmf: identify failed: $e") }
                        }
                    }
                    o.state == LxState.CANCELLED || o.state == LxState.REJECTED -> failMessage(o, o.state.name.lowercase())
                    o.method == LxMethod.OPPORTUNISTIC -> processOpportunistic(o)
                    o.method == LxMethod.DIRECT -> processDirect(o)
                    else -> failMessage(o, "unsupported method")
                }
            }
        } finally {
            lock.withLock { processing = false }
        }
    }

    private val identified = HashSet<String>()

    private fun processOpportunistic(o: Outbound) {
        val peer = o.message.dest
        if (o.attempts <= o.maxAttempts) {
            if (!attemptDue(o, peer)) return
            val pathKnown = node.paths.has(peer)
            o.awaitingPath = false
            if (o.attempts >= MAX_PATHLESS_TRIES && !pathKnown) {
                log("lxmf: requesting path to ${peer.hex().take(8)} after ${o.attempts} pathless tries for ${o.message}")
                o.attempts++
                o.sentOnPath = false
                requestPathThrottled(peer)
                scheduleAttempt(o, peer, pathRequest = true)
            } else if (o.attempts == MAX_PATHLESS_TRIES + 1 && pathKnown && o.sentOnPath) {
                // Only rediscover if the previous attempt was sent along the same path.
                o.attempts++
                o.sentOnPath = false
                o.awaitingPath = true
                if (!pathRequestPending(peer)) {
                    node.paths.remove(peer)
                    lock.withLock { pathRequestTimes[peer.hex()] = System.currentTimeMillis() }
                    node.requestPath(peer, pathRequestWaitMs())
                }
                o.nextAttemptMs = System.currentTimeMillis() + pathRequestWaitMs()
            } else {
                o.attempts++
                o.sentOnPath = pathKnown
                scheduleAttempt(o, peer)
                log("lxmf: opportunistic attempt ${o.attempts} for ${o.message} to ${peer.hex().take(8)}")
                try {
                    val r = node.sendData(peer, o.message.packed.copyOfRange(LxMessage.DEST_LEN, o.message.packed.size), wantReceipt = true)
                    o.receipt = r
                    setState(o, LxState.SENT)
                    r?.result?.thenAccept { res -> if (res.delivered && !o.state.terminal) { setState(o, LxState.DELIVERED); trigger() } }
                } catch (e: Exception) {
                    log("lxmf: error sending ${o.message}: ${e.message}")
                    failMessage(o, e.message ?: "send failed")
                }
            }
        } else if (attemptDue(o, peer)) {
            failMessage(o, "max delivery attempts reached (opportunistic)")
        }
    }

    private fun processDirect(o: Outbound) {
        val peer = o.message.dest
        val peerKey = peer.hex()
        if (o.attempts > o.maxAttempts) {
            if (attemptDue(o, peer)) failMessage(o, "max delivery attempts reached (direct)")
            return
        }
        val link = lock.withLock { directLinks[peerKey] ?: backchannelLinks[peerKey] }
        if (link != null) {
            when (link.state) {
                LinkState.ACTIVE, LinkState.STALE -> {
                    if (o.state != LxState.SENDING) {
                        log("lxmf: starting transfer of ${o.message} on ${link.idHex.take(8)} (${o.representation.name.lowercase()})")
                        setState(o, LxState.SENDING)
                        try {
                            if (o.representation == Representation.PACKET) {
                                val r = node.links.send(link, o.message.packed)
                                o.receipt = r
                                r.result.thenAccept { res ->
                                    if (o.state.terminal) return@thenAccept
                                    if (res.delivered) { setState(o, LxState.DELIVERED); trigger() }
                                    else {
                                        // __link_packet_timed_out: tear the link down, back to OUTBOUND, try again.
                                        log("lxmf: link packet for ${o.message} timed out, tearing ${link.idHex.take(8)} down")
                                        node.links.teardown(link)
                                        setState(o, LxState.OUTBOUND)
                                        trigger()
                                    }
                                }
                            } else {
                                val res = node.links.sendResource(link, o.message.packed)
                                res.result.thenAccept { out ->
                                    if (o.state.terminal) return@thenAccept
                                    if (out.delivered) { setState(o, LxState.DELIVERED); trigger() }
                                    else if (out.error == "rejected by receiver") { setState(o, LxState.REJECTED, out.error); trigger() }
                                    else {
                                        log("lxmf: resource for ${o.message} failed (${out.error}), tearing ${link.idHex.take(8)} down")
                                        node.links.teardown(link)
                                        setState(o, LxState.OUTBOUND, out.error ?: "")
                                        trigger()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            log("lxmf: error sending ${o.message}: ${e.message}")
                            failMessage(o, e.message ?: "send failed")
                        }
                    }
                }
                LinkState.CLOSED -> {
                    if (link.established.isDone && !link.established.isCompletedExceptionally) {
                        log("lxmf: link to ${peerKey.take(8)} closed unexpectedly, retrying the path request")
                        requestPathThrottled(peer)
                    } else if (!o.pathRequestRetried) {
                        log("lxmf: link to ${peerKey.take(8)} was never activated, retrying the path request")
                        requestPathThrottled(peer)
                        o.pathRequestRetried = true
                    }
                    forgetLink(link)
                    if (o.state == LxState.SENDING) setState(o, LxState.OUTBOUND)
                    if (o.attempts + 1 < o.maxAttempts) scheduleAttempt(o, peer)
                }
                else -> { /* pending or in handshake: wait for it to become active or close */ }
            }
            return
        }
        // No link: establish one if the retry wait has elapsed.
        if (!attemptDue(o, peer)) return
        o.attempts++
        o.awaitingPath = false
        scheduleAttempt(o, peer)
        if (o.attempts < o.maxAttempts) {
            if (node.paths.has(peer)) {
                log("lxmf: establishing link to ${peerKey.take(8)} for delivery attempt ${o.attempts}")
                try {
                    val l = node.links.initiate(peer)
                    l.onPacket = { _, plain, pkt -> inbound(plain, LxMethod.DIRECT, pkt.iface) }
                    l.acceptResources = { size -> size in 1..cfg.deliveryLimit }
                    l.onResource = { ll, data -> inbound(data, LxMethod.DIRECT, ll.iface) }
                    l.onEstablished = { trigger() }
                    l.onClosed = { ll -> forgetLink(ll); trigger() }
                    lock.withLock { directLinks[peerKey] = l }
                } catch (e: Exception) {
                    log("lxmf: link to ${peerKey.take(8)} not started: ${e.message}")
                }
            } else {
                log("lxmf: no path for delivery attempt ${o.attempts} to ${peerKey.take(8)}, requesting one")
                requestPathThrottled(peer)
                scheduleAttempt(o, peer, pathRequest = true)
            }
        } else {
            failMessage(o, "max delivery attempts reached (direct)")
        }
    }

    /** Known lxmf.delivery peers from the path table. */
    data class Peer(val destHash: String, val displayName: String?, val stampCost: Int, val hops: Int, val iface: String, val lastSeenMs: Long, val hasRatchet: Boolean)

    fun peers(): List<Peer> {
        val nameHash = Rns.nameHash(LxMessage.DELIVERY_NAME)
        return node.paths.all().filter { it.announce.nameHash.contentEquals(nameHash) }.map { e ->
            val k = node.recall(e.destHash)
            val info = LxmfAppData.parse(k?.appData)
            Peer(e.destHash.hex(), info.displayName, info.stampCost, e.hops, e.iface, k?.lastSeenMs ?: 0, k?.ratchet != null)
        }
    }

    companion object {
        const val PATH_REQUEST_WAIT_MS = 7_000L
        const val DELIVERY_RETRY_WAIT_MS = 10_000L
        const val MAX_DELIVERY_ATTEMPTS = 5
        const val MAX_PATHLESS_TRIES = 1
    }
}
