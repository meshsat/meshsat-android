package net.meshsat.android.rns

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The Reticulum node (RNS/Transport.py, RNS/Link.py) on top of the wire format
 * in Wire.kt: packet filtering and deduplication, the path table with RNS
 * replacement rules, announce rebroadcast as a transport node, HEADER_2
 * forwarding with link and reverse tables, path requests, links, packet proofs
 * and receipts, resources. A port of the Bridge's internal/rns, which is
 * verified against Python RNS by its interop tests; the Kotlin port is verified
 * the same way (the interop tests). [MESHSAT-1553]
 *
 * The node knows nothing about sockets or radios: a [Transmitter] puts raw
 * packets on named interfaces and the owner feeds received packets to [inbound].
 * Paid bearers never appear in [Transmitter.floodable], so nothing in here can
 * flood them; a [PaidPolicy] opens one to a few named destinations instead.
 *
 * Threading: [lock] guards the tables; callbacks run outside it, on whichever
 * thread fed the packet or on the jobs thread. Nothing transmits under the lock.
 */
interface Transmitter {
    /** Puts one raw packet on the named interface; false when it could not be sent. */
    fun transmit(ifaceId: String, raw: ByteArray): Boolean

    /** The online interfaces that may carry broadcast traffic (announces, path requests, pathless packets). Never a paid bearer. */
    fun floodable(): List<String>

    /** The interface's hardware MTU, or 0 when unknown. */
    fun hwMtu(ifaceId: String): Int = 0

    /** Bits per second, or 0 when unknown or effectively unlimited (no announce cap). */
    fun bitrate(ifaceId: String): Int = 0

    /** Whether a (paid) interface is up; a policy skips a down one instead of booking a send on it. */
    fun online(ifaceId: String): Boolean = true
}

class NodeConfig(
    val identity: RnsIdentity,
    /**
     * Interfaces whose HEADER_1 packets for a destination that is not local are
     * forwarded along a known path even though a stock node would not (its peers
     * cannot address a transport): the MeshSat extension for the Meshtastic bearer.
     */
    val forwardHeader1From: Set<String> = emptySet(),
    /** Overrides PATHFINDER_E (one week). */
    val pathTtlMs: Long = Rns.PATHFINDER_E * 1000L,
    val now: () -> Long = { System.currentTimeMillis() },
    val log: (String) -> Unit = { android.util.Log.d("RnsNode", it) },
)

/** A received packet handed to destination and link callbacks. */
class Packet(val raw: ByteArray, val hdr: Header, val hash: ByteArray, val iface: String) {
    val truncatedHash: ByteArray get() = hash.copyOf(Rns.HASH_LEN)
}

/** A local SINGLE destination the node answers for. */
class Destination(val name: String, identity: RnsIdentity? = null) {
    internal var identityOrNull: RnsIdentity? = identity
    /** The identity; the node's own when none was given, once added to a node. */
    val identity: RnsIdentity get() = identityOrNull ?: throw IllegalStateException("destination $name not added to a node yet")
    var hash: ByteArray = ByteArray(0)
        internal set
    val hashHex: String get() = hash.hex()

    /** Peers may open links to this destination. */
    var acceptLinks = false
    /** Every delivered packet is answered with a proof (LXMF needs it). */
    var proveAll = false
    /** Supplies the announce app data. */
    var appData: (() -> ByteArray?)? = null
    /** Decrypted single-packet data. */
    var onPacket: ((plain: ByteArray, pkt: Packet) -> Unit)? = null
    /** A peer's link to this destination became active. */
    var onLink: ((Link) -> Unit)? = null
    /** Decrypted data on links to this destination. */
    var onLinkPacket: ((Link, ByteArray, Packet) -> Unit)? = null
    var onLinkClosed: ((Link) -> Unit)? = null
    /** Whether resources advertised on links to this destination are received (null rejects them). */
    var acceptResources: ((size: Int) -> Boolean)? = null
    /** A completed resource on such a link. */
    var onLinkResource: ((Link, ByteArray) -> Unit)? = null

    // Ratchets (Destination.enable_ratchets / rotate_ratchets): newest first, null = off.
    private val ratchetLock = Any()
    private var ratchets: MutableList<ByteArray>? = null
    private var latestRatchetMs = 0L
    var ratchetIntervalMs: Long = RATCHET_INTERVAL_MS
    /** Called with the full list (newest first) and the newest's time after a rotation, for persistence. */
    var onRatchetsChanged: ((List<ByteArray>, Long) -> Unit)? = null

    val ratchetsEnabled: Boolean get() = synchronized(ratchetLock) { ratchets != null }

    /** Turns ratchets on, continuing from a persisted list (newest first) when there is one. */
    fun enableRatchets(existing: List<ByteArray> = emptyList(), latestMs: Long = 0L) = synchronized(ratchetLock) {
        ratchets = existing.filter { it.size == Rns.RATCHET_LEN }.toMutableList()
        latestRatchetMs = latestMs
    }

    /** The private keys, newest first, for decryption. */
    internal fun ratchetPrivs(): List<ByteArray> = synchronized(ratchetLock) { ratchets?.toList() ?: emptyList() }

    /** Rotates when the newest is older than the interval and returns the newest public key, or null when off. */
    internal fun rotateRatchets(nowMs: Long): ByteArray? {
        var changed: List<ByteArray>? = null
        val pub = synchronized(ratchetLock) {
            val list = ratchets ?: return null
            if (list.isEmpty() || nowMs > latestRatchetMs + ratchetIntervalMs) {
                list.add(0, RnsIdentity.generateRatchet())
                latestRatchetMs = nowMs
                while (list.size > RATCHET_COUNT) list.removeAt(list.size - 1)
                changed = list.toList()
            }
            RnsIdentity.ratchetPublic(list[0])
        }
        changed?.let { onRatchetsChanged?.invoke(it, latestRatchetMs) }
        return pub
    }

    fun latestRatchetPub(): ByteArray? = synchronized(ratchetLock) { ratchets?.firstOrNull()?.let { RnsIdentity.ratchetPublic(it) } }

    companion object {
        const val RATCHET_COUNT = 512
        const val RATCHET_INTERVAL_MS = 30L * 60 * 1000
    }
}

/** What an announce taught us about a remote destination. */
class KnownIdentity(val destHash: ByteArray) {
    var publicKey: ByteArray = ByteArray(0)
    var identity: RnsIdentity? = null
    var appData: ByteArray? = null
    var ratchet: ByteArray? = null      // 32-byte x25519 pub or null
    var ratchetAtMs: Long = 0
    var lastSeenMs: Long = 0
}

/** Monotonic counters. */
class Stats {
    var rxPackets = 0L; var txPackets = 0L
    var dropped = 0L; var filtered = 0L
    var announcesRx = 0L; var announcesTx = 0L
    var forwarded = 0L; var linkTransit = 0L
    var pathRequestsRx = 0L
    var ifacViolations = 0L
    /** Announces, path responses and path requests sent on a paid interface under its policy, and those held back. */
    var paidTx = 0L; var paidSuppressed = 0L

    fun copy(): Stats = Stats().also {
        it.rxPackets = rxPackets; it.txPackets = txPackets; it.dropped = dropped; it.filtered = filtered
        it.announcesRx = announcesRx; it.announcesTx = announcesTx; it.forwarded = forwarded; it.linkTransit = linkTransit
        it.pathRequestsRx = pathRequestsRx; it.ifacViolations = ifacViolations; it.paidTx = paidTx; it.paidSuppressed = paidSuppressed
    }
}

class Node(val cfg: NodeConfig, internal val tx: Transmitter) {
    init { require(cfg.identity.hasPrivateKey) { "node identity needs a private key" } }

    val identity: RnsIdentity get() = cfg.identity
    /** The transport id this node writes into HEADER_2 packets. */
    val idHash: ByteArray = cfg.identity.hash
    val idHashHex: String get() = idHash.hex()

    internal val lock = ReentrantLock()
    internal fun now(): Long = cfg.now()
    internal fun log(msg: String) = cfg.log(msg)

    internal val dests = HashMap<String, Destination>()
    val paths = PathTable(cfg.pathTtlMs, cfg.now)
    internal val identities = HashMap<String, KnownIdentity>()
    internal var hashlist = HashSet<String>()
    internal var hashlistPrev = HashSet<String>()

    internal val announceTable = HashMap<String, AnnounceEntry>()
    internal val heldAnnounces = HashMap<String, AnnounceEntry>()
    internal val announceQueues = HashMap<String, AnnounceQueue>()

    internal val linkTable = HashMap<String, LinkEntry>()
    internal val reverseTable = HashMap<String, ReverseEntry>()

    internal val discoveryPRs = HashMap<String, DiscoveryEntry>()
    internal var prTags = HashSet<String>()
    internal var prTagsPrev = HashSet<String>()
    internal val inflightPRs = HashMap<String, Long>()
    internal val pathWaiters = HashMap<String, MutableList<java.util.concurrent.CompletableFuture<Boolean>>>()

    internal val receipts = HashMap<String, Receipt>()
    internal val ifacs = HashMap<String, Ifac>()

    internal val paid = HashMap<String, PaidPolicy>()
    internal val paidLast = HashMap<PaidKey, Long>()
    internal val crossings = HashMap<CrossingKey, Long>()

    val links = LinkManager(this)

    /** Every valid announce accepted into the path table (not our own destinations). */
    var onAnnounce: ((Announce, Packet) -> Unit)? = null
    /** A path table entry was added or replaced. */
    var onPathChanged: ((dest: ByteArray, entry: PathEntry) -> Unit)? = null
    /** A data packet crossed a paid link with a policy, at most once a minute per interface, destination and direction. */
    var onPaidCrossing: ((PaidCrossing) -> Unit)? = null

    internal val statsRaw = Stats()
    fun stats(): Stats = lock.withLock { statsRaw.copy() }
    internal inline fun count(f: Stats.() -> Unit) = lock.withLock { statsRaw.f() }

    internal var scheduler: ScheduledExecutorService? = null
    @Volatile var running = false
        private set

    // ---------------------------------------------------------------------------
    // Local destinations and known identities
    // ---------------------------------------------------------------------------

    /** Registers a local destination; its hash is computed from the identity and name. */
    fun addDestination(d: Destination): Destination {
        if (d.identityOrNull == null) d.identityOrNull = cfg.identity
        d.hash = d.identity.destHash(d.name)
        lock.withLock { dests[d.hashHex] = d }
        return d
    }

    /** A destination built without an identity gets the node's. */
    fun addDestination(name: String, configure: Destination.() -> Unit = {}): Destination {
        val d = Destination(name, cfg.identity)
        d.configure()
        return addDestination(d)
    }

    fun localDestination(hash: ByteArray): Destination? = lock.withLock { dests[hash.hex()] }

    fun localDestinations(): List<Destination> = lock.withLock { dests.values.toList() }

    /** What is known about a destination, or null. */
    fun recall(dest: ByteArray): KnownIdentity? = lock.withLock { identities[dest.hex()] }

    fun knownIdentities(): List<KnownIdentity> = lock.withLock { identities.values.toList() }

    /** Stores a destination's public key without an announce (a persisted row, a link-identified peer). */
    fun remember(dest: ByteArray, pub: ByteArray, appData: ByteArray? = null, ratchet: ByteArray? = null, ratchetAtMs: Long = 0L): Boolean {
        val id = RnsIdentity.fromPublicBytesOrNull(pub) ?: return false
        lock.withLock {
            val k = identities.getOrPut(dest.hex()) { KnownIdentity(dest.copyOf()) }
            k.publicKey = pub.copyOf()
            k.identity = id
            if (appData != null) k.appData = appData.copyOf()
            if (ratchet != null && ratchet.size == Rns.RATCHET_LEN) { k.ratchet = ratchet.copyOf(); k.ratchetAtMs = ratchetAtMs }
            k.lastSeenMs = now()
        }
        return true
    }

    /** Enables interface access codes on one interface (null disables). */
    fun setIfac(ifaceId: String, f: Ifac?) = lock.withLock { if (f == null) ifacs.remove(ifaceId) else ifacs[ifaceId] = f }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    /** Starts the periodic jobs (Transport.jobs), every 250 ms on one thread. */
    fun start() {
        if (running) return
        running = true
        val s = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rns-jobs").apply { isDaemon = true } }
        scheduler = s
        s.scheduleWithFixedDelay({ try { jobs() } catch (e: Exception) { log("rns: jobs failed: $e") } }, 250, 250, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!running) return
        running = false
        links.closeAll()
        scheduler?.shutdownNow()
        scheduler = null
    }

    internal fun schedule(delayMs: Long, task: () -> Unit) {
        val s = scheduler
        if (s == null || s.isShutdown) { task(); return }
        s.schedule({ try { task() } catch (e: Exception) { log("rns: scheduled task failed: $e") } }, maxOf(0L, delayMs), TimeUnit.MILLISECONDS)
    }

    internal fun jobs() {
        val nowMs = now()
        processAnnounceTable(nowMs)
        expireTables(nowMs)
        expireReceipts(nowMs)
        expireDiscovery(nowMs)
        links.watchdog(nowMs)
        lock.withLock {
            if (hashlist.size > 50_000) { hashlistPrev = hashlist; hashlist = HashSet() }
            if (prTags.size > 20_000) { prTagsPrev = prTags; prTags = HashSet() }
        }
    }

    // ---------------------------------------------------------------------------
    // Inbound
    // ---------------------------------------------------------------------------

    /**
     * Processes one raw packet received on [ifaceId]. True when the RNS layer
     * consumed it, false when it is not an RNS packet this node handles (the
     * caller may then try the MeshSat-private framing).
     */
    fun inbound(rawIn: ByteArray, ifaceId: String): Boolean {
        if (rawIn.size < Rns.HEADER_MINSIZE) return false
        var raw: ByteArray
        val f = lock.withLock { ifacs[ifaceId] }
        if (f != null) {
            raw = try { f.unwrap(rawIn) } catch (_: WireException) { count { ifacViolations++ }; return true }
        } else if (Ifac.hasFlag(rawIn)) {
            count { ifacViolations++ }
            return true
        } else {
            raw = rawIn.copyOf()
        }
        val hdr = Header.unpackOrNull(raw) ?: return false
        noteCrossing(ifaceId, raw, inbound = true)
        val pkt = Packet(raw, hdr, Header.packetHash(raw), ifaceId)
        count { rxPackets++ }

        if (!packetFilter(pkt)) { count { filtered++ }; return true }

        // hops++ as Transport.inbound does before anything else; a paid link with
        // extra hops counts as more than one hop for every packet.
        val extra = paidExtraHops(ifaceId)
        if (extra > 0 && hdr.hops + 1 + extra <= 255) hdr.hops += extra
        hdr.hops++
        raw[1] = hdr.hops.toByte()

        val destKey = hdr.destHash.hex()
        lock.withLock {
            val inLinkTable = linkTable.containsKey(destKey)
            val remember = !inLinkTable && !(hdr.packetType == Rns.PACKET_PROOF && hdr.context == Rns.CTX_LRPROOF)
            if (remember) hashlist.add(pkt.hash.hex())
        }

        // Packets in transport: are we the designated next hop?
        if (hdr.headerType == Rns.HEADER_2 && hdr.packetType != Rns.PACKET_ANNOUNCE) {
            if (!hdr.transportId.sameAs(idHash)) return true
            if (forwardTransport(pkt)) return true
            // No path: Python drops; a link request to a local destination carrying our own transport id still gets delivered below.
            if (hdr.packetType != Rns.PACKET_LINKREQUEST) return true
        }

        // Link transport: packets for links we carry in transit.
        if (hdr.packetType != Rns.PACKET_ANNOUNCE && hdr.packetType != Rns.PACKET_LINKREQUEST && hdr.context != Rns.CTX_LRPROOF) {
            if (linkTransit(pkt)) return true
        }

        when (hdr.packetType) {
            Rns.PACKET_ANNOUNCE -> return handleAnnounce(pkt)

            Rns.PACKET_LINKREQUEST -> {
                if (hdr.destType != Rns.DEST_SINGLE) return true
                localDestination(hdr.destHash)?.let { links.accept(it, pkt); return true }
                return forwardHeader1(pkt)
            }

            Rns.PACKET_DATA -> when (hdr.destType) {
                Rns.DEST_LINK -> {
                    links.active(hdr.destHash)?.let { links.receive(it, pkt); return true }
                    return false   // a link we do not hold: not ours (legacy MeshSat contexts on a LINK dest)
                }
                Rns.DEST_PLAIN -> {
                    if (hdr.destHash.contentEquals(Rns.PATH_REQUEST_DEST_HASH)) { handlePathRequest(pkt); return true }
                    return false   // legacy MeshSat path requests, HeMB and so on
                }
                Rns.DEST_SINGLE -> {
                    localDestination(hdr.destHash)?.let { deliverLocal(it, pkt); return true }
                    return forwardHeader1(pkt)
                }
                else -> return false
            }

            Rns.PACKET_PROOF -> when (hdr.context) {
                Rns.CTX_LRPROOF -> {
                    if (lrProofTransit(pkt)) return true
                    links.handleProof(pkt)
                    return true
                }
                Rns.CTX_RESOURCE_PRF -> {
                    links.active(hdr.destHash)?.let { links.receive(it, pkt); return true }
                    return false
                }
                else -> {
                    proofTransit(pkt)
                    handleReceiptProof(pkt)
                    return true
                }
            }
        }
        return false
    }

    /** Transport.packet_filter for a transport instance. */
    internal fun packetFilter(pkt: Packet): Boolean {
        val h = pkt.hdr
        if (h.headerType == Rns.HEADER_2 && h.packetType != Rns.PACKET_ANNOUNCE && !h.transportId.sameAs(idHash)) return false
        when (h.context) {
            Rns.CTX_KEEPALIVE, Rns.CTX_RESOURCE_REQ, Rns.CTX_RESOURCE_PRF, Rns.CTX_RESOURCE, Rns.CTX_CACHE_REQUEST, Rns.CTX_CHANNEL -> return true
        }
        if (h.destType == Rns.DEST_PLAIN || h.destType == Rns.DEST_GROUP) {
            if (h.packetType == Rns.PACKET_ANNOUNCE) return false
            return h.hops <= 1
        }
        val key = pkt.hash.hex()
        val seen = lock.withLock { key in hashlist || key in hashlistPrev }
        if (!seen) return true
        return h.packetType == Rns.PACKET_ANNOUNCE && h.destType == Rns.DEST_SINGLE
    }

    /** Decrypts a single-packet DATA for a local destination. */
    internal fun deliverLocal(d: Destination, pkt: Packet) {
        if (pkt.hdr.context != Rns.CTX_NONE) return
        val plain = d.identity.decrypt(pkt.hdr.data, d.ratchetPrivs())?.first
        if (plain == null) {
            log("rns: could not decrypt packet for ${d.name} on ${pkt.iface}")
            return
        }
        d.onPacket?.invoke(plain, pkt)
        if (d.proveAll) prove(d, pkt)
    }

    /** Answers a delivered packet with an implicit proof on the receiving interface (Identity.prove). */
    internal fun prove(d: Destination, pkt: Packet) {
        transmit(pkt.iface, Proof.packet(pkt.raw, d.identity.sign(pkt.hash)))
    }

    internal fun transmit(ifaceId: String, raw: ByteArray): Boolean {
        val f = lock.withLock { statsRaw.txPackets++; ifacs[ifaceId] }
        val out = if (f != null) f.wrap(raw) else raw
        if (!tx.transmit(ifaceId, out)) { log("rns: transmit on $ifaceId failed"); return false }
        noteCrossing(ifaceId, raw, inbound = false)
        return true
    }

}
