package net.meshsat.android

import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.sms.SmsReceiver
import net.meshsat.android.sms.SmsWireV2
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The app SMS wire v2 (r40 review R40-R1) against the shared vectors, the Bridge's file under
 * smswire/vectors.lock: this app sends every frame byte for byte as the Bridge does, encrypted ones
 * included (the vectors' fixed nonces), and reads every one, so "sara" and "¡" are two SMS again.
 */
class SmsWireV2Test {
    private val dir = File(requireNotNull(javaClass.classLoader?.getResource("smswire/vectors.lock")).toURI()).parentFile
    private val lock = File(dir, "vectors.lock").readLines().filter { it.isNotBlank() }
        .associate { it.substringBefore(' ') to it.substringAfter(' ') }
    private val raw = File(dir, lock.getValue("file")).readBytes()
    private val v = JSONObject(String(raw, Charsets.UTF_8))
    private val key = v.getString("key_hex")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun rows(name: String) = v.getJSONArray(name).let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun open(p: ByteArray): ByteArray? = try { AesGcmCrypto.decrypt(p, key) } catch (_: Exception) { null }

    @Test
    fun `the copy is the locked one`() {
        val sum = MessageDigest.getInstance("SHA-256").digest(raw).hex()
        assertEquals(lock.getValue("sha256"), sum)
    }

    @Test
    fun `every vector reads as typed, clear or sealed`() {
        for (c in rows("vectors")) {
            val r = SmsWireV2.read(Base64.getDecoder().decode(c.getString("sms")), ::open)
            assertTrue(c.getString("name"), r is SmsWireV2.Read.Text)
            r as SmsWireV2.Read.Text
            assertEquals(c.getString("name"), c.getJSONObject("expect").getString("text"), r.text)
            assertEquals(c.getString("name"), c.getInt("mode") == 1, r.encrypted)
            assertEquals(c.getString("name"), c.getInt("codec").toByte(), r.codec)
        }
    }

    @Test
    fun `this app sends every vector byte for byte`() {
        for (c in rows("vectors")) {
            val frame = SmsWireV2.frame(c.getString("text"), smaz2 = true)
            assertEquals(c.getString("name"), c.getString("frame_hex"), frame.hex())
            val wire = if (c.getInt("mode") == 0) {
                SmsWireV2.wire(frame, null)
            } else {
                SmsWireV2.wire(frame) { f -> AesGcmCrypto.encrypt(f, key, hex(c.getString("nonce_hex"))) }
            }
            assertEquals(c.getString("name"), c.getString("wire_hex"), wire.hex())
            assertEquals(c.getString("name"), c.getString("sms"), Base64.getEncoder().encodeToString(wire))
        }
    }

    @Test
    fun `a v2 frame this app does not read is kept, and a sealed one without its key too`() {
        for (c in rows("negative")) {
            val r = SmsWireV2.read(Base64.getDecoder().decode(c.getString("sms")), ::open)
            assertTrue(c.getString("name") + ": $r", r is SmsWireV2.Read.Kept)
        }
        for (c in rows("vectors").filter { it.getInt("mode") == 1 }) {
            val r = SmsWireV2.read(Base64.getDecoder().decode(c.getString("sms")), { null })
            assertEquals(c.getString("name"), SmsWireV2.Read.Kept(false), r)
        }
    }

    /**
     * Version 1 goes to the old rule, whose ambiguity stays: the SMAZ2 c2 a1 an old phone sent for
     * "sara" is readable "¡", taken as the text, never decompressed.
     */
    @Test
    fun `version 1 goes to the old rule`() {
        for (c in rows("legacy").filter { it.getString("name") != OVERLAP }) {
            assertEquals(c.getString("name"), SmsWireV2.Read.NotV2, SmsWireV2.read(Base64.getDecoder().decode(c.getString("sms")), ::open))
        }
        assertNull(SmsReceiver.smaz2Text(byteArrayOf(0xC2.toByte(), 0xA1.toByte())))
        assertArrayEquals(byteArrayOf(0xC2.toByte(), 0xA1.toByte()), "¡".toByteArray(Charsets.UTF_8))
    }

    /** The r40 review's counterexamples, sent this app's way. */
    @Test
    fun `sara and sata come back as typed`() {
        for (word in listOf("sara", "sata")) {
            val frame = SmsWireV2.frame(word, smaz2 = true)
            assertEquals(SmsWireV2.CODEC_SMAZ2, frame[0])
            for (seal in listOf<((ByteArray) -> ByteArray)?>(null, { f -> AesGcmCrypto.encrypt(f, key) })) {
                val r = SmsWireV2.read(SmsWireV2.wire(frame, seal), ::open)
                assertEquals(word, (r as SmsWireV2.Read.Text).text)
            }
        }
    }

    /** SmsReceiver's own steps (processIncoming once the key is resolved), not the codec alone. */
    private fun receive(sms: String, withKey: Boolean = true) = SmsReceiver.decode(
        sms, "+31612345678", Base64.getDecoder().decode(sms), if (withKey) key else "", "conv key", autoDecrypt = true, codebook = null,
    )

    /** r41 review V1/V2: what the receiver shows for every vector, and whether a key opened it. */
    @Test
    fun `every vector through the receiver, with the lock where a key opened it`() {
        for (c in rows("vectors") + rows("negative")) {
            val e = c.getJSONObject("expect")
            val want = if (e.getString("status") == "kept") c.getString("sms") else e.getString("text")
            val r = receive(c.getString("sms"))
            assertEquals(c.getString("name"), want, r.displayText)
            assertEquals(c.getString("name"), e.getBoolean("encrypted"), r.wasEncrypted)
        }
        // Sealed with a key this phone does not have: shown as it came, no lock.
        for (c in rows("vectors").filter { it.getInt("mode") == 1 }) {
            val r = receive(c.getString("sms"), withKey = false)
            assertEquals(c.getString("name"), c.getString("sms"), r.displayText)
            assertEquals(c.getString("name"), false, r.wasEncrypted)
        }
        // Version 1 a key opens reads by the old rule, with the lock.
        for (c in rows("legacy").filter { it.getJSONObject("expect").getBoolean("encrypted") }) {
            val r = receive(c.getString("sms"))
            assertEquals(c.getString("name"), c.getJSONObject("expect").getString("text"), r.displayText)
            assertEquals(c.getString("name"), true, r.wasEncrypted)
        }
    }

    /**
     * legacy_encrypted_clear_header_overlap (r41 review V2): an unversioned SMS the key opens whose
     * bytes also parse as a clear v2 frame. The key's reading wins, with the lock; without the key
     * the clear frame is all there is.
     */
    @Test
    fun `legacy_encrypted_clear_header_overlap reads as the key opens it`() {
        val c = rows("legacy").single { it.getString("name") == OVERLAP }
        val sms = c.getString("sms")
        val asFrame = SmsWireV2.read(Base64.getDecoder().decode(sms), { null })
        assertTrue("the vector parses as a clear v2 frame: $asFrame", asFrame is SmsWireV2.Read.Text && !asFrame.encrypted)
        val r = receive(sms)
        assertEquals(c.getJSONObject("expect").getString("text"), r.displayText)
        assertEquals(true, r.wasEncrypted)
    }

    private companion object {
        const val OVERLAP = "legacy_encrypted_clear_header_overlap"
    }
}
