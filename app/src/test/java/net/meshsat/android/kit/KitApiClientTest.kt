package net.meshsat.android.kit

import net.meshsat.android.data.SettingsRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A kit's API on the LAN with its token (MESHSAT-1738), against a MockWebServer standing in for
 * the Bridge: the Bearer goes on every request when there is one and never when there is none,
 * a 401 is the prompt for the token, and the token reaches no log line, no toString and no error.
 */
class KitApiClientTest {

    private lateinit var server: MockWebServer
    private val token = "s3cr3t-token-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val lines = mutableListOf<String>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun base() = "http://${server.hostName}:${server.port}"

    @Test
    fun `the token goes as the Bearer on every request`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"bridge_id":"tesseract","version":"1.2.3","mode":"direct"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = KitApiClient(base(), token, log = { lines += it })
        val r = client.get("/api/status")
        assertEquals(200, r.code)
        assertFalse(r.needsToken)
        assertEquals("Bearer $token", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        client.post("/api/send", """{"text":"hi"}""")
        val post = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer $token", post.getHeader("Authorization"))
        assertEquals("POST", post.method)
        assertEquals("""{"text":"hi"}""", post.body.readUtf8())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"bridge_id":"tesseract","version":"1.2.3","mode":"direct"}"""))
        val probed = KitProbe.check(KitApiClient(base(), token), hasToken = true)
        assertEquals(KitProbe.Result.Reachable("tesseract, v1.2.3, direct"), probed)
        assertEquals("Reachable: tesseract, v1.2.3, direct", KitProbe.line(probed))
        assertEquals("/api/status", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `without a token no Authorization header is sent`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        KitApiClient(base(), "").get("/api/status")
        assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertNull(KitApiClient.authHeader(""))
        assertNull(KitApiClient.authHeader("   "))
        assertEquals("Bearer abc", KitApiClient.authHeader(" abc "))
    }

    @Test
    fun `a 401 is the prompt for the token, or a refused one`() {
        val unauthorized = MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer realm=\"meshsat\"").setBody("""{"error":"api token required"}""")
        server.enqueue(unauthorized)
        val none = KitProbe.check(KitApiClient(base(), ""), hasToken = false)
        assertEquals(KitProbe.Result.NeedsToken, none)
        assertTrue(KitProbe.asksForToken(none))
        assertEquals("This kit asks for its API token: enter it above and check again.", KitProbe.line(none))

        server.enqueue(unauthorized)
        val wrong = KitProbe.check(KitApiClient(base(), "not-the-one"), hasToken = true)
        assertEquals(KitProbe.Result.WrongToken, wrong)
        assertTrue(KitProbe.asksForToken(wrong))

        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        val down = KitProbe.check(KitApiClient(base(), token), hasToken = true)
        assertEquals(KitProbe.Result.Unreachable("HTTP 503"), down)
        assertFalse(KitProbe.asksForToken(down))
    }

    @Test
    fun `the token reaches no log line, no toString and no error message`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no"))
        val client = KitApiClient(base(), token, log = { lines += it })
        client.get("/api/status")
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("401") && lines[0].contains("token sent"))
        assertFalse(client.toString(), client.toString().contains(token))
        assertTrue(client.toString().contains("token set"))

        // A connection that fails: the exception and the log carry the host, never the header.
        server.shutdown()
        val dead = KitApiClient(base(), token, timeoutMs = 500, log = { lines += it })
        val r = KitProbe.check(dead, hasToken = true)
        assertTrue(r is KitProbe.Result.Unreachable)
        val all = lines.joinToString("\n") + "\n" + KitProbe.line(r)
        assertFalse(all, all.contains(token))
        assertEquals("a <token> b", dead.scrub("a $token b"))
    }

    @Test
    fun `addresses as people type them become the kit's base URL`() {
        assertEquals("http://192.168.1.20:6050", KitApiClient.normalizeBase("192.168.1.20"))
        assertEquals("http://192.168.1.20:6050", KitApiClient.normalizeBase(" http://192.168.1.20/ "))
        assertEquals("http://kit-a.local:6050", KitApiClient.normalizeBase("kit-a.local:6050/"))
        assertEquals("http://kit-a:7000", KitApiClient.normalizeBase("kit-a:7000"))
        assertEquals("https://kit.example.net:443", KitApiClient.normalizeBase("https://kit.example.net:443"))
        assertEquals("https://kit.example.net:6050", KitApiClient.normalizeBase("https://kit.example.net"))
        assertEquals("http://[fe80::1]:6050", KitApiClient.normalizeBase("[fe80::1]"))
        assertEquals("", KitApiClient.normalizeBase("  "))
        assertEquals("http://192.168.1.20:6050", KitConnection("k1", "tesseract", "192.168.1.20").base)
    }

    @Test
    fun `the kit list round-trips without any token in it`() {
        val a = KitConnection.create("tesseract", "192.168.1.20")
        val b = KitConnection.create("parallax", "parallax.local")
        val s = KitConnection.encodeList(listOf(a, b))
        assertFalse(s.contains("token"))
        assertEquals(listOf(a, b), KitConnection.decodeList(s))
        assertEquals(emptyList<KitConnection>(), KitConnection.decodeList(""))
        assertEquals(emptyList<KitConnection>(), KitConnection.decodeList("not json"))
        assertEquals(emptyList<KitConnection>(), KitConnection.decodeList("""[{"name":"no id"}]"""))
        assertEquals("kit_api_token:${a.id}", KitConnection.tokenKey(a.id))
        assertEquals(8, a.id.length)
        // The settings key the list lives under is a plain preference; the token key is not.
        assertEquals("kits_lan", SettingsRepository.KEY_KITS.name)
    }
}
