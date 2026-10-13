package net.meshsat.android.lxmf

import net.meshsat.android.rns.Node
import net.meshsat.android.rns.NodeConfig
import net.meshsat.android.rns.PaidPolicy
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.Transmitter
import net.meshsat.android.rns.crossesPaidLink
import net.meshsat.android.rns.setPaidPolicy
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Two LXMF endpoints on in-memory nodes, end to end, including the satellite rules. [MESHSAT-1553] */
class LxmfRouterTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun addProvider() { if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider()) }
    }

    private val wires = ConcurrentHashMap<String, Pair<Node, String>>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val nodes = ArrayList<Node>()
    private val routers = ArrayList<LxmfRouter>()

    private inner class Tx(val name: String, val paid: Set<String> = emptySet(), val delayMs: Long = 0) : Transmitter {
        override fun transmit(ifaceId: String, raw: ByteArray): Boolean {
            val (peer, peerIface) = wires["$name/$ifaceId"] ?: return true
            val cp = raw.copyOf()
            pool.execute { if (delayMs > 0) Thread.sleep(delayMs); peer.inbound(cp, peerIface) }
            return true
        }
        override fun floodable(): List<String> = wires.keys.filter { it.startsWith("$name/") }.map { it.substringAfter('/') }.filter { it !in paid }.sorted()
        override fun hwMtu(ifaceId: String): Int = 500
    }

    private fun endpoint(name: String, stampCost: Int = 0, paid: Set<String> = emptySet(), delayMs: Long = 0): Pair<LxmfRouter, LinkedBlockingQueue<LxMessage>> {
        val got = LinkedBlockingQueue<LxMessage>()
        val n = Node(NodeConfig(RnsIdentity.generate(), log = { System.err.println("[$name] $it") }), Tx(name, paid, delayMs))
        val r = LxmfRouter(n, LxmfRouter.Config(n.identity, "Endpoint $name", stampCost = stampCost, enforceStamps = stampCost > 0,
            onMessage = { got.add(it) }, log = { System.err.println("[$name lxmf] $it") }))
        n.start(); r.start()
        nodes.add(n); routers.add(r)
        return r to got
    }

    private fun connect(a: String, aIface: String, b: String, bIface: String) {
        val na = nodes[routers.indexOfFirst { it.displayName == "Endpoint $a" }]
        val nb = nodes[routers.indexOfFirst { it.displayName == "Endpoint $b" }]
        wires["$a/$aIface"] = nb to bIface
        wires["$b/$bIface"] = na to aIface
    }

    @After
    fun stop() { routers.forEach { it.stop() }; nodes.forEach { it.stop() }; pool.shutdownNow() }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(10) }
        throw AssertionError("timeout waiting for $what")
    }

    @Test
    fun `opportunistic, direct with link reuse, a resource and the backchannel`() {
        val (a, gotA) = endpoint("a")
        val (b, gotB) = endpoint("b")
        connect("a", "ab", "b", "ba")
        assertTrue(b.announce()); assertTrue(a.announce())
        waitFor("A knows B") { a.node.paths.has(b.hash) }
        waitFor("B knows A") { b.node.paths.has(a.hash) }
        assertEquals("Endpoint b", a.peerInfo(b.hash)!!.displayName)
        assertTrue(a.peers().any { it.destHash == b.hashHex && it.hasRatchet })

        // Auto picks opportunistic for a short message to a peer with a ratchet and no link.
        val o1 = a.send(b.hash, "hello b".toByteArray(), "t1".toByteArray())
        assertEquals(LxMethod.OPPORTUNISTIC, o1.method)
        assertEquals(LxState.DELIVERED, o1.result.get(15, TimeUnit.SECONDS))
        val m1 = gotB.poll(5, TimeUnit.SECONDS)!!
        assertEquals("hello b", m1.contentString); assertEquals("t1", m1.titleString)
        assertTrue(m1.signatureValid); assertEquals(LxMethod.OPPORTUNISTIC, m1.method)
        assertEquals(o1.hashHex, m1.hashHex)

        // Direct: a link, then a second message reuses it.
        val o2 = a.send(b.hash, "d".repeat(310).toByteArray(), method = LxMethod.DIRECT)
        assertEquals(LxState.DELIVERED, o2.result.get(20, TimeUnit.SECONDS))
        assertEquals(LxMethod.DIRECT, gotB.poll(5, TimeUnit.SECONDS)!!.method)
        val linksBefore = a.node.links.all().size
        val o3 = a.send(b.hash, "e".repeat(300).toByteArray(), method = LxMethod.DIRECT)
        assertEquals(LxState.DELIVERED, o3.result.get(20, TimeUnit.SECONDS))
        gotB.poll(5, TimeUnit.SECONDS)!!
        assertEquals(linksBefore, a.node.links.all().size)

        // A resource for a large message on the same link.
        val big = "resource payload from a. ".repeat(120)
        val o4 = a.send(b.hash, big.toByteArray(), "big".toByteArray(), method = LxMethod.DIRECT)
        assertEquals(LxmfRouter.Representation.RESOURCE, o4.representation)
        assertEquals(LxState.DELIVERED, o4.result.get(30, TimeUnit.SECONDS))
        assertEquals(big, gotB.poll(5, TimeUnit.SECONDS)!!.contentString)

        // The backchannel: B answers on the link A opened and identified on, no new link.
        waitFor("B sees A's identity on the link") { b.deliveryLinkAvailable(a.hash) }
        val before = b.node.links.all().size
        val o5 = b.send(a.hash, "reply on the backchannel".toByteArray(), method = LxMethod.DIRECT)
        assertEquals(LxState.DELIVERED, o5.result.get(20, TimeUnit.SECONDS))
        assertEquals("reply on the backchannel", gotA.poll(5, TimeUnit.SECONDS)!!.contentString)
        assertEquals(before, b.node.links.all().size)
    }

    @Test
    fun `stamps at cost 6 are generated for a peer that demands them and checked on receipt`() {
        val (a, _) = endpoint("a", stampCost = 6)
        val (b, gotB) = endpoint("b", stampCost = 6)
        connect("a", "ab", "b", "ba")
        b.announce(); a.announce()
        waitFor("A knows B") { a.node.paths.has(b.hash) }
        waitFor("B knows A") { b.node.paths.has(a.hash) }
        assertEquals(6, a.peerInfo(b.hash)!!.stampCost)
        val o = a.send(b.hash, "stamped".toByteArray())
        assertNotNull(o.message.stamp)
        assertEquals(LxState.DELIVERED, o.result.get(20, TimeUnit.SECONDS))
        val m = gotB.poll(5, TimeUnit.SECONDS)!!
        assertTrue(m.stampChecked); assertTrue(m.stampValid); assertTrue(m.stampValue >= 6)
    }

    @Test
    fun `across a paid link auto picks direct and waits for the slow round trip`() {
        // a =sat= b, both ends with a policy that lists both delivery destinations and 4 extra hops,
        // 2 s each way: 6 x (1 + 4 + 1) s of link patience covers 4 s round trips with room.
        val (a, gotA) = endpoint("a", paid = setOf("sat"), delayMs = 2000)
        val (b, gotB) = endpoint("b", paid = setOf("sat"), delayMs = 2000)
        connect("a", "sat", "b", "sat")
        for (r in routers) r.node.setPaidPolicy("sat", PaidPolicy(listOf(a.hash, b.hash), extraHops = 4))
        assertTrue(b.announce()); assertTrue(a.announce())
        waitFor("A knows B over the satellite", 15_000) { a.node.paths.has(b.hash) }
        waitFor("B knows A over the satellite", 15_000) { b.node.paths.has(a.hash) }
        assertEquals(5, a.node.paths.hops(b.hash))
        assertTrue(a.node.crossesPaidLink(b.hash))
        val o = a.send(b.hash, "across the sky".toByteArray())
        assertEquals(LxMethod.DIRECT, o.method)
        assertEquals(LxState.DELIVERED, o.result.get(60, TimeUnit.SECONDS))
        assertEquals("across the sky", gotB.poll(5, TimeUnit.SECONDS)!!.contentString)
        // The reply comes back on the same link.
        val r = b.send(a.hash, "and back".toByteArray())
        assertEquals(LxMethod.DIRECT, r.method)
        assertEquals(LxState.DELIVERED, r.result.get(60, TimeUnit.SECONDS))
        assertEquals("and back", gotA.poll(5, TimeUnit.SECONDS)!!.contentString)
    }

    @Test
    fun `a message to an unreachable peer fails after the attempts`() {
        val (a, _) = endpoint("a")
        val (b, _) = endpoint("b")
        connect("a", "ab", "b", "ba")
        b.announce()
        waitFor("A knows B") { a.node.paths.has(b.hash) }
        wires.clear()   // the wire breaks
        val o = a.send(b.hash, "lost".toByteArray(), method = LxMethod.OPPORTUNISTIC)
        assertEquals(LxState.FAILED, o.result.get(120, TimeUnit.SECONDS))
        assertTrue(o.attempts >= 3)
    }
}
