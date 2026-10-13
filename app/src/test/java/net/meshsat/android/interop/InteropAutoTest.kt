package net.meshsat.android.interop

import net.meshsat.android.rns.AutoInterface
import net.meshsat.android.rns.Link
import net.meshsat.android.rns.Node
import net.meshsat.android.rns.NodeConfig
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.Transmitter
import net.meshsat.android.rns.announce
import net.meshsat.android.rns.hopsTo
import net.meshsat.android.rns.sendData
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.net.Inet6Address
import java.net.NetworkInterface
import java.security.Security
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Our AutoInterface on one end of a veth pair, a stock Python RNS AutoInterface on the
 * other (the Bridge's ipmesh_test.go): discovery, announces, a packet with its proof and
 * a link, over link-local multicast and UDP. Needs the pair on the host:
 * `sudo ip link add veth0 type veth peer name veth1 && sudo ip link set veth0 up && sudo ip link set veth1 up`
 * (the SDK VM has it); skipped otherwise. [MESHSAT-1553 P4]
 */
class InteropAutoTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            PyEnv.python()
        }

        /** The two veth ends with link-local addresses, ours first, or null. */
        fun vethPair(): Pair<String, String>? {
            fun hasLinkLocal(n: String): Boolean = try {
                NetworkInterface.getByName(n)?.let { i -> i.isUp && i.inetAddresses.toList().any { it is Inet6Address && it.isLinkLocalAddress } } == true
            } catch (_: Exception) { false }
            return if (hasLinkLocal("veth0") && hasLinkLocal("veth1")) "veth0" to "veth1" else null
        }
    }

    private var failed = false
    private val nodes = ArrayList<Node>()
    private val autos = ArrayList<AutoInterface>()

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
        autos.forEach { it.stop() }
        PyPeer.stopAll(dumpLogs = failed)
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(50) }
        failed = true
        throw AssertionError("timeout waiting for $what")
    }

    private fun h(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    @Test
    fun announcePacketProofAndLinkOverAutoInterface() {
        val pair = vethPair()
        Assume.assumeTrue("no veth0/veth1 pair with link-local IPv6 addresses on this host", pair != null)
        val (ours, theirs) = pair!!
        val id = RnsIdentity.generate()
        lateinit var node: Node
        val auto = AutoInterface("auto_0", devices = listOf(ours), onPacket = { raw -> node.inbound(raw, "auto_0") }, log = { System.err.println("[auto] $it") }).also { autos.add(it) }
        node = Node(NodeConfig(id, log = { System.err.println("[kit] $it") }), object : Transmitter {
            override fun transmit(ifaceId: String, raw: ByteArray): Boolean = auto.send(raw)
            override fun floodable(): List<String> = if (auto.online) listOf("auto_0") else emptyList()
            override fun hwMtu(ifaceId: String): Int = AutoInterface.HW_MTU
        }).also { nodes.add(it) }
        val got = LinkedBlockingQueue<String>()
        val links = LinkedBlockingQueue<Link>()
        val dest = node.addDestination("interop.raw") {
            acceptLinks = true; proveAll = true
            onPacket = { plain, _ -> got.add(String(plain)) }
            onLink = { l -> links.add(l) }
            onLinkPacket = { _, plain, _ -> got.add(String(plain)) }
        }
        assertTrue("AutoInterface start on $ours", auto.start())
        node.start()

        val p = PyPeer.start(PyPeer.Opts(name = "A", auto = theirs, loglevel = 5))
        waitFor("python discovered as a peer") { auto.peerCount() == 1 }
        assertEquals(theirs.let { ours }, auto.peers()[0].device)

        // Announces both ways over the pair. Python ignores discovery for its first 1.9 s and
        // never repeats an announce by itself, so ours is asked for again until the path is known.
        var tries = 0
        waitFor("we learn python's raw dest over auto_0", 40_000) {
            val known = node.hopsTo(h(p.raw)) == 1 && node.paths.get(h(p.raw))?.iface == "auto_0"
            if (!known && tries++ % 40 == 0) p.send("cmd" to "announce")
            known
        }
        assertTrue(node.announce(dest))
        p.wait("announce", { it["dest"] == dest.hashHex }, 20_000)

        // A packet with its proof, each way.
        p.send("cmd" to "send_packet", "dest" to dest.hashHex, "data" to "hello over multicast peering")
        assertEquals("hello over multicast peering", got.poll(20, TimeUnit.SECONDS) ?: run { failed = true; throw AssertionError("no packet over auto") })
        p.wait("packet_proved", null, 20_000)
        val r = node.sendData(h(p.raw), "hello from the phone".toByteArray())!!
        p.wait("packet_received", { it["data"] == "hello from the phone" }, 20_000)
        assertTrue(r.result.get(20, TimeUnit.SECONDS).delivered)

        // A link from python to us, with a packet on it.
        p.send("cmd" to "link", "dest" to dest.hashHex)
        val l = links.poll(20, TimeUnit.SECONDS) ?: run { failed = true; throw AssertionError("no link over auto") }
        val est = p.wait("link_established", null, 20_000)
        assertEquals(l.idHex, est["link"])
        p.send("cmd" to "link_send", "link" to l.idHex, "data" to "on the link")
        assertEquals("on the link", got.poll(20, TimeUnit.SECONDS))
        p.wait("link_proof", null, 20_000)
        assertTrue(auto.rxBytes > 0 && auto.txBytes > 0)
    }
}
