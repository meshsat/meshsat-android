package net.meshsat.android.interop

import net.meshsat.android.lxmf.LxMessage
import net.meshsat.android.lxmf.LxMethod
import net.meshsat.android.lxmf.LxState
import net.meshsat.android.lxmf.LxmfAppData
import net.meshsat.android.lxmf.LxmfRouter
import net.meshsat.android.rns.Node
import net.meshsat.android.rns.NodeConfig
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.TcpServerInterface
import net.meshsat.android.rns.Transmitter
import net.meshsat.android.rns.hopsTo
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

/** Our LXMF endpoint against upstream Python LXMF 1.2.0 over TCP (the Bridge's lxmf_test.go). [MESHSAT-1553] */
class InteropLxmfTest {
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
    private val routers = ArrayList<LxmfRouter>()
    private val servers = ArrayList<TcpServerInterface>()

    @Before
    fun begin() { System.err.println("=== ${testName.methodName}") }

    @After
    fun tearDown() {
        routers.forEach { it.stop() }
        nodes.forEach { it.stop() }
        servers.forEach { it.stop() }
        PyPeer.stopAll(dumpLogs = failed)
    }

    inner class Kit(stampCost: Int = 0) {
        val id = RnsIdentity.generate()
        val server = TcpServerInterface("tcp_0", log = { System.err.println("[kit] $it") }).also { servers.add(it) }
        val got = LinkedBlockingQueue<LxMessage>()
        val node: Node
        val router: LxmfRouter
        val port: Int

        init {
            node = Node(NodeConfig(id, log = { System.err.println("[kit] $it") }), object : Transmitter {
                override fun transmit(ifaceId: String, raw: ByteArray): Boolean = server.send(raw)
                override fun floodable(): List<String> = if (server.isOnline) listOf("tcp_0") else emptyList()
                override fun hwMtu(ifaceId: String): Int = 262144
            })
            router = LxmfRouter(node, LxmfRouter.Config(id, "MeshSat test kit", stampCost = stampCost, enforceStamps = stampCost > 0,
                onMessage = { got.add(it) }, log = { System.err.println("[lxmf] $it") }))
            nodes.add(node); routers.add(router)
            server.onReceive = { raw -> node.inbound(raw, "tcp_0") }
            port = server.start()
            node.start(); router.start()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(20) }
        failed = true
        throw AssertionError("timeout waiting for $what")
    }

    private fun h(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    private fun take(q: LinkedBlockingQueue<LxMessage>, ms: Long, what: String): LxMessage = q.poll(ms, TimeUnit.MILLISECONDS) ?: run { failed = true; throw AssertionError("timeout waiting for $what") }

    private fun bringUp(k: Kit, p: PyPeer) {
        waitFor("tcp peer") { k.server.peerCount() > 0 }
        p.send("cmd" to "announce")
        waitFor("we learn python's delivery dest") { k.node.hopsTo(h(p.delivery)) == 1 }
        assertTrue(k.router.announce())
        p.wait("announce", { it["dest"] == k.router.hashHex })
    }

    @Test
    fun singlePacketBothWays() {
        val k = Kit()
        val p = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}"))
        waitFor("tcp peer") { k.server.peerCount() > 0 }
        p.send("cmd" to "announce")
        waitFor("we learn python's delivery dest") { k.node.hopsTo(h(p.delivery)) == 1 }
        assertTrue(k.router.announce())
        val dh = k.router.hashHex
        val ev = p.wait("announce", { it["dest"] == dh })
        val info = LxmfAppData.parse(h(ev["app_data"] as String))
        assertEquals("MeshSat test kit", info.displayName)

        // Python -> us, opportunistic single packet, we prove it.
        p.send("cmd" to "send_lxm", "dest" to dh, "content" to "hello kit", "title" to "t1", "method" to "opportunistic")
        val sent = p.wait("lxm_sent")
        val m = take(k.got, 15_000, "the LXMF message")
        assertEquals("hello kit", m.contentString); assertEquals("t1", m.titleString)
        assertTrue(m.signatureValid); assertEquals(LxMethod.OPPORTUNISTIC, m.method)
        assertEquals(sent["hash"], m.hashHex)
        p.wait("lxm_delivered", null, 15_000)

        // Us -> Python, opportunistic (auto: ratchet announced, no link), python proves it.
        val o = k.router.send(h(p.delivery), "hello python".toByteArray(), "t2".toByteArray())
        assertEquals(LxMethod.OPPORTUNISTIC, o.method)
        val rcv = p.wait("lxm_received", null, 15_000)
        assertEquals("hello python", rcv["content"]); assertEquals("t2", rcv["title"]); assertEquals(true, rcv["signature_validated"])
        assertEquals(o.hashHex, rcv["hash"])
        assertEquals(LxState.DELIVERED, o.result.get(15, TimeUnit.SECONDS))
        val peers = k.router.peers()
        assertEquals(1, peers.size); assertEquals(p.delivery, peers[0].destHash); assertEquals("A", peers[0].displayName)
    }

    @Test
    fun directLinkBothWays() {
        val k = Kit()
        val p = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}"))
        bringUp(k, p)
        val dh = k.router.hashHex

        // Python -> us over a link (DIRECT), 310 bytes of content: a link packet.
        val content = "d".repeat(310)
        p.send("cmd" to "send_lxm", "dest" to dh, "content" to content, "method" to "direct")
        val m = take(k.got, 20_000, "the direct LXMF message")
        assertEquals(content, m.contentString); assertEquals(LxMethod.DIRECT, m.method); assertTrue(m.signatureValid)
        p.wait("lxm_delivered", null, 15_000)

        // Us -> Python over a link we open, then a second one that must reuse it.
        val o = k.router.send(h(p.delivery), "b".repeat(300).toByteArray(), method = LxMethod.DIRECT)
        assertEquals(LxState.DELIVERED, o.result.get(20, TimeUnit.SECONDS))
        val rcv = p.wait("lxm_received", null, 20_000)
        assertEquals(o.hashHex, rcv["hash"]); assertEquals(true, rcv["signature_validated"])
        val before = k.node.links.all().size
        val o2 = k.router.send(h(p.delivery), "c".repeat(300).toByteArray(), method = LxMethod.DIRECT)
        assertEquals(LxState.DELIVERED, o2.result.get(20, TimeUnit.SECONDS))
        p.wait("lxm_received", null, 20_000)
        assertEquals(before, k.node.links.all().size)
        // Python replies with its own choice ("auto": a link exists, so direct on it) and we receive it.
        p.send("cmd" to "send_lxm", "dest" to dh, "content" to "auto reply", "method" to "auto")
        val reply = take(k.got, 20_000, "python's auto reply")
        assertEquals("auto reply", reply.contentString)
        p.wait("lxm_delivered", null, 15_000)
    }

    @Test
    fun stampsAtCost6() {
        val k = Kit(stampCost = 6)
        val p = PyPeer.start(PyPeer.Opts(name = "S", connect = "127.0.0.1:${k.port}", stampCost = 6, enforce = true, loglevel = 6))
        bringUp(k, p)
        val dh = k.router.hashHex
        assertEquals(6, k.router.peerInfo(h(p.delivery))!!.stampCost)

        p.send("cmd" to "send_lxm", "dest" to dh, "content" to "stamped from python", "method" to "opportunistic")
        val m = take(k.got, 60_000, "the stamped message")
        assertTrue(m.stampValid); assertTrue(m.stampValue >= 6)
        p.wait("lxm_delivered", null, 15_000)

        val o = k.router.send(h(p.delivery), "stamped from kotlin".toByteArray())
        assertNotNull(o.message.stamp)
        val rcv = p.wait("lxm_received", null, 30_000)
        assertEquals(true, rcv["stamp_valid"])
        assertEquals(LxState.DELIVERED, o.result.get(20, TimeUnit.SECONDS))
    }

    @Test
    fun resourceBothWays() {
        val k = Kit()
        val p = PyPeer.start(PyPeer.Opts(name = "A", connect = "127.0.0.1:${k.port}", loglevel = 5))
        bringUp(k, p)
        val dh = k.router.hashHex

        // Python -> us: 2 KB of content forces an RNS resource on a link.
        val big = "resource payload from python. ".repeat(70)
        p.send("cmd" to "send_lxm", "dest" to dh, "content" to big, "method" to "direct")
        val m = take(k.got, 30_000, "the resource message")
        assertEquals(big, m.contentString); assertEquals(LxMethod.DIRECT, m.method); assertTrue(m.signatureValid)
        p.wait("lxm_delivered", null, 30_000)

        // Us -> Python: 3 KB of content as a resource on our link.
        val big2 = "resource payload from kotlin. ".repeat(100)
        val o = k.router.send(h(p.delivery), big2.toByteArray(), "big".toByteArray())
        assertEquals(LxMethod.DIRECT, o.method); assertEquals(LxmfRouter.Representation.RESOURCE, o.representation)
        val rcv = p.wait("lxm_received", null, 30_000)
        assertEquals(o.hashHex, rcv["hash"]); assertEquals(true, rcv["signature_validated"]); assertEquals(big2, rcv["content"])
        assertEquals(LxState.DELIVERED, o.result.get(30, TimeUnit.SECONDS))
    }
}
