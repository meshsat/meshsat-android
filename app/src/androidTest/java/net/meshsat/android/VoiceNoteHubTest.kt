package net.meshsat.android

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.meshsat.android.audio.OggOpus
import net.meshsat.android.audio.OpusVoice
import net.meshsat.android.lxmf.AttachmentPolicy
import net.meshsat.android.lxmf.LxAttachment
import net.meshsat.android.lxmf.LxAttachments
import net.meshsat.android.lxmf.LxMessage
import net.meshsat.android.lxmf.LxMethod
import net.meshsat.android.lxmf.LxMid
import net.meshsat.android.lxmf.LxState
import net.meshsat.android.lxmf.LxmfRouter
import net.meshsat.android.reticulum.RnsReceiveCallback
import net.meshsat.android.reticulum.RnsTcpInterface
import net.meshsat.android.rns.Node
import net.meshsat.android.rns.NodeConfig
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.Transmitter
import net.meshsat.android.rns.requestPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A voice note end to end on the emulator (MESHSAT-1735): a tone becomes an Opus note through
 * Android's own codec and the Ogg container, Android's extractor and decoder take it back; the
 * note travels as an LXMF message with FIELD_AUDIO between two real endpoints on the device,
 * direct on a link as a resource, byte for byte; and the same message goes to the Hub's address
 * over its public Reticulum port when that port offers a path to it (the address and the port
 * come from the instrumentation arguments `hub_dest`, `hub_host`, `hub_port`; the Hub side, the
 * stored field, is checked by the Hub session through its API with the hash this test prints).
 * A workspace on a Private network answers no path request from a client without its access
 * code, which this app does not speak: the Hub leg is then skipped, with what the port offered
 * recorded as instrumentation status, never passed by pretending.
 */
@RunWith(AndroidJUnit4::class)
class VoiceNoteHubTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args: Bundle = InstrumentationRegistry.getArguments()

    companion object {
        private const val TAG = "VoiceNoteHubTest"
        const val DEFAULT_HUB_HOST = "rns.meshsat.net"
        const val DEFAULT_HUB_PORT = 4242
        /** The platform workspace's lxmf.delivery address ("MeshSat"), the kits' tenant. */
        const val DEFAULT_HUB_ADDRESS = "a37933806ad447c68789f29d02186e60"
    }

    private fun hex(h: String) = ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(100) }
        throw AssertionError("timeout waiting for $what")
    }

    private fun status(vararg kv: Pair<String, String>) =
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { for ((k, v) in kv) putString(k, v) })

    /** Two seconds of 440 Hz as an Ogg Opus note, written to the app's files for a look from outside. */
    private fun note(): ByteArray {
        assertTrue("an Opus encoder (API 29+)", OpusVoice.encoderAvailable())
        val pcm = OpusVoice.tone(440.0, 2000)
        val ogg = OpusVoice.encode(pcm)
        File(context.filesDir, "voice-note-test.ogg").writeBytes(ogg)
        Log.i(TAG, "note: ${ogg.size} bytes for ${pcm.size / 2 / OpusVoice.SAMPLE_RATE} s")
        return ogg
    }

    @Test
    fun opusNoteFromAToneRoundTripsThroughAndroidsCodec() {
        val ogg = note()
        assertTrue("fits one message: ${ogg.size}", ogg.size in 1..LxAttachments.MAX_BYTES)
        assertTrue(OggOpus.looksLikeOggOpus(ogg))
        val s = OggOpus.read(ogg)
        assertEquals(1, s.head.channels)
        assertEquals(OpusVoice.SAMPLE_RATE, s.head.inputSampleRate)
        assertTrue("about 2 s by the packets: ${s.durationMs}", s.durationMs in 1850..2150)
        // Android's own Ogg parser, the one MediaPlayer uses, reads the container as Opus of that length.
        val ex = MediaExtractor()
        try {
            ex.setDataSource(File(context.filesDir, "voice-note-test.ogg").absolutePath)
            assertEquals(1, ex.trackCount)
            val f = ex.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, f.getString(MediaFormat.KEY_MIME))
            if (f.containsKey(MediaFormat.KEY_DURATION)) {
                val ms = f.getLong(MediaFormat.KEY_DURATION) / 1000
                assertTrue("extractor duration $ms", ms in 1800..2200)
            }
        } finally { ex.release() }
        // Decoded back: about 2 s of signal, not silence.
        val pcm = OpusVoice.decode(ogg)
        assertEquals(1, pcm.channels)
        assertTrue("decoded ${pcm.durationMs} ms at ${pcm.sampleRate}", pcm.durationMs in 1800..2200)
        val rms = OpusVoice.rms(pcm.data)
        assertTrue("rms $rms", rms > 0.15)
    }

    /** Two endpoints on this device wired directly: the note leaves as a resource and arrives byte for byte. */
    @Test
    fun aVoiceNoteTravelsAsAResourceBetweenTwoEndpoints() {
        val ogg = note()
        val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        lateinit var a: Node; lateinit var b: Node
        val wireA = object : Transmitter {
            override fun transmit(ifaceId: String, raw: ByteArray): Boolean { val cp = raw.copyOf(); pool.execute { b.inbound(cp, "ba") }; return true }
            override fun floodable() = listOf("ab")
            override fun hwMtu(ifaceId: String) = 500
        }
        val wireB = object : Transmitter {
            override fun transmit(ifaceId: String, raw: ByteArray): Boolean { val cp = raw.copyOf(); pool.execute { a.inbound(cp, "ab") }; return true }
            override fun floodable() = listOf("ba")
            override fun hwMtu(ifaceId: String) = 500
        }
        a = Node(NodeConfig(RnsIdentity.generate(), log = { Log.d(TAG, "[a] $it") }), wireA)
        b = Node(NodeConfig(RnsIdentity.generate(), log = { Log.d(TAG, "[b] $it") }), wireB)
        val gotB = LinkedBlockingQueue<LxMessage>()
        val ra = LxmfRouter(a, LxmfRouter.Config(a.identity, "Phone A", log = { Log.i(TAG, "[a lxmf] $it") }))
        val rb = LxmfRouter(b, LxmfRouter.Config(b.identity, "Phone B", onMessage = { gotB.add(it) }, log = { Log.i(TAG, "[b lxmf] $it") }))
        try {
            a.start(); b.start(); ra.start(); rb.start()
            assertTrue(rb.announce()); assertTrue(ra.announce())
            waitFor("A knows B", 10_000) { a.paths.has(rb.hash) }
            val fields = LxAttachments.withAttachments(LxMid.fields(LxMid.newMid()), listOf(LxAttachment.audio(LxAttachments.AUDIO_OPUS_OGG, ogg)))
            val o = ra.send(rb.hash, "a note between two phones".toByteArray(), fields = fields)
            assertEquals("auto picks direct for a note", LxMethod.DIRECT, o.method)
            assertEquals(LxmfRouter.Representation.RESOURCE, o.representation)
            assertEquals(LxState.DELIVERED, o.result.get(60, TimeUnit.SECONDS))
            val m = gotB.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("B got nothing")
            assertEquals(o.hashHex, m.hashHex)
            assertTrue(m.signatureValid)
            val atts = LxAttachments.attachmentsOf(m.fields)
            assertEquals(1, atts.size)
            assertEquals(LxAttachments.AUDIO_OPUS_OGG, atts[0].mode)
            assertArrayEquals(ogg, atts[0].data)
            assertEquals(OggOpus.read(ogg).durationMs, OggOpus.read(atts[0].data).durationMs)
            status("local_lxmf_hash" to o.hashHex, "local_packed_bytes" to o.message.packedSize.toString())
            Log.i(TAG, "note of ${ogg.size} B arrived at B in message ${o.hashHex} (${o.message.packedSize} B packed)")
        } finally {
            ra.stop(); rb.stop(); a.stop(); b.stop(); pool.shutdownNow()
        }
    }

    @Test
    fun aVoiceNoteReachesTheHubOverTcp() {
        val host = args.getString("hub_host") ?: DEFAULT_HUB_HOST
        val port = args.getString("hub_port")?.toIntOrNull() ?: DEFAULT_HUB_PORT
        val address = (args.getString("hub_dest") ?: DEFAULT_HUB_ADDRESS).lowercase()
        assertEquals("hub_dest is 32 hex", 32, address.length)
        val ogg = note()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val tcp = RnsTcpInterface(scope, "tcp_rns_0")
        lateinit var node: Node
        val tx = object : Transmitter {
            override fun transmit(ifaceId: String, raw: ByteArray): Boolean = runBlocking { tcp.send(raw) } == null
            override fun floodable(): List<String> = if (tcp.isOnline) listOf("tcp_rns_0") else emptyList()
            override fun hwMtu(ifaceId: String): Int = tcp.mtu
        }
        node = Node(NodeConfig(RnsIdentity.generate(), log = { Log.d(TAG, it) }), tx)
        tcp.setReceiveCallback(RnsReceiveCallback { id, raw -> try { node.inbound(raw, id) } catch (e: Exception) { Log.w(TAG, "inbound: $e") } })
        val router = LxmfRouter(node, LxmfRouter.Config(node.identity, "MeshSat Android test", log = { Log.i(TAG, it) }))
        try {
            node.start(); router.start()
            tcp.connect(host, port)
            waitFor("TCP to $host:$port", 30_000) { tcp.isOnline }
            router.announce()
            val dest = hex(address)
            node.requestPath(dest, 30_000)
            val found = try {
                waitFor("a path to $address", 90_000) {
                    if (!node.paths.has(dest) && System.currentTimeMillis() % 30_000 < 150) node.requestPath(dest, 30_000)
                    node.paths.has(dest)
                }
                true
            } catch (_: AssertionError) { false }
            // What the port did offer, for the record: every announce heard and the link state.
            val heard = node.paths.all().joinToString("; ") { "${it.destHash.hex()} hops=${it.hops} iface=${it.iface}" }
            status("hub_dest" to address, "hub_path" to found.toString(), "tcp_state" to tcp.state.value.name, "paths_heard" to heard)
            Log.i(TAG, "path to $address: $found; tcp=${tcp.state.value}; paths known: ${node.paths.all().size} [$heard]")
            assumeTrue(
                "$host:$port offers no path to $address in 90 s (a workspace on a Private network answers only with its access code, which this app does not speak); heard: [$heard]",
                found,
            )
            Log.i(TAG, "path to the Hub: ${node.paths.hops(dest)} hops, name ${router.peerInfo(dest)?.displayName}")

            val text = "Voice note test from MeshSat Android, ${java.util.Date()}"
            val fields = LxAttachments.withAttachments(LxMid.fields(LxMid.newMid()), listOf(LxAttachment.audio(LxAttachments.AUDIO_OPUS_OGG, ogg)))
            val o = router.send(dest, text.toByteArray(), fields = fields, method = LxMethod.DIRECT)
            assertEquals(LxmfRouter.Representation.RESOURCE, o.representation)
            Log.i(TAG, "sent lxmf hash=${o.hashHex} bytes=${o.message.packedSize} audio=${ogg.size}")
            val state = o.result.get(180, TimeUnit.SECONDS)
            status("lxmf_hash" to o.hashHex, "lxmf_state" to state.name, "audio_bytes" to ogg.size.toString(), "packed_bytes" to o.message.packedSize.toString())
            Log.i(TAG, "lxmf ${o.hashHex} to the Hub: ${state.name} (${o.lastError})")
            assertEquals("delivered to the Hub: ${o.lastError}", LxState.DELIVERED, state)
            assertTrue(AttachmentPolicy.carries("lxmf"))
            assertEquals(LxMessage.DELIVERY_NAME, "lxmf.delivery")
        } finally {
            router.stop(); node.stop()
            runBlocking { tcp.stop() }
            scope.cancel()
        }
    }
}
