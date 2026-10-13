package net.meshsat.android.interop

import net.meshsat.android.rns.Destination
import net.meshsat.android.rns.Link
import net.meshsat.android.rns.LinkState
import net.meshsat.android.rns.Node
import net.meshsat.android.rns.NodeConfig
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.TcpServerInterface
import net.meshsat.android.rns.Transmitter
import net.meshsat.android.rns.announce
import net.meshsat.android.rns.hopsTo
import net.meshsat.android.rns.requestPath
import net.meshsat.android.rns.sendData
import net.meshsat.android.reticulum.RnsReceiveCallback
import net.meshsat.android.reticulum.RnsTcpInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.security.Security
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The node on a real TCP interface against upstream Python RNS peers from the
 * pinned venv (the Bridge's harness_test.go). Skipped without the venv. [MESHSAT-1553]
 */
class InteropNodeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            PyEnv.python()
        }
    }

    @get:Rule
    val testName = TestName()

    private var failed = false
    private val nodes = ArrayList<Node>()
    private val servers = ArrayList<TcpServerInterface>()
    private val clients = ArrayList<RnsTcpInterface>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun begin() { System.err.println("=== ${testName.methodName}") }

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
        servers.forEach { it.stop() }
        clients.forEach { it.disconnect() }
        scope.cancel()
        PyPeer.stopAll(dumpLogs = failed)
    }

    /** Our node on a TCP server interface "tcp_0" (and optionally a client "tcp_c" to a listening peer). */
    inner class Kit(connect: String? = null) {
        val id = RnsIdentity.generate()
        val server = TcpServerInterface("tcp_0", log = { System.err.println("[kit] $it") }).also { servers.add(it) }
        val client: RnsTcpInterface? = connect?.let { RnsTcpInterface(scope, "tcp_c").also { clients.add(it) } }
        val got = LinkBlockingQueueOf<String>()
        val links = LinkBlockingQueueOf<Link>()
        lateinit var node: Node
        lateinit var dest: Destination
        val port: Int

        init {
            val tx = object : Transmitter {
                override fun transmit(ifaceId: String, raw: ByteArray): Boolean = when (ifaceId) {
                    "tcp_0" -> server.send(raw)
                    "tcp_c" -> runBlocking { client!!.send(raw) == null }
                    else -> false
                }
                override fun floodable(): List<String> {
                    val out = ArrayList<String>()
                    if (server.isOnline) out.add("tcp_0")
                    if (client?.isOnline == true) out.add("tcp_c")
                    return out
                }
                override fun hwMtu(ifaceId: String): Int = 262144
            }
            node = Node(NodeConfig(id, log = { System.err.println("[kit] $it") }), tx)
            nodes.add(node)
            dest = node.addDestination("interop.raw") {
                acceptLinks = true; proveAll = true
                onPacket = { plain, _ -> got.add(String(plain)) }
                onLink = { l -> links.add(l) }
                onLinkPacket = { _, plain, _ -> got.add(String(plain)) }
            }
            server.onReceive = { raw -> node.inbound(raw, "tcp_0") }
            port = server.start()
            client?.let { c ->
                c.setReceiveCallback(RnsReceiveCallback { _, raw -> node.inbound(raw, "tcp_c") })
                val (host, p) = connect!!.split(":")
                c.connect(host, p.toInt())
            }
            node.start()
        }
    }

    class LinkBlockingQueueOf<T> : LinkedBlockingQueue<T>() {
        fun take(timeoutMs: Long, what: String): T = poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("timeout waiting for $what")
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(20) }
        failed = true
        throw AssertionError("timeout waiting for $what")
    }

    private fun h(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun num(v: Any?): Int = (v as Number).toInt()

    @Test
    fun announceBothWays() {
        val k = Kit()
        val p = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}"))
        waitFor("tcp peer") { k.server.peerCount() > 0 }

        // Python -> us
        p.send("cmd" to "announce")
        waitFor("we learn python's raw dest") { k.node.hopsTo(h(p.raw)) == 1 }
        waitFor("we learn python's lxmf dest") { k.node.hopsTo(h(p.delivery)) == 1 }
        val known = k.node.recall(h(p.delivery))
        assertNotNull(known)
        assertTrue("LXMF announce app data not stored", known!!.appData!!.isNotEmpty())
        assertNotNull("ratchet from the lxmf announce not stored", known.ratchet)

        // Us -> Python, twice: the second (newer) announce must be accepted too.
        assertTrue(k.node.announce(k.dest))
        val dh = k.dest.hashHex
        val ev = p.wait("announce", { it["dest"] == dh })
        assertEquals(1, num(ev["hops"]))
        Thread.sleep(1100)
        assertTrue(k.node.announce(k.dest))
        p.wait("announce", { it["dest"] == dh })
        p.send("cmd" to "has_path", "dest" to dh)
        val pe = p.wait("path")
        assertEquals(true, pe["known"])
        assertEquals(1, num(pe["hops"]))
    }

    @Test
    fun packetAndProofBothWays() {
        val k = Kit()
        val p = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}"))
        waitFor("tcp peer") { k.server.peerCount() > 0 }
        p.send("cmd" to "announce")
        waitFor("we learn python") { k.node.hopsTo(h(p.raw)) == 1 }
        k.node.announce(k.dest)
        val dh = k.dest.hashHex
        p.wait("announce", { it["dest"] == dh })

        // Python -> us, single packet, we prove it.
        p.send("cmd" to "send_packet", "dest" to dh, "data" to "ping from python")
        assertEquals("ping from python", k.got.take(10_000, "packet from python"))
        p.wait("packet_proved")

        // Us -> Python, python proves it.
        val r = k.node.sendData(h(p.raw), "ping from kotlin".toByteArray())!!
        val ev = p.wait("packet_received")
        assertEquals("ping from kotlin", ev["data"])
        assertTrue(r.result.get(10, TimeUnit.SECONDS).delivered)
    }

    @Test
    fun linkPythonToUs() {
        val k = Kit()
        val p = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}"))
        waitFor("tcp peer") { k.server.peerCount() > 0 }
        k.node.announce(k.dest)
        val dh = k.dest.hashHex
        p.wait("announce", { it["dest"] == dh })

        p.send("cmd" to "link", "dest" to dh)
        val ev = p.wait("link_established", null, 15_000)
        val linkId = ev["link"] as String
        val l = k.links.take(10_000, "our link active")
        assertEquals(linkId, l.idHex)
        // Data on the link, proved by us (proveAll).
        p.send("cmd" to "link_send", "link" to linkId, "data" to "link data from python")
        assertEquals("link data from python", k.got.take(10_000, "link data"))
        p.wait("link_proof")
        // Identify, keepalive round trip at a short interval, close.
        p.send("cmd" to "link_identify", "link" to linkId)
        waitFor("identified") { l.remoteIdentity != null }
        p.send("cmd" to "set_keepalive", "link" to linkId, "seconds" to 5)
        p.wait("keepalive_set")
        for (i in 1..8) { Thread.sleep(1000); System.err.println("t+${i}s our link state ${l.state}") }
        assertEquals(LinkState.ACTIVE, l.state)
        p.send("cmd" to "link_close", "link" to linkId)
        waitFor("we see the close") { k.node.links.get(l.id) == null }
    }

    @Test
    fun linkUsToPython() {
        val k = Kit()
        val p = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}"))
        waitFor("tcp peer") { k.server.peerCount() > 0 }
        p.send("cmd" to "announce")
        waitFor("we learn python") { k.node.hopsTo(h(p.raw)) == 1 }

        val l = k.node.links.initiate(h(p.raw))
        l.established.get(15, TimeUnit.SECONDS)
        val ev = p.wait("link_established")
        assertEquals(l.idHex, ev["link"])
        val r = k.node.links.send(l, "hello over link".toByteArray())
        val pe = p.wait("link_packet")
        assertEquals("hello over link", pe["data"])
        assertTrue(r.result.get(10, TimeUnit.SECONDS).delivered)
        k.node.links.identify(l, k.id)
        val ie = p.wait("link_identified")
        assertEquals(k.id.hashHex, ie["identity"])
        k.node.links.teardown(l)
        p.wait("link_closed")
    }

    /** Python A <-> us (transport) <-> Python B. A and B never connect to each other. */
    @Test
    fun threeNodeTransport() {
        val k = Kit()
        val pa = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}"))
        val pb = PyPeer.start(PyPeer.Opts(name = "B", connect = "127.0.0.1:${k.port}"))
        waitFor("two tcp peers") { k.server.peerCount() >= 2 }

        pa.send("cmd" to "announce")
        // B hears A's raw destination through our HEADER_2 rebroadcast at 2 hops.
        val ev = pb.wait("announce", { it["dest"] == pa.raw }, 15_000)
        assertEquals(2, num(ev["hops"]))
        pb.send("cmd" to "has_path", "dest" to pa.raw)
        val pe = pb.wait("path")
        assertEquals(k.node.idHashHex, pe["next_hop"])

        // B -> A single packet through us, proof back via the reverse table.
        pb.send("cmd" to "send_packet", "dest" to pa.raw, "data" to "B to A through kotlin")
        val got = pa.wait("packet_received", null, 15_000)
        assertEquals("B to A through kotlin", got["data"])
        assertEquals(2, num(got["hops"]))
        pb.wait("packet_proved", null, 15_000)

        // B -> A link through us: request in transit, proof validated in transit, data repeated both ways.
        pb.send("cmd" to "link", "dest" to pa.raw)
        val le = pb.wait("link_established", null, 20_000)
        val linkId = le["link"] as String
        pa.wait("link_established", { it["link"] == linkId }, 20_000)
        pb.send("cmd" to "link_send", "link" to linkId, "data" to "on the transit link")
        val lp = pa.wait("link_packet", null, 15_000)
        assertEquals("on the transit link", lp["data"])
        pb.wait("link_proof", null, 15_000)
        pb.send("cmd" to "link_close", "link" to linkId)
        pa.wait("link_closed", { it["link"] == linkId }, 15_000)

        // Path request: B asks for our destination it has not heard (we announced nothing yet).
        val dh = k.dest.hashHex
        pb.send("cmd" to "request_path", "dest" to dh)
        pb.wait("path_requested")
        waitFor("we count the path request") { k.node.stats().pathRequestsRx >= 1 }
        pb.wait("announce", { it["dest"] == dh }, 15_000)   // the path response
        pb.send("cmd" to "has_path", "dest" to dh)
        val pe2 = pb.wait("path")
        assertEquals(true, pe2["known"])
        assertEquals(1, num(pe2["hops"]))
    }

    /** Us behind a stock rnsd transport: we learn a peer at 2 hops with the rnsd identity as next hop. */
    @Test
    fun behindStockRnsd() {
        val port = freePort()
        val tr = PyPeer.start(PyPeer.Opts(name = "T", transport = true, listen = port))
        val pa = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:$port"))
        val k = Kit(connect = "127.0.0.1:$port")
        waitFor("we are connected", 15_000) { k.client!!.isOnline }
        Thread.sleep(500)

        pa.send("cmd" to "announce")
        waitFor("we learn A via T", 20_000) { k.node.hopsTo(h(pa.raw)) == 2 }
        assertEquals(tr.transportId, hex(k.node.paths.get(h(pa.raw))!!.nextHop))
        // Us -> A through rnsd with proof back.
        val r = k.node.sendData(h(pa.raw), "kotlin to A via rnsd".toByteArray())!!
        val got = pa.wait("packet_received", null, 15_000)
        assertEquals("kotlin to A via rnsd", got["data"])
        assertTrue(r.result.get(15, TimeUnit.SECONDS).delivered)
        // We announce; A must see us at 2 hops via rnsd.
        k.node.announce(k.dest)
        val dh = k.dest.hashHex
        val ev = pa.wait("announce", { it["dest"] == dh }, 15_000)
        assertEquals(2, num(ev["hops"]))
        // A -> us link through rnsd.
        pa.send("cmd" to "link", "dest" to dh)
        pa.wait("link_established", null, 20_000)
        k.links.take(15_000, "our link via rnsd")
        // (A path request answered by rnsd from its table is not asserted here: rnsd 1.5 rate-limits
        // path requests from a freshly connected peer, which made it time-dependent. Our own answering
        // of path requests is proven in threeNodeTransport.)
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
