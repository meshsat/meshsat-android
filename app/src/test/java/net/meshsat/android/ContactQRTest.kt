package net.meshsat.android

import net.meshsat.android.pair.ContactQR
import net.meshsat.android.routing.Identity
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/**
 * The contact card two people hand over face to face (MESHSAT-566, MESHSAT-575).
 *
 * Everything here is someone else's input arriving through a camera, so the tests care as much
 * about what is refused as about what round-trips.
 */
class ContactQRTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun addProvider() {
            // appended, never at position 1: inserting BouncyCastle first hijacks Conscrypt's
            // SSLContext on Android 16 and breaks every HTTPS call (MESHSAT-497).
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        }
    }

    private val identity = Identity.generate()

    private fun card(name: String = "Kyriakos") = ContactQR.Card(
        name = name,
        signingPubRaw = identity.signingPubRaw,
        meshNodeId = "!bf6ee7bc",
        bridgeId = "msa-flaneur",
        issuedAtSec = 1_789_900_000L,
    )

    @Test
    fun `a card survives the round trip`() {
        val text = ContactQR.encode(card(), identity)
        assertTrue(text.startsWith(ContactQR.PREFIX))

        val result = ContactQR.decode(text)
        assertTrue("got $result", result is ContactQR.Result.Ok)
        assertEquals(card(), (result as ContactQR.Result.Ok).card)
    }

    @Test
    fun `a card altered in transit is refused`() {
        val text = ContactQR.encode(card(), identity)
        // Change the name in the payload: the signature no longer covers what is there.
        val body = text.removePrefix(ContactQR.PREFIX)
        val payload = java.util.Base64.getUrlDecoder().decode(body.substringBefore('.'))
        val tampered = String(payload).replaceFirst("Kyriakos", "Mallory").toByteArray()
        val forged = ContactQR.PREFIX +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tampered) +
            "." + body.substringAfter('.')

        assertTrue(ContactQR.decode(forged) is ContactQR.Result.BadSignature)
    }

    @Test
    fun `a card signed by another key is refused`() {
        // The card claims this phone's key while another identity signed it.
        val other = Identity.generate()
        val text = ContactQR.encode(card(), other)
        assertTrue(ContactQR.decode(text) is ContactQR.Result.BadSignature)
    }

    @Test
    fun `anything that is not a card is told apart from a broken one`() {
        assertTrue(ContactQR.decode("https://meshsat.net") is ContactQR.Result.NotACard)
        assertTrue(ContactQR.decode("") is ContactQR.Result.NotACard)
        assertTrue(ContactQR.decode("meshsat:contact:2:abc.def") is ContactQR.Result.NotACard)
        assertTrue(ContactQR.decode(ContactQR.PREFIX + "no-dot") is ContactQR.Result.Malformed)
        assertTrue(ContactQR.decode(ContactQR.PREFIX + "!!!.???") is ContactQR.Result.Malformed)
    }

    @Test
    fun `a key of the wrong length is refused before it reaches the verifier`() {
        val payload = "Someone\u001FAAAA\u001F\u001F\u001F0".toByteArray()
        val text = ContactQR.PREFIX +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + ".AAAA"
        assertTrue(ContactQR.decode(text) is ContactQR.Result.Malformed)
    }

    @Test
    fun `a name carrying the separator is refused rather than reshaping the record`() {
        val sneaky = card(name = "Kyriakos\u001Ffake-key")
        try {
            ContactQR.encode(sneaky, identity)
            throw AssertionError("a name with a separator should not encode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("separator"))
        }
    }

    @Test
    fun `the fingerprint is stable, readable, and different per identity`() {
        val fp = card().fingerprint
        assertEquals(fp, ContactQR.fingerprintOf(identity.signingPubRaw))
        assertEquals("four groups of four", 19, fp.length) // 16 hex characters and 3 spaces
        assertEquals(3, fp.count { it == ' ' })
        assertNotEquals(fp, ContactQR.fingerprintOf(Identity.generate().signingPubRaw))
    }
}
