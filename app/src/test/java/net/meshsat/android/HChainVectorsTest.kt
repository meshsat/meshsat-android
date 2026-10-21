package net.meshsat.android

import net.meshsat.android.codec.Smaz2Bytes
import net.meshsat.android.codec.Smaz2Dict
import net.meshsat.android.codec.Smaz2Exception
import net.meshsat.android.codec.Smaz2g
import net.meshsat.android.engine.SatelliteChain
import net.meshsat.android.engine.TransformException
import net.meshsat.android.engine.TransformPipeline
import net.meshsat.android.engine.TransformSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The kits' satellite H chain against the Bridge's own vectors (MESHSAT-1623): the copy in
 * resources/hchain is the file vectors.lock names, generated from the Bridge commit it records.
 * Every vector is checked both ways, through the codec AND through the chain this app sends with.
 */
class HChainVectorsTest {

    private val dir = File(requireNotNull(javaClass.classLoader?.getResource("hchain/vectors.lock")).toURI()).parentFile
    private val lock = File(dir, "vectors.lock").readLines().filter { it.isNotBlank() }
        .associate { it.substringBefore(' ') to it.substringAfter(' ') }
    private val v = JSONObject(File(dir, lock.getValue("file")).readText())

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun rows(name: String): List<JSONObject> = v.getJSONArray(name).let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun JSONArray.strings() = List(length()) { getString(it) }

    @Test
    fun `the copy is the locked file`() {
        val bytes = File(dir, lock.getValue("file")).readBytes()
        assertEquals(lock["sha256"], sha256(bytes))
        assertEquals("meshsat-h-chain-vectors", v.getString("name"))
        assertEquals(1, v.getInt("version"))
        assertEquals("01", v.getJSONObject("spec").getString("protocol_version"))
        println("h-chain vectors: bridge ${lock["bridge_commit"]}, sha256 ${lock["sha256"]}, app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    @Test
    fun `the Meshtastic table is the Bridge's, in order, duplicates and all`() {
        val d = v.getJSONObject("dictionaries").getJSONObject("meshtastic")
        val words = d.getJSONArray("words").strings()
        assertEquals(256, words.size)
        assertEquals(words, Smaz2Bytes.MESHTASTIC_WORD_LIST)
        // Not deduplicated: the Bridge's table repeats some words, and the compressor indexes each by its last entry.
        assertTrue(words.toSet().size < words.size)
        val bigrams = d.getString("bigrams")
        assertEquals(256, bigrams.length)
        assertEquals(bigrams, Smaz2Bytes.MESHTASTIC_BIGRAM_TABLE)
    }

    @Test
    fun `smaz2 with either table matches the Bridge both ways`() {
        val vectors = rows("smaz2")
        assertTrue(vectors.size >= 30)
        var lossy = 0
        for (x in vectors) {
            val name = "${x.getString("name")} (${x.getString("dict")})"
            val dict = Smaz2Dict.of(x.getString("dict"))
            val text = hex(x.getString("text_hex"))
            assertArrayEquals("$name text_hex", x.getString("text").toByteArray(Charsets.UTF_8), text)
            val z = hex(x.getString("compressed_hex"))
            assertEquals("$name compress", x.getString("compressed_hex"), Smaz2Bytes.compress(text, dict).hex())
            // What the Bridge's own decompressor returns, byte for byte, the text wherever it round trips.
            val back = Smaz2Bytes.decompress(z, dict)
            assertEquals("$name decompress", x.getString("decompressed_hex"), back.hex())
            if (!back.contentEquals(text)) {
                lossy++
                // Only the default table (lib-x) loses, and only bytes 0x01-0x08; the kits' table never does.
                assertEquals("$name loses", Smaz2Dict.DEFAULT, dict)
                assertTrue("$name loses", text.any { it in 1..8 })
            }
        }
        assertEquals("the one known lossy vector, default control_bytes", 1, lossy)
    }

    @Test
    fun `smaz2g frames match the Bridge both ways, a tie stays raw`() {
        val vectors = rows("smaz2g")
        assertTrue(vectors.size >= 30)
        var ties = 0
        for (x in vectors) {
            val name = x.getString("name")
            val dict = Smaz2Dict.of(x.getString("dict"))
            val text = hex(x.getString("text_hex"))
            val frame = Smaz2g.encode(text, dict)
            assertEquals("$name frame", x.getString("frame_hex"), frame.hex())
            assertEquals("$name mode", if (x.getString("mode") == "raw") Smaz2g.MODE_RAW else Smaz2g.MODE_SMAZ2, frame[0])
            assertArrayEquals("$name decode", text, Smaz2g.decode(frame, dict))
            // Through the app's pipeline as well, the step the chain uses.
            val step = listOf(TransformSpec("smaz2g", mapOf("dict" to x.getString("dict"))))
            assertArrayEquals("$name pipeline", frame, TransformPipeline().applyEgress(text, step))
            assertArrayEquals("$name pipeline back", text, TransformPipeline().applyIngress(frame, step))
            if (x.getString("mode") == "raw" && text.isNotEmpty() && Smaz2Bytes.compress(text, dict).size == text.size) ties++
        }
        assertTrue("the vectors hold a size tie", ties >= 1)
        assertEquals("empty text is the one-byte raw frame", "00", Smaz2g.encode(ByteArray(0), Smaz2Dict.MESHTASTIC).hex())
    }

    @Test
    fun `malformed frames are refused without a crash`() {
        val vectors = rows("smaz2g_reject")
        assertTrue(vectors.size >= 9)
        for (x in vectors) {
            val name = x.getString("name")
            val dict = Smaz2Dict.of(x.getString("dict"))
            val bytes = hex(x.getString("bytes_hex"))
            try {
                Smaz2g.decode(bytes, dict)
                fail("$name decoded")
            } catch (e: Smaz2Exception) {
            }
            try {
                TransformPipeline().applyIngress(bytes, listOf(TransformSpec("smaz2g", mapOf("dict" to x.getString("dict")))))
                fail("$name decoded in the pipeline")
            } catch (e: TransformException) {
            }
        }
    }

    @Test
    fun `the whole H chain matches the Bridge byte for byte, both ways`() {
        val vectors = rows("envelopes")
        assertTrue(vectors.size >= 8)
        var nonceOpensWithVersion = 0
        for (x in vectors) {
            val name = x.getString("name")
            val key = x.getString("key_hex")
            val nonce = hex(x.getString("nonce_hex"))
            val dictName = x.getString("dict")
            val text = x.getString("text").toByteArray(Charsets.UTF_8)
            val wire = hex(x.getString("wire_hex"))
            if (nonce[0] == SatelliteChain.PROTOCOL_VERSION) nonceOpensWithVersion++

            // App to Bridge: the app's wire, with the vector's nonce, is the Bridge's wire. The
            // kits' chain is the Meshtastic table; a vector on another table runs its own steps.
            val pipeline = TransformPipeline().apply { fixedNonce = nonce }
            val steps = listOf(TransformSpec("smaz2g", mapOf("dict" to dictName)), TransformSpec("encrypt", mapOf("key" to key)))
            val sealed = if (dictName == "meshtastic") SatelliteChain.seal(text, key, pipeline)
            else byteArrayOf(SatelliteChain.PROTOCOL_VERSION) + pipeline.applyEgress(text, steps)
            assertEquals("$name wire", x.getString("wire_hex"), sealed.hex())
            assertEquals("$name wire_len", x.getInt("wire_len"), sealed.size)
            val z = Smaz2Bytes.compress(text, Smaz2Dict.of(dictName)).size
            assertEquals("$name 30 + min(n, z)", SatelliteChain.OVERHEAD + minOf(text.size, z), sealed.size)

            // Bridge to app: the Bridge's wire opens to the text, and on the kits' table reads as a sealed text.
            assertArrayEquals("$name open", text, TransformPipeline().applyIngress(wire.copyOfRange(1, wire.size), steps))
            if (dictName == "meshtastic") {
                assertArrayEquals("$name open", text, SatelliteChain.open(wire, key))
                assertEquals("$name readMt", SatelliteChain.Mt.Text(String(text, Charsets.UTF_8), sealed = true), SatelliteChain.readMt(wire, key))
            }

            // The version byte once: the nonce follows it, whatever its first byte.
            assertEquals(SatelliteChain.PROTOCOL_VERSION, wire[0])
            assertArrayEquals("$name nonce after the version byte", nonce, wire.copyOfRange(1, 13))
        }
        assertTrue("a vector's nonce starts with 0x01", nonceOpensWithVersion >= 1)
    }

    @Test
    fun `a wire that does not open is refused and never shown as text`() {
        val vectors = rows("envelope_reject")
        assertTrue(vectors.size >= 7)
        for (x in vectors) {
            val name = x.getString("name")
            val bytes = hex(x.getString("bytes_hex"))
            val key = x.getString("key_hex")
            try {
                SatelliteChain.open(bytes, key)
                fail("$name opened")
            } catch (e: TransformException) {
            }
            val mt = SatelliteChain.readMt(bytes, key)
            // A wire without the version byte is not MeshSat's: a plain text as before, not a decode.
            if (bytes.isNotEmpty() && bytes[0] == SatelliteChain.PROTOCOL_VERSION) {
                assertTrue("$name read as $mt", mt is SatelliteChain.Mt.Unopened)
            } else {
                assertTrue("$name read as $mt", mt is SatelliteChain.Mt.Text && !mt.sealed)
            }
        }
    }
}
