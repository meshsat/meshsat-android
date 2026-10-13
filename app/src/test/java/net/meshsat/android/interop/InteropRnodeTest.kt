package net.meshsat.android.interop

import net.meshsat.android.rnode.RnodeDriver
import net.meshsat.android.rnode.RnodeLink
import net.meshsat.android.rnode.RnodeProtocol
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.Security
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Our RNode driver on one software RNode, a stock rnsd RNodeInterface on the other
 * (the Bridge's rnode_test.go), with CrossTalk's EU preset on both: configuration,
 * announces, a packet with its proof each way. [MESHSAT-1553 P4]
 */
class InteropRnodeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            PyEnv.python()
        }
    }

    private var failed = false
    private var fake: Process? = null
    private val nodes = ArrayList<Node>()
    private val drivers = ArrayList<RnodeDriver>()

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
        drivers.forEach { it.close() }
        PyPeer.stopAll(dumpLogs = failed)
        fake?.destroyForcibly()
    }

    /** A pseudo-terminal end as an RNode link: a reader thread feeds the driver. */
    private class PtyLink(path: String) : RnodeLink {
        private val out = FileOutputStream(File(path))
        private val input = FileInputStream(File(path))
        @Volatile var closed = false
        fun startReader(driver: RnodeDriver) {
            Thread({
                val buf = ByteArray(4096)
                while (!closed) {
                    val n = try { input.read(buf) } catch (_: Exception) { -1 }
                    if (n < 0) { if (!closed) driver.linkLost("pty closed"); break }
                    if (n > 0) driver.feed(buf.copyOf(n))
                }
            }, "pty-reader").apply { isDaemon = true }.start()
        }
        override fun write(b: ByteArray) { out.write(b); out.flush() }
        override fun close() { closed = true; try { out.close() } catch (_: Exception) {}; try { input.close() } catch (_: Exception) {} }
    }

    /** Runs fake_rnode.py and returns the two pty paths. */
    private fun startFakeRnodes(): Pair<String, String> {
        val res = javaClass.getResource("/interop/fake_rnode.py") ?: throw AssertionError("interop/fake_rnode.py missing")
        val script = File.createTempFile("fake_rnode", ".py").also { it.deleteOnExit(); res.openStream().use { s -> it.outputStream().use { o -> s.copyTo(o) } } }
        val p = ProcessBuilder(PyEnv.python(), script.path).redirectErrorStream(false).start()
        fake = p
        val r = p.inputStream.bufferedReader()
        var a = ""; var b = ""
        val deadline = System.currentTimeMillis() + 10_000
        while ((a.isEmpty() || b.isEmpty()) && System.currentTimeMillis() < deadline) {
            val line = r.readLine() ?: break
            val f = line.trim().split(Regex("\\s+"))
            if (f.size == 2 && f[0] == "A") a = f[1]
            if (f.size == 2 && f[0] == "B") b = f[1]
        }
        if (a.isEmpty() || b.isEmpty()) throw AssertionError("fake_rnode.py printed no pty paths")
        return a to b
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(50) }
        failed = true
        throw AssertionError("timeout waiting for $what")
    }

    private fun h(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    @Test
    fun rnodeAgainstRnsd() {
        val (ptyA, ptyB) = startFakeRnodes()
        val preset = RnodeProtocol.preset("eu-868")!!.params
        val py = PyPeer.start(PyPeer.Opts(name = "R", rnode = ptyA, rnodeParams = preset, loglevel = 5))

        val id = RnsIdentity.generate()
        lateinit var node: Node
        val link = PtyLink(ptyB)
        val driver = RnodeDriver(link, RnodeDriver.Options(name = "rnode_0", params = preset, flowControl = true,
            onPacket = { raw -> node.inbound(raw, "rnode_0") }, log = { System.err.println("[rnode] $it") })).also { drivers.add(it) }
        link.startReader(driver)
        node = Node(NodeConfig(id, log = { System.err.println("[kit] $it") }), object : Transmitter {
            override fun transmit(ifaceId: String, raw: ByteArray): Boolean = driver.send(raw) == null
            override fun floodable(): List<String> = if (driver.online) listOf("rnode_0") else emptyList()
            override fun hwMtu(ifaceId: String): Int = RnodeProtocol.HW_MTU
            override fun bitrate(ifaceId: String): Int = driver.stats().bitrateBps.toInt()
        }).also { nodes.add(it) }
        val got = LinkedBlockingQueue<String>()
        val dest = node.addDestination("interop.raw") { acceptLinks = true; proveAll = true; onPacket = { plain, _ -> got.add(String(plain)) } }
        assertNull(driver.open())
        node.start()
        val st = driver.stats()
        assertEquals(preset.frequency, st.frequency); assertEquals(preset.sf, st.sf); assertTrue(st.radioOn)

        // Announces both ways over the "air".
        py.send("cmd" to "announce")
        waitFor("we hear python over the rnode") { node.hopsTo(h(py.raw)) == 1 && node.paths.get(h(py.raw))?.iface == "rnode_0" }
        assertTrue(node.announce(dest))
        py.wait("announce", { it["dest"] == dest.hashHex }, 20_000)

        // Packets with proofs both ways.
        py.send("cmd" to "send_packet", "dest" to dest.hashHex, "data" to "lora hello")
        assertEquals("lora hello", got.poll(20, TimeUnit.SECONDS) ?: run { failed = true; throw AssertionError("no packet over the rnode") })
        py.wait("packet_proved", null, 20_000)
        val r = node.sendData(h(py.raw), "hello from the phone's rnode".toByteArray())!!
        py.wait("packet_received", { it["data"] == "hello from the phone's rnode" }, 20_000)
        assertTrue(r.result.get(20, TimeUnit.SECONDS).delivered)
        val s2 = driver.stats()
        assertTrue("counters $s2", s2.txPackets >= 2 && s2.rxPackets >= 2)
    }
}
