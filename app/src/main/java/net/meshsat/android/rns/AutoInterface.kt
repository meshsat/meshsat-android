package net.meshsat.android.rns

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * RNS AutoInterface (RNS/Interfaces/AutoInterface.py), a port of the Bridge's
 * routing/auto_interface.go: zero-configuration peering on a LAN over IPv6
 * link-local multicast, then one UDP datagram per Reticulum packet to each peer.
 * It is how Sideband, CrossTalk and rnsd on the same WiFi find each other, so a
 * phone with it on joins their network with nothing typed. [MESHSAT-1553 P4]
 *
 * Protocol: every 1.6 s a node multicasts its discovery token,
 * SHA-256(group id || its link-local address text), to
 * ff12:0:<six words of SHA-256(group id)[2..13]> port 29716 on each adopted
 * interface, and unicasts the same token to every known peer's port 29717 every
 * 5.2 s (reverse peering). A token that verifies adds or refreshes a peer; a peer
 * silent for 22 s is dropped. Data goes to [peer]:42671 from a socket bound to
 * the adopted link-local address, so every datagram leaves with the address the
 * token names. A datagram seen on several adopted interfaces is delivered once.
 */
class AutoInterface(
    val name: String = "auto_rns_0",
    val groupId: String = DEFAULT_GROUP,
    /** Network interfaces to adopt; empty = every one up with a link-local IPv6 address, except lo and [ignore]. */
    val devices: List<String> = emptyList(),
    val ignore: List<String> = emptyList(),
    private val onPacket: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val HW_MTU = 1196
        const val DISCOVERY_PORT = 29716
        const val DATA_PORT = 42671
        const val DEFAULT_GROUP = "reticulum"
        const val PEERING_TIMEOUT_MS = 22_000L
        const val ANNOUNCE_INTERVAL_MS = 1_600L
        const val PEER_JOB_INTERVAL_MS = 4_000L
        const val REVERSE_PEERING_MS = 5_200L
        const val DEDUP_LEN = 48
        const val DEDUP_TTL_MS = 750L
        private val scopeRe = Regex("fe80:[0-9a-f]*::")

        /**
         * The text of an IPv6 address as inet_ntop prints it (RFC 5952): lowercase, no
         * leading zeros, the longest run of two or more zero words as "::", the leftmost
         * on a tie. Tokens hash this text, so it must match what Python's socket gives.
         */
        fun compress(addr: ByteArray): String {
            require(addr.size == 16) { "not an IPv6 address" }
            val words = IntArray(8) { ((addr[2 * it].toInt() and 0xFF) shl 8) or (addr[2 * it + 1].toInt() and 0xFF) }
            var bestStart = -1
            var bestLen = 0
            var i = 0
            while (i < 8) {
                if (words[i] == 0) {
                    var j = i
                    while (j < 8 && words[j] == 0) j++
                    if (j - i > bestLen) { bestLen = j - i; bestStart = i }
                    i = j
                } else i++
            }
            if (bestLen < 2) bestStart = -1
            val sb = StringBuilder()
            i = 0
            while (i < 8) {
                if (i == bestStart) { sb.append("::"); i += bestLen; continue }
                if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
                sb.append(Integer.toHexString(words[i]))
                i++
            }
            return sb.toString()
        }

        /** As upstream normalises a link-local address: no %scope, and "fe80:<zeros>::" collapsed to "fe80::". */
        fun descope(addr: String): String = scopeRe.replace(addr.substringBefore('%'), "fe80::")

        /** The discovery token: SHA-256(group id || link-local address text). */
        fun token(groupId: String, linkLocalText: String): ByteArray =
            Rns.sha256(groupId.toByteArray(Charsets.UTF_8), linkLocalText.toByteArray(Charsets.UTF_8))

        /** The link-local multicast discovery group of a group id: ff12:0:<six words of SHA-256(group id)[2..13]>. */
        fun multicastGroup(groupId: String): ByteArray {
            val g = Rns.sha256(groupId.toByteArray(Charsets.UTF_8))
            val out = ByteArray(16)
            out[0] = 0xFF.toByte(); out[1] = 0x12
            for (k in 0 until 6) { out[4 + 2 * k] = g[2 + 2 * k]; out[5 + 2 * k] = g[3 + 2 * k] }
            return out
        }
    }

    /** One discovered peer. */
    data class PeerInfo(val address: String, val device: String, val lastHeardMs: Long)

    private class Adopted(val iface: NetworkInterface, val linkLocal: Inet6Address, val text: String) {
        var uni: DatagramSocket? = null
        var data: DatagramSocket? = null
        var mcastEchoMs = 0L
        val threads = ArrayList<Thread>()
    }

    private class Peer(var addr: Inet6Address, val ifname: String, var lastHeardMs: Long, var lastOutMs: Long = 0L)

    private val lock = ReentrantLock()
    private val adopted = LinkedHashMap<String, Adopted>()
    private val peers = HashMap<String, Peer>()   // by descoped address text
    private val groupBytes = multicastGroup(groupId)
    private var disc: MulticastSocket? = null
    private var jobs: Thread? = null
    private var discThread: Thread? = null
    private val dedup = ArrayDeque<Pair<ByteArray, Long>>()
    @Volatile var online = false
        private set
    @Volatile private var stopped = false
    @Volatile var rxBytes = 0L
        private set
    @Volatile var txBytes = 0L
        private set

    val groupText: String get() = compress(groupBytes)
    fun adoptedDevices(): List<String> = lock.withLock { adopted.keys.toList() }
    fun peerCount(): Int = lock.withLock { peers.size }
    fun peers(): List<PeerInfo> = lock.withLock { peers.map { (k, p) -> PeerInfo(k, p.ifname, p.lastHeardMs) } }

    private fun wanted(ifname: String): Boolean {
        if (ifname == "lo" || ifname == "lo0" || ifname in ignore) return false
        return devices.isEmpty() || ifname in devices
    }

    private fun startThread(n: String, body: () -> Unit): Thread = Thread({ try { body() } catch (e: Exception) { if (!stopped) log("auto $name: $n stopped: $e") } }, n).apply { isDaemon = true; start() }

    /** Adopts interfaces, binds sockets and starts announcing. False when no interface has a link-local IPv6 address. */
    fun start(): Boolean {
        stopped = false
        val ifaces = try { NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList() } catch (e: Exception) { log("auto $name: cannot list interfaces: $e"); emptyList() }
        val ms = MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(Inet6Address.getByName("::"), DISCOVERY_PORT)) }
        for (ifc in ifaces) {
            try {
                if (!wanted(ifc.name) || !ifc.isUp || ifc.isLoopback) continue
                val ll = ifc.inetAddresses.toList().filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress } ?: continue
                adopt(ifc, ll, ms)
            } catch (e: Exception) {
                log("auto $name: could not adopt ${ifc.name}: $e")
            }
        }
        if (adopted.isEmpty()) {
            ms.close()
            log("auto $name: no usable network interface with a link-local IPv6 address")
            return false
        }
        disc = ms
        online = true
        discThread = startThread("auto-disc") { discoveryLoop(ms, null) }
        jobs = startThread("auto-jobs") { jobLoop() }
        log("auto $name: online on ${adopted.keys} group $groupText")
        return true
    }

    private fun adopt(ifc: NetworkInterface, ll: Inet6Address, ms: MulticastSocket) {
        val scoped = Inet6Address.getByAddress(null, ll.address, ifc)
        val ad = Adopted(ifc, scoped, descope(compress(ll.address)))
        ms.joinGroup(InetSocketAddress(Inet6Address.getByAddress(null, groupBytes, ifc), DISCOVERY_PORT), ifc)
        // Unicast discovery and data sockets on the link-local address itself, so every
        // datagram leaves with the address our token was derived from.
        val uni = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(scoped, DISCOVERY_PORT + 1)) }
        val data = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(scoped, DATA_PORT)) }
        ad.uni = uni; ad.data = data; ad.mcastEchoMs = now()
        lock.withLock { adopted[ifc.name] = ad }
        ad.threads += startThread("auto-uni-${ifc.name}") { discoveryLoop(uni, ad) }
        ad.threads += startThread("auto-data-${ifc.name}") { dataLoop(data, ad) }
        log("auto $name: adopted ${ifc.name} ${ad.text}")
    }

    /** The adopted interface a multicast datagram came in on, by the source's scope id. */
    private fun adoptedFor(src: Inet6Address): Adopted? = lock.withLock {
        val sid = src.scopeId
        if (sid > 0) adopted.values.firstOrNull { it.iface.index == sid } ?: adopted.values.singleOrNull()
        else adopted.values.singleOrNull()
    }

    private fun discoveryLoop(sock: DatagramSocket, ad: Adopted?) {
        val buf = ByteArray(1024)
        while (!stopped) {
            val p = DatagramPacket(buf, buf.size)
            try { sock.receive(p) } catch (e: Exception) { if (!stopped) log("auto $name: discovery socket closed: ${e.message}"); return }
            if (p.length < Rns.FULL_HASH_LEN) continue
            val src = p.address as? Inet6Address ?: continue
            val text = descope(compress(src.address))
            if (!buf.copyOf(Rns.FULL_HASH_LEN).contentEquals(token(groupId, text))) {
                log("auto $name: peering packet from $text with a wrong token")
                continue
            }
            val via = ad ?: adoptedFor(src) ?: continue
            addPeer(src, text, via)
        }
    }

    private fun addPeer(src: Inet6Address, text: String, via: Adopted) {
        val t = now()
        var added = false
        lock.withLock {
            // Our own multicast echoed back: proves the link carries multicast.
            adopted.values.firstOrNull { it.text == text }?.let { it.mcastEchoMs = t; return }
            val p = peers[text]
            if (p != null) { p.lastHeardMs = t; return }
            val addr = try { Inet6Address.getByAddress(null, src.address, via.iface) } catch (_: Exception) { src }
            peers[text] = Peer(addr, via.iface.name, t)
            added = true
        }
        if (added) log("auto $name: peer $text added on ${via.iface.name}")
    }

    private fun isDuplicate(data: ByteArray): Boolean {
        val h = Rns.sha256(data)
        val t = now()
        lock.withLock {
            while (dedup.isNotEmpty() && t - dedup.first().second > DEDUP_TTL_MS) dedup.removeFirst()
            if (dedup.any { it.first.contentEquals(h) }) return true
            dedup.addLast(h to t)
            while (dedup.size > DEDUP_LEN) dedup.removeFirst()
        }
        return false
    }

    private fun dataLoop(sock: DatagramSocket, ad: Adopted) {
        val buf = ByteArray(65535)
        while (!stopped) {
            val p = DatagramPacket(buf, buf.size)
            try { sock.receive(p) } catch (e: Exception) { if (!stopped) log("auto $name: data socket on ${ad.iface.name} closed: ${e.message}"); return }
            val src = p.address as? Inet6Address ?: continue
            val text = descope(compress(src.address))
            val known = lock.withLock { peers[text]?.also { it.lastHeardMs = now() } != null }
            if (!known) continue
            val payload = buf.copyOf(p.length)
            if (isDuplicate(payload)) continue
            rxBytes += payload.size
            onPacket(payload)
        }
    }

    private fun jobLoop() {
        var lastAnnounce = 0L
        var lastPeerJobs = now()
        while (!stopped) {
            val t = now()
            if (t - lastAnnounce >= ANNOUNCE_INTERVAL_MS) { lastAnnounce = t; announceAll() }
            if (t - lastPeerJobs >= PEER_JOB_INTERVAL_MS) { lastPeerJobs = t; peerJobs() }
            try { Thread.sleep(200) } catch (_: InterruptedException) { return }
        }
    }

    private fun announceAll() {
        val ads = lock.withLock { adopted.values.toList() }
        for (ad in ads) {
            try {
                val tok = token(groupId, ad.text)
                val group = Inet6Address.getByAddress(null, groupBytes, ad.iface)
                ad.data?.send(DatagramPacket(tok, tok.size, group, DISCOVERY_PORT))
            } catch (e: Exception) {
                if (!stopped) log("auto $name: announce on ${ad.iface.name} failed: ${e.message}")
            }
        }
    }

    private fun peerJobs() {
        val t = now()
        val reverse = ArrayList<Peer>()
        val removed = ArrayList<String>()
        val ads: Map<String, Adopted>
        lock.withLock {
            val it = peers.entries.iterator()
            while (it.hasNext()) {
                val (k, p) = it.next()
                if (t - p.lastHeardMs > PEERING_TIMEOUT_MS) { it.remove(); removed.add(k); continue }
                if (t - p.lastOutMs > REVERSE_PEERING_MS) { p.lastOutMs = t; reverse.add(p) }
            }
            ads = HashMap(adopted)
        }
        for (k in removed) log("auto $name: peer $k timed out")
        for (p in reverse) {
            val ad = ads[p.ifname] ?: continue
            try {
                val tok = token(groupId, ad.text)
                ad.data?.send(DatagramPacket(tok, tok.size, p.addr, DISCOVERY_PORT + 1))
            } catch (e: Exception) {
                if (!stopped) log("auto $name: reverse peering to ${p.addr.hostAddress} failed: ${e.message}")
            }
        }
        // A re-plugged link changes the link-local address; say so (the sockets keep the old one until a restart).
        for ((n, ad) in ads) {
            try {
                val ifc = NetworkInterface.getByName(n) ?: continue
                val ll = ifc.inetAddresses.toList().filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress } ?: continue
                val text = descope(compress(ll.address))
                if (text != ad.text) log("auto $name: link-local address of $n changed to $text, restart the interface")
            } catch (_: Exception) {}
        }
    }

    /** Delivers one packet to every peer. False when there is no peer or no interface. */
    fun send(packet: ByteArray): Boolean {
        if (!online) return false
        val targets = lock.withLock { peers.values.map { it to adopted[it.ifname] } }
        var sent = false
        for ((p, ad) in targets) {
            val sock = ad?.data ?: continue
            try {
                sock.send(DatagramPacket(packet, packet.size, p.addr, DATA_PORT))
                txBytes += packet.size
                sent = true
            } catch (e: Exception) {
                if (!stopped) log("auto $name: send to ${p.addr.hostAddress} failed: ${e.message}")
            }
        }
        return sent
    }

    /** Closes every socket. */
    fun stop() {
        stopped = true
        online = false
        val ads = lock.withLock { adopted.values.toList().also { adopted.clear(); peers.clear() } }
        disc?.close(); disc = null
        for (ad in ads) { ad.uni?.close(); ad.data?.close() }
        jobs?.interrupt(); jobs = null
    }
}
