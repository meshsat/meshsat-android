package net.meshsat.android.rns

import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** RNS.Link status. */
enum class LinkState { PENDING, HANDSHAKE, ACTIVE, STALE, CLOSED }

/** An RNS link, initiated by us or accepted for a local destination (RNS/Link.py). */
class Link internal constructor(
    val id: ByteArray,
    /** The remote destination (initiator) or the local destination (responder). */
    val dest: ByteArray,
    val initiator: Boolean,
) {
    val idHex: String get() = id.hex()
    @Volatile var iface: String = ""
        internal set

    internal val mu = ReentrantLock()
    internal var stateRaw = LinkState.PENDING
    internal var prv: ByteArray? = null          // our ephemeral x25519 private key
    internal var sigSeed: ByteArray? = null      // ephemeral ed25519 seed (initiator); the responder signs with its identity
    internal var signer: ((ByteArray) -> ByteArray)? = null
    internal var peerPub: ByteArray? = null
    internal var peerSigPub: ByteArray? = null
    internal var key: ByteArray? = null
    internal var token: Token? = null
    @Volatile var mtu: Int = Rns.MTU
        internal set
    @Volatile var mdu: Int = LinkWire.mdu(Rns.MTU)
        internal set
    internal var rttMsRaw: Long = 0
    internal var keepaliveMs: Long = Rns.KEEPALIVE_MAX * 1000L
    internal var staleTimeMs: Long = 2L * Rns.KEEPALIVE_MAX * 1000L
    internal var requestTimeMs: Long = 0
    internal var establishTimeoutMs: Long = 0
    internal var activatedAtMs: Long = 0
    internal var lastInboundMs: Long = 0
    internal var lastOutboundMs: Long = 0
    internal var lastKeepaliveMs: Long = 0
    internal var lastProofMs: Long = 0
    internal var staleSinceMs: Long = 0
    internal var expectedHops: Int = 1
    internal var remoteIdent: ByteArray? = null  // 64-byte public key once identified
    internal var local: Destination? = null
    internal var destIdentity: RnsIdentity? = null   // initiator: the destination identity
    internal var resources: LinkResources? = null
    /** Requests we sent and still wait for, by request id (Link.pending_requests). */
    internal val pendingRequests = HashMap<String, RequestReceipt>()

    /** Whether an advertised resource of the given data size is received; null rejects every resource. */
    var acceptResources: ((size: Int) -> Boolean)? = null
    /** A completed incoming resource. */
    var onResource: ((Link, ByteArray) -> Unit)? = null
    var onEstablished: ((Link) -> Unit)? = null
    var onPacket: ((Link, ByteArray, Packet) -> Unit)? = null
    var onClosed: ((Link) -> Unit)? = null
    var onIdentified: ((Link, ByteArray) -> Unit)? = null
    /** Resource and other contexts not handled here. */
    var onContext: ((Link, Packet) -> Unit)? = null

    /** Completes when the link is active (initiator side), or exceptionally when it fails. */
    val established = CompletableFuture<Link>()
    /** Completes when the link closes. */
    val closed = CompletableFuture<Link>()

    val state: LinkState get() = mu.withLock { stateRaw }
    val rttMs: Long get() = mu.withLock { rttMsRaw }
    val remoteIdentity: ByteArray? get() = mu.withLock { remoteIdent }
    val keepalive: Long get() = mu.withLock { keepaliveMs }
    /** How long the initiator waits for the proof: the per-hop timeout times the path's hops plus one. */
    val establishTimeout: Long get() = establishTimeoutMs

    fun encrypt(plain: ByteArray): ByteArray = (token ?: throw IllegalStateException("link has no key yet")).encrypt(plain)

    /** Null when the token does not verify. */
    fun decrypt(ct: ByteArray): ByteArray? = try { token?.decrypt(ct) } catch (_: WireException) { null }

    internal fun sign(data: ByteArray): ByteArray = (signer ?: throw IllegalStateException("link cannot sign"))(data)

    internal fun noteProof(nowMs: Long) = mu.withLock { lastProofMs = nowMs }

    internal fun res(): LinkResources = mu.withLock { resources ?: LinkResources().also { resources = it } }

    override fun toString(): String = "<Link $idHex ${state.name.lowercase()}>"
}

/** Pending and active links. */
class LinkManager internal constructor(private val n: Node) {
    internal fun node(): Node = n
    private val mu = ReentrantLock()
    private val pending = HashMap<String, Link>()
    private val links = HashMap<String, Link>()

    /** An active (or stale) link by id. */
    internal fun active(id: ByteArray): Link? = mu.withLock { links[id.hex()] }

    /** Any known link by id. */
    fun get(id: ByteArray): Link? = mu.withLock { links[id.hex()] ?: pending[id.hex()] }

    fun all(): List<Link> = mu.withLock { pending.values.toList() + links.values.toList() }

    private fun updateKeepalive(l: Link) {
        var ka = (l.rttMsRaw * (Rns.KEEPALIVE_MAX / 1.75)).toLong()
        if (ka > Rns.KEEPALIVE_MAX * 1000L) ka = Rns.KEEPALIVE_MAX * 1000L
        if (ka < Rns.KEEPALIVE_MIN * 1000L) ka = Rns.KEEPALIVE_MIN * 1000L
        l.keepaliveMs = ka
        l.staleTimeMs = 2 * ka
    }

    /** Opens a link to a remote destination known from an announce. */
    fun initiate(dest: ByteArray): Link {
        val k = n.recall(dest)
        val destId = k?.identity ?: throw IllegalStateException("destination ${dest.hex().take(8)} identity unknown")
        val prv = RnsIdentity.generateX25519()
        val sigSeed = RnsIdentity.generateSigningSeed()
        var mtu = Rns.MTU
        val ifc = n.nextHopInterface(dest)
        if (ifc.isNotEmpty()) { val hw = n.tx.hwMtu(ifc); if (hw in 1 until mtu) mtu = hw }
        val lr = LinkWire.Request(RnsIdentity.ratchetPublic(prv), RnsIdentity.signingPublicOfSeed(sigSeed), mtu, LinkWire.MODE_AES256_CBC)
        val raw = Header(headerType = Rns.HEADER_1, destType = Rns.DEST_SINGLE, packetType = Rns.PACKET_LINKREQUEST,
            destHash = dest, context = Rns.CTX_NONE, data = lr.pack()).pack()
        val id = LinkWire.linkIdFromRequestPacket(raw)
        val hops = maxOf(1, n.paths.hops(dest))
        val nowMs = n.now()
        val l = Link(id, dest.copyOf(), initiator = true)
        l.prv = prv; l.sigSeed = sigSeed; l.signer = { d -> RnsIdentity.signWithSeed(sigSeed, d) }
        l.peerSigPub = destId.sigPubRaw; l.destIdentity = destId
        l.mtu = mtu; l.mdu = LinkWire.mdu(mtu); l.requestTimeMs = nowMs
        l.establishTimeoutMs = (Rns.PER_HOP_TIMEOUT * hops + 6) * 1000L
        l.expectedHops = hops
        mu.withLock { pending[l.idHex] = l }
        val ifaces = n.outbound(raw, dest, Rns.DEST_SINGLE)
        if (ifaces.isEmpty()) {
            mu.withLock { pending.remove(l.idHex) }
            throw IllegalStateException("no path to ${dest.hex().take(8)} and nothing to flood on")
        }
        if (ifaces.size == 1) l.iface = ifaces[0]
        l.lastOutboundMs = nowMs
        n.log("rns: link request sent ${l.idHex.take(8)} -> ${dest.hex().take(8)}")
        return l
    }

    /** Answers a link request for a local destination (Link.validate_request + prove). */
    internal fun accept(d: Destination, pkt: Packet) {
        if (!d.acceptLinks) return
        val h = pkt.hdr
        val lr = try { LinkWire.Request.unpack(h.data) } catch (e: WireException) { n.log("rns: bad link request: ${e.message}"); return }
        val id = try { LinkWire.linkIdFromRequestPacket(pkt.raw) } catch (_: WireException) { return }
        var mtu = Rns.MTU
        if (lr.signalled) {
            mtu = lr.mtu
            val hw = n.tx.hwMtu(pkt.iface)
            if (hw in 1 until mtu) mtu = hw
        }
        val prv = RnsIdentity.generateX25519()
        val shared = try { RnsIdentity.x25519Agree(prv, lr.ephPub) } catch (_: Exception) { return }
        val key = LinkWire.deriveLinkKey(shared, id)
        val nowMs = n.now()
        val l = Link(id, d.hash, initiator = false)
        l.iface = pkt.iface
        l.stateRaw = LinkState.HANDSHAKE
        l.prv = prv; l.signer = { data -> d.identity.sign(data) }
        l.peerPub = lr.ephPub; l.peerSigPub = lr.ephSigPub
        l.key = key; l.token = Token(key)
        l.mtu = mtu; l.mdu = LinkWire.mdu(mtu)
        l.requestTimeMs = nowMs; l.local = d
        l.establishTimeoutMs = (Rns.PER_HOP_TIMEOUT * maxOf(1, h.hops) + 6) * 1000L
        l.acceptResources = d.acceptResources
        mu.withLock { links[l.idHex] = l }

        val ephPub = RnsIdentity.ratchetPublic(prv)
        val signed = LinkWire.proofSignedData(id, ephPub, d.identity.sigPubRaw, mtu, LinkWire.MODE_AES256_CBC)
        val lp = LinkWire.ProofData(d.identity.sign(signed), ephPub, mtu, LinkWire.MODE_AES256_CBC)
        val ph = Header(headerType = Rns.HEADER_1, destType = Rns.DEST_LINK, packetType = Rns.PACKET_PROOF,
            destHash = id, context = Rns.CTX_LRPROOF, data = lp.pack())
        l.lastOutboundMs = nowMs
        n.transmit(pkt.iface, ph.pack())
        n.log("rns: link request accepted ${l.idHex.take(8)} for ${d.name} on ${pkt.iface}, proof sent")
    }

    /** Validates a link request proof for one of our pending links. */
    internal fun handleProof(pkt: Packet) {
        val h = pkt.hdr
        val l = mu.withLock { pending[h.destHash.hex()] } ?: return
        val lp = try { LinkWire.ProofData.unpack(h.data) } catch (_: WireException) { return }
        if (lp.mode != LinkWire.MODE_AES256_CBC) return
        l.mu.withLock {
            if (l.stateRaw != LinkState.PENDING) return
            val destId = l.destIdentity ?: return
            val confirmedMtu = if (lp.signalled) lp.mtu else Rns.MTU
            val signed = LinkWire.proofSignedData(l.id, lp.ephPub, destId.sigPubRaw, lp.mtu, lp.mode, lp.signalled)
            if (!destId.verify(signed, lp.signature)) { n.log("rns: invalid link proof signature ${l.idHex.take(8)}"); return }
            val shared = try { RnsIdentity.x25519Agree(l.prv!!, lp.ephPub) } catch (_: Exception) { return }
            l.peerPub = lp.ephPub
            l.key = LinkWire.deriveLinkKey(shared, l.id)
            l.token = Token(l.key!!)
            l.mtu = confirmedMtu; l.mdu = LinkWire.mdu(confirmedMtu)
            val nowMs = n.now()
            l.rttMsRaw = nowMs - l.requestTimeMs
            l.iface = pkt.iface
            l.stateRaw = LinkState.ACTIVE
            l.activatedAtMs = nowMs; l.lastProofMs = nowMs; l.lastInboundMs = nowMs
            updateKeepalive(l)
            mu.withLock { pending.remove(l.idHex); links[l.idHex] = l }
            // The RTT packet activates the responder side.
            sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_LRRTT, l.token!!.encrypt(LinkWire.rttData(l.rttMsRaw / 1000.0)))
            n.log("rns: link established ${l.idHex.take(8)} -> ${l.dest.hex().take(8)} rtt=${l.rttMsRaw}ms mtu=${l.mtu}")
        }
        l.established.complete(l)
        l.onEstablished?.invoke(l)
    }

    /** Transmits a packet on the link's interface (Transport.outbound for LINK destinations). Caller holds l.mu. */
    internal fun sendLinkPacket(l: Link, ptype: Int, context: Int, data: ByteArray): ByteArray {
        val raw = Header(headerType = Rns.HEADER_1, destType = Rns.DEST_LINK, packetType = ptype, destHash = l.id, context = context, data = data).pack()
        l.lastOutboundMs = n.now()
        if (context == Rns.CTX_KEEPALIVE) l.lastKeepaliveMs = l.lastOutboundMs
        n.transmit(l.iface, raw)
        return raw
    }

    /** Encrypts and sends plaintext as a link DATA packet; the receipt resolves when the peer's proof arrives. */
    fun send(l: Link, plain: ByteArray): Receipt = l.mu.withLock {
        if (l.stateRaw != LinkState.ACTIVE && l.stateRaw != LinkState.STALE) throw IllegalStateException("link not active")
        if (plain.size > l.mdu) throw IllegalArgumentException("plaintext ${plain.size} exceeds the link MDU ${l.mdu}")
        val ct = l.token!!.encrypt(plain)
        val raw = sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_NONE, ct)
        // Link proofs are signed by the peer's link signing key: the destination identity
        // when we initiated, the initiator's ephemeral key otherwise.
        val r = Receipt(Header.packetHash(raw), l.id, n.now(), n.now() + (Rns.PER_HOP_TIMEOUT * maxOf(1, l.expectedHops) + 6) * 1000L, l.peerSigPub, l)
        n.addReceipt(r)
        r
    }

    /** Sends a payload with a specific context on the link; used by resource transfer. */
    internal fun sendContext(l: Link, ptype: Int, context: Int, data: ByteArray) = l.mu.withLock { sendLinkPacket(l, ptype, context, data) }

    /** Tells the responder who we are (Link.identify). */
    fun identify(l: Link, id: RnsIdentity) = l.mu.withLock {
        if (!l.initiator || l.stateRaw != LinkState.ACTIVE) throw IllegalStateException("identify needs an active initiated link")
        sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_LINKIDENTIFY, l.token!!.encrypt(LinkWire.identifyData(id, l.id)))
    }

    /** Closes a link and tells the peer (Link.teardown). */
    fun teardown(l: Link) {
        l.mu.withLock {
            if (l.stateRaw == LinkState.CLOSED) return
            if (l.stateRaw == LinkState.ACTIVE || l.stateRaw == LinkState.STALE) {
                l.token?.let { sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_LINKCLOSE, it.encrypt(l.id)) }
            }
            l.stateRaw = LinkState.CLOSED
        }
        closed(l)
    }

    internal fun closed(l: Link) {
        mu.withLock { links.remove(l.idHex); pending.remove(l.idHex) }
        n.log("rns: link closed ${l.idHex.take(8)}")
        failRequestsOn(l, "link closed")
        l.established.completeExceptionally(IllegalStateException("link closed"))
        l.closed.complete(l)
        l.onClosed?.invoke(l)
        l.local?.onLinkClosed?.invoke(l)
    }

    /** Dispatches a packet addressed to an active link (Link.receive). */
    internal fun receive(l: Link, pkt: Packet) {
        val h = pkt.hdr
        val state: LinkState
        val nowMs = n.now()
        l.mu.withLock {
            if (pkt.iface != l.iface) { n.log("rns: link ${l.idHex.take(8)} packet on ${pkt.iface}, want ${l.iface}"); return }
            l.lastInboundMs = nowMs
            if (l.stateRaw == LinkState.STALE) l.stateRaw = LinkState.ACTIVE
            state = l.stateRaw
        }

        if (h.packetType == Rns.PACKET_PROOF) {
            if (h.context == Rns.CTX_RESOURCE_PRF) handleResourceProof(l, h.data)
            return
        }
        when (h.context) {
            Rns.CTX_NONE -> {
                val plain = l.decrypt(h.data) ?: return
                l.onPacket?.invoke(l, plain, pkt)
                val local = l.local
                if (local != null) {
                    local.onLinkPacket?.invoke(l, plain, pkt)
                    if (local.proveAll) l.mu.withLock { sendLinkPacket(l, Rns.PACKET_PROOF, Rns.CTX_NONE, pkt.hash + l.sign(pkt.hash)) }
                }
            }

            Rns.CTX_LRRTT -> {
                if (l.initiator || state != LinkState.HANDSHAKE) return
                val plain = l.decrypt(h.data)
                val rtt = plain?.let { LinkWire.parseRttData(it) }
                if (rtt == null) { teardown(l); return }
                l.mu.withLock {
                    val measured = nowMs - l.requestTimeMs
                    l.rttMsRaw = maxOf((rtt * 1000).toLong(), measured)
                    l.stateRaw = LinkState.ACTIVE
                    l.activatedAtMs = nowMs
                    l.expectedHops = h.hops
                    updateKeepalive(l)
                }
                n.log("rns: link active ${l.idHex.take(8)} for ${l.local?.name} rtt=${l.rttMs}ms")
                l.established.complete(l)
                l.local?.onLink?.invoke(l)
            }

            Rns.CTX_KEEPALIVE -> {
                if (!l.initiator && h.data.size == 1 && (h.data[0].toInt() and 0xFF) == LinkWire.KEEPALIVE_INITIATOR) {
                    l.mu.withLock {
                        if (nowMs >= l.lastOutboundMs + l.keepaliveMs) {
                            sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_KEEPALIVE, byteArrayOf(LinkWire.KEEPALIVE_RESPONSE.toByte()))
                        }
                    }
                }
            }

            Rns.CTX_LINKCLOSE -> {
                val plain = l.decrypt(h.data)
                if (plain != null && plain.size == Rns.HASH_LEN && plain.contentEquals(l.id)) {
                    l.mu.withLock { l.stateRaw = LinkState.CLOSED }
                    closed(l)
                }
            }

            Rns.CTX_RESOURCE_ADV -> l.decrypt(h.data)?.let { handleResourceAdv(l, it) }
            Rns.CTX_RESOURCE_REQ -> l.decrypt(h.data)?.let { handleResourceRequest(l, it) }
            Rns.CTX_RESOURCE -> receivePart(l, h.data)
            Rns.CTX_RESOURCE_HMU -> l.decrypt(h.data)?.let { handleResourceHmu(l, it) }
            Rns.CTX_RESOURCE_ICL, Rns.CTX_RESOURCE_RCL -> l.decrypt(h.data)?.let { handleResourceCancel(l, it) }
            Rns.CTX_RESPONSE -> l.decrypt(h.data)?.let { handleResponsePacket(l, it) }

            Rns.CTX_LINKIDENTIFY -> {
                if (l.initiator) return
                val plain = l.decrypt(h.data) ?: return
                val pub = LinkWire.parseIdentify(plain, l.id) ?: return
                val first = l.mu.withLock { val f = l.remoteIdent == null; l.remoteIdent = pub; f }
                if (first) l.onIdentified?.invoke(l, pub)
            }

            else -> l.onContext?.invoke(l, pkt)
        }
    }

    /** Link.__watchdog_job for every link, from the node jobs. */
    internal fun watchdog(nowMs: Long) {
        resourceWatchdog(nowMs)
        requestWatchdog(nowMs)
        for (l in all()) {
            var close = false
            l.mu.withLock {
                when (l.stateRaw) {
                    LinkState.PENDING, LinkState.HANDSHAKE -> if (nowMs > l.requestTimeMs + l.establishTimeoutMs) {
                        l.stateRaw = LinkState.CLOSED; close = true
                        n.log("rns: link establishment timed out ${l.idHex.take(8)}")
                    }
                    LinkState.ACTIVE -> {
                        var lastIn = l.lastInboundMs
                        if (l.lastProofMs > lastIn) lastIn = l.lastProofMs
                        if (l.activatedAtMs > lastIn) lastIn = l.activatedAtMs
                        if (nowMs >= lastIn + l.keepaliveMs || nowMs >= l.lastOutboundMs + l.keepaliveMs) {
                            if (l.initiator && nowMs >= l.lastKeepaliveMs + l.keepaliveMs) {
                                sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_KEEPALIVE, byteArrayOf(LinkWire.KEEPALIVE_INITIATOR.toByte()))
                            }
                            if (nowMs >= lastIn + l.staleTimeMs) { l.stateRaw = LinkState.STALE; l.staleSinceMs = nowMs }
                        }
                    }
                    LinkState.STALE -> {
                        val grace = l.rttMsRaw * 4 + Rns.STALE_GRACE * 1000L
                        if (nowMs > l.staleSinceMs + grace) {
                            l.token?.let { sendLinkPacket(l, Rns.PACKET_DATA, Rns.CTX_LINKCLOSE, it.encrypt(l.id)) }
                            l.stateRaw = LinkState.CLOSED; close = true
                        }
                    }
                    LinkState.CLOSED -> {}
                }
            }
            if (close) closed(l)
        }
    }

    internal fun closeAll() { for (l in all()) teardown(l) }
}
