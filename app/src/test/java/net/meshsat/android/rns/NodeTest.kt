package net.meshsat.android.rns

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Nodes wired back to back in memory (the Bridge's node_test.go): a packet a
 * node transmits on "ab" arrives at the peer on "ba" a moment later, like a wire.
 * [MESHSAT-1553]
 */
class NodeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun addProvider() { if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider()) }
    }

    private class FakeNet {
        class Wire(val peer: Node, val peerIface: String)
        val wires = ConcurrentHashMap<String, Wire>()   // nodeName/iface
        val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        fun connect(a: Node, aName: String, aIface: String, b: Node, bName: String, bIface: String) {
            wires["$aName/$aIface"] = Wire(b, bIface)
            wires["$bName/$bIface"] = Wire(a, aIface)
        }
    }

    private class FakeTx(val net: FakeNet, val name: String) : Transmitter {
        val sent = ConcurrentHashMap<String, Int>()
        val paid = HashSet<String>()
        var delayMs = 0L
        override fun transmit(ifaceId: String, raw: ByteArray): Boolean {
            sent.merge(ifaceId, 1, Int::plus)
            val w = net.wires["$name/$ifaceId"] ?: return true
            val cp = raw.copyOf()
            net.pool.execute { if (delayMs > 0) Thread.sleep(delayMs); w.peer.inbound(cp, w.peerIface) }
            return true
        }
        override fun floodable(): List<String> = net.wires.keys.filter { it.startsWith("$name/") }.map { it.substringAfter('/') }.filter { it !in paid }.sorted()
        override fun hwMtu(ifaceId: String): Int = 500
    }

    private val net = FakeNet()
    private val nodes = ArrayList<Node>()

    private fun node(name: String): Pair<Node, FakeTx> {
        val tx = FakeTx(net, name)
        val n = Node(NodeConfig(RnsIdentity.generate(), log = { }), tx)
        nodes.add(n)
        return n to tx
    }

    @After
    fun stopAll() { nodes.forEach { it.stop() }; net.pool.shutdownNow() }

    private fun waitFor(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(10) }
        throw AssertionError("timeout waiting for $what")
    }

    @Test
    fun `three nodes in a line, A - T - B, with T the transport`() {
        val (a, _) = node("a"); val (t, _) = node("t"); val (b, _) = node("b")
        net.connect(a, "a", "at", t, "t", "ta")
        net.connect(t, "t", "tb", b, "b", "bt")
        a.start(); t.start(); b.start()

        val got = AtomicReference<ByteArray>()
        val linkGot = AtomicReference<ByteArray>()
        val bDest = b.addDestination("test.delivery") {
            acceptLinks = true; proveAll = true
            onPacket = { plain, _ -> got.set(plain) }
            onLinkPacket = { _, plain, _ -> linkGot.set(plain) }
        }
        a.addDestination("test.delivery")

        // B announces; T learns it at 1 hop and rebroadcasts as HEADER_2; A learns it at 2 hops.
        assertTrue(b.announce(bDest))
        waitFor("T learns B") { t.paths.hops(bDest.hash) == 1 }
        waitFor("A learns B via T", 10_000) { a.paths.hops(bDest.hash) == 2 }
        assertArrayEquals(t.idHash, a.paths.get(bDest.hash)!!.nextHop)
        assertNotNull(a.recall(bDest.hash)?.identity)

        // Single packet A -> B through T, proof back through the reverse table.
        val r = a.sendData(bDest.hash, "hello through transport".toByteArray())!!
        waitFor("B receives") { got.get()?.let { String(it) } == "hello through transport" }
        assertTrue(r.result.get(5, TimeUnit.SECONDS).delivered)

        // Link A -> B through T: request in transit, proof validated in transit, data proved.
        val l = a.links.initiate(bDest.hash)
        l.established.get(5, TimeUnit.SECONDS)
        waitFor("responder active") { b.links.all().any { it.id.contentEquals(l.id) && it.state == LinkState.ACTIVE } }
        val lr = a.links.send(l, "over the link".toByteArray())
        waitFor("link data at B") { linkGot.get()?.let { String(it) } == "over the link" }
        assertTrue(lr.result.get(5, TimeUnit.SECONDS).delivered)
        a.links.identify(l, a.identity)
        waitFor("B identified A") { b.links.all().any { it.id.contentEquals(l.id) && it.remoteIdentity != null } }
        assertArrayEquals(a.identity.publicBytes, b.links.get(l.id)!!.remoteIdentity)
        a.links.teardown(l)
        waitFor("B closed") { b.links.get(l.id) == null }

        // Path request from A for a destination C that only announces later.
        val (c, _) = node("c")
        net.connect(b, "b", "bc", c, "c", "cb")
        c.start()
        val cDest = c.addDestination("test.delivery")
        val f = a.requestPath(cDest.hash, 5000)
        Thread.sleep(200)
        c.announce(cDest)
        assertTrue(f.get(6, TimeUnit.SECONDS))
        assertEquals(3, a.paths.hops(cDest.hash))
    }

    @Test
    fun `a resource crosses a link and a second one reuses it`() {
        val (a, _) = node("a"); val (b, _) = node("b")
        net.connect(a, "a", "ab", b, "b", "ba")
        a.start(); b.start()
        val got = AtomicReference<ByteArray>()
        val bDest = b.addDestination("test.delivery") {
            acceptLinks = true
            acceptResources = { size -> size in 1..(1 shl 20) }
            onLinkResource = { _, data -> got.set(data) }
        }
        b.announce(bDest)
        waitFor("A learns B") { a.paths.hops(bDest.hash) == 1 }
        val l = a.links.initiate(bDest.hash)
        l.established.get(5, TimeUnit.SECONDS)
        val payload = ByteArray(3000) { (it % 251).toByte() }
        val res = a.links.sendResource(l, payload)
        val out = res.result.get(10, TimeUnit.SECONDS)
        assertTrue(out.error ?: "", out.delivered)
        waitFor("resource at B") { got.get() != null }
        assertArrayEquals(payload, got.get())
        val res2 = a.links.sendResource(l, ByteArray(20_000) { (it * 7).toByte() })
        assertTrue(res2.result.get(15, TimeUnit.SECONDS).delivered)
        assertEquals(1, a.links.all().size)
    }

    @Test
    fun `the packet filter drops duplicates and packets for another transport`() {
        val (a, _) = node("a")
        val id = RnsIdentity.generate()
        val raw = Header(destType = Rns.DEST_SINGLE, packetType = Rns.PACKET_DATA, destHash = id.destHash("x"), data = "d".toByteArray()).pack()
        // A SINGLE packet for an unknown remote destination is not ours: declined, so the
        // legacy framing may try, but it enters the hash list and a second copy is filtered.
        assertFalse(a.inbound(raw.copyOf(), "x"))
        val before = a.stats().filtered
        a.inbound(raw.copyOf(), "x")
        assertEquals(before + 1, a.stats().filtered)
        val other = ByteArray(16).also { it[0] = 1 }
        val raw2 = Header.rewriteForTransport(raw, other)
        raw2[raw2.size - 1] = (raw2[raw2.size - 1].toInt() xor 0xFF).toByte()
        a.inbound(raw2, "x")
        assertEquals(before + 2, a.stats().filtered)
        // Things the node never handles fall through for the legacy stack.
        assertFalse(a.inbound(ByteArray(10), "x"))
        val plain = Header(destType = Rns.DEST_PLAIN, destHash = Rns.plainDestHash("meshsat.legacy"), data = "x".toByteArray()).pack()
        assertFalse(a.inbound(plain, "x"))
    }

    @Test
    fun `a newer announce replaces the path, an older one does not`() {
        val (a, _) = node("a")
        val id = RnsIdentity.generate()
        val old = Announce.create(id, "lxmf.delivery", null, nowSec = 1_700_000_000)
        val newer = Announce.create(id, "lxmf.delivery", null, nowSec = 1_700_000_100)
        assertTrue(a.inbound(newer.packet(), "x"))
        assertEquals(1, a.paths.hops(newer.destHash))
        val first = a.paths.get(newer.destHash)!!
        // Older emission, more hops: kept out.
        val oldRaw = old.transportPacket(Rns.randomBytes(16), 2, Rns.CTX_NONE)
        a.inbound(oldRaw, "y")
        assertTrue(a.paths.get(newer.destHash) === first)
        // Newer emission at more hops replaces (the path's blob set is older).
        val newest = Announce.create(id, "lxmf.delivery", null, nowSec = 1_700_000_200)
        a.inbound(newest.transportPacket(Rns.randomBytes(16), 2, Rns.CTX_NONE), "y")
        assertEquals(3, a.paths.hops(newer.destHash))   // the packet's 2 plus this hop
        assertEquals("y", a.paths.get(newer.destHash)!!.iface)
    }

    @Test
    fun `a neighbour heard across a paid link gets HEADER_1 packets, however many hops it counts`() {
        val (a, _) = node("a"); val (b, _) = node("b")
        net.connect(a, "a", "sat", b, "b", "sat")
        for (n in listOf(a, b)) n.setPaidPolicy("sat", PaidPolicy(listOf(), extraHops = 8))
        val seen = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val bDest = b.addDestination("test.delivery") { proveAll = true; onPacket = { _, pkt -> seen.add(pkt.hdr.headerType) } }
        for (n in listOf(a, b)) n.setPaidPolicy("sat", PaidPolicy(listOf(bDest.hash), extraHops = 8))
        a.start(); b.start()
        assertTrue(b.announce(bDest))
        waitFor("A learns B over the paid link") { a.paths.has(bDest.hash) }
        assertEquals(9, a.paths.hops(bDest.hash))
        assertTrue(a.paths.get(bDest.hash)!!.direct)   // next hop is B itself
        val r = a.sendData(bDest.hash, "to the neighbour".toByteArray())!!
        assertTrue(r.result.get(5, TimeUnit.SECONDS).delivered)
        assertEquals(listOf(Rns.HEADER_1), seen.toList())
        // A HEADER_2 naming B's own destination as the transport id is still taken as local (older senders).
        val raw = Header.rewriteForTransport(Header(destHash = bDest.hash, data = a.recall(bDest.hash)!!.identity!!.encrypt("tolerated".toByteArray())).pack(), bDest.hash)
        assertTrue(b.inbound(raw, "sat"))
        waitFor("tolerated packet delivered") { seen.size == 2 }
    }

    @Test
    fun `a paid interface carries only listed destinations, with extra hops`() {
        val (a, txA) = node("a"); val (b, txB) = node("b")
        net.connect(a, "a", "sat", b, "b", "sat")
        txA.paid.add("sat"); txB.paid.add("sat")
        a.start(); b.start()
        val listed = b.addDestination("lxmf.delivery")
        val unlisted = b.addDestination("meshsat.node")
        // Nothing floodable: an announce goes nowhere without a policy.
        assertFalse(b.announce(unlisted))
        assertEquals(0, txB.sent["sat"] ?: 0)
        for (n in listOf(a, b)) n.setPaidPolicy("sat", PaidPolicy(listOf(listed.hash), extraHops = 20))
        assertFalse(b.announce(unlisted))
        assertTrue(b.announce(listed))
        waitFor("A learns the listed destination over the paid link") { a.paths.has(listed.hash) }
        assertEquals(21, a.paths.hops(listed.hash))   // 1 + 20 extra
        assertTrue(a.crossesPaidLink(listed.hash))
        assertNull(a.paths.get(unlisted.hash))
        // The interval gate: a second announce inside five minutes is held back, so nothing is sent.
        val tx = b.stats().paidTx
        assertFalse(b.announce(listed))
        assertEquals(tx, b.stats().paidTx)
        assertTrue(b.stats().paidSuppressed > 0)
    }
}
