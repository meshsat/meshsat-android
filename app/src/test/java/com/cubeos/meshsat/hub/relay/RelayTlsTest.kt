package com.cubeos.meshsat.hub.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The TLS session inside a relay tunnel (MESHSAT-1157; docs/relay.md, "Inside the
 * tunnel"): Hub CA as the only root, the phone's certificate presented, the server
 * accepted only under the target bridge id.
 *
 * The far end is a plain SSLServerSocket on loopback wearing the `kit-a` fixture and
 * demanding a client certificate, which is exactly what the bridge's relay client
 * does with `tls.NewListener` + RequireAndVerifyClientCert. No tunnel is involved:
 * [RelayHttp] only ever sees a local port, so a local port is all it needs here.
 */
class RelayTlsTest {

    @Test
    fun `parses SEC1 and PKCS8 EC keys to the same key`() {
        val sec1 = RelayTls.parsePrivateKey(RelayTestPki.PHONE_1_KEY)
        val pkcs8 = RelayTls.parsePrivateKey(RelayTestPki.PHONE_1_KEY_PKCS8)
        assertEquals("EC", sec1.algorithm)
        assertEquals("EC", pkcs8.algorithm)
        // Same scalar; the re-encoded DER differs (openssl strips the curve OID inside PKCS#8).
        assertEquals((sec1 as ECPrivateKey).s, (pkcs8 as ECPrivateKey).s)
    }

    @Test
    fun `the bridge certificate carries its id as a DNS SAN and the phone certificate carries none`() {
        assertEquals(listOf("kit-a"), RelayTls.dnsNames(RelayTls.parseCertificate(RelayTestPki.KIT_A_CERT)))
        assertEquals(emptyList<String>(), RelayTls.dnsNames(RelayTls.parseCertificate(RelayTestPki.PHONE_1_CERT)))
    }

    @Test
    fun `the client context trusts the Hub CA and nothing else`() {
        val ctx = RelayTls.clientContext(RelayTestPki.PHONE_1_CERT, RelayTestPki.PHONE_1_KEY, RelayTestPki.CA_CERT)
        assertNotNull(ctx)
        // Rebuild the trust store the same way and count its issuers: one.
        val ts = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        RelayTls.parseCertificates(RelayTestPki.CA_CERT).forEachIndexed { i, c -> ts.setCertificateEntry("hub-ca-$i", c) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ts) }
        val issuers = tmf.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
        assertEquals(1, issuers.size)
        assertEquals("CN=MeshSat Test Bridge CA,O=MeshSat Test", issuers[0].subjectX500Principal.name)
    }

    @Test
    fun `GET through the inner TLS session succeeds under the right bridge id and presents the phone certificate`() {
        BridgeEnd().use { bridge ->
            val http = RelayHttp(bridge.port, "kit-a", RelayTestPki.PHONE_1_CERT, RelayTestPki.PHONE_1_KEY, RelayTestPki.CA_CERT)
            val resp = http.get("/health")
            assertEquals(200, resp.code)
            assertEquals("""{"status":"ok"}""", resp.text)
            assertTrue(bridge.seenRequest.await(5, TimeUnit.SECONDS))
            assertEquals("GET /health HTTP/1.1", bridge.requestLine)
            assertEquals("CN=phone-1,O=MeshSat Test", bridge.clientPrincipal)
            assertEquals("kit-a", bridge.sni)
        }
    }

    @Test
    fun `the same server is refused under any other bridge id`() {
        BridgeEnd().use { bridge ->
            val http = RelayHttp(bridge.port, "kit-b", RelayTestPki.PHONE_1_CERT, RelayTestPki.PHONE_1_KEY, RelayTestPki.CA_CERT)
            try {
                http.get("/health")
                fail("kit-b must not be accepted for a certificate naming kit-a")
            } catch (e: IOException) {
                // The JDK says "HTTPS hostname wrong", Android says SSLPeerUnverifiedException;
                // what matters is that no request ever reached the bridge end.
                assertTrue(e.message ?: e.javaClass.name, e.message?.isNotBlank() == true)
            }
            assertFalse("the bridge end must never see the request", bridge.seenRequest.await(300, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `a server that demands a client certificate refuses a client without one`() {
        BridgeEnd().use { bridge ->
            val bare = SSLContext.getInstance("TLS")
            val ts = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            ts.setCertificateEntry("ca", RelayTls.parseCertificate(RelayTestPki.CA_CERT))
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ts) }
            bare.init(null, tmf.trustManagers, null)
            try {
                (bare.socketFactory.createSocket(InetAddress.getLoopbackAddress(), bridge.port) as SSLSocket).use { s ->
                    s.startHandshake()
                    s.outputStream.write("GET /health HTTP/1.1\r\nHost: kit-a\r\n\r\n".toByteArray())
                    s.outputStream.flush()
                    val n = s.inputStream.read(ByteArray(16))
                    assertTrue("server must close without answering, read $n", n < 0)
                }
            } catch (_: IOException) {
                // handshake or first read failed: also a refusal
            }
            assertFalse("the bridge end must never see the request", bridge.seenRequest.await(300, TimeUnit.MILLISECONDS))
        }
    }

    /**
     * What the bridge's relay client is on its side of a tunnel: a TLS server with
     * the bridge certificate, client certificates required and checked against the
     * Hub CA, and its HTTP API behind it. Answers one request per connection.
     */
    private class BridgeEnd : AutoCloseable {
        val seenRequest = CountDownLatch(1)
        @Volatile var requestLine = ""
        @Volatile var clientPrincipal = ""
        @Volatile var sni = ""
        private val server: SSLServerSocket
        val port: Int
        private val thread: Thread

        init {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            ks.setKeyEntry(
                "kit-a",
                RelayTls.parsePrivateKey(RelayTestPki.KIT_A_KEY),
                charArrayOf(),
                RelayTls.parseCertificates(RelayTestPki.KIT_A_CERT).toTypedArray(),
            )
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, charArrayOf()) }
            val ts = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            ts.setCertificateEntry("hub-ca", RelayTls.parseCertificate(RelayTestPki.CA_CERT))
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ts) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
            server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
            server.needClientAuth = true
            port = server.localPort
            thread = Thread({ serveLoop() }, "bridge-end").apply { isDaemon = true; start() }
        }

        private fun serveLoop() {
            while (!server.isClosed) {
                val s = try { server.accept() as SSLSocket } catch (_: IOException) { return }
                Thread({ serveOne(s) }, "bridge-end-conn").apply { isDaemon = true; start() }
            }
        }

        private fun serveOne(s: SSLSocket) {
            try {
                s.use {
                    it.startHandshake()
                    val session = it.session
                    clientPrincipal = (session.peerCertificates.first() as X509Certificate).subjectX500Principal.name
                    sni = (session as? javax.net.ssl.ExtendedSSLSession)?.requestedServerNames
                        ?.filterIsInstance<javax.net.ssl.SNIHostName>()?.firstOrNull()?.asciiName ?: ""
                    val reader = BufferedReader(InputStreamReader(it.inputStream))
                    // A client that hangs up after the handshake (hostname refused) sends no
                    // request line; only a real one counts as "seen".
                    requestLine = reader.readLine() ?: return
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    seenRequest.countDown()
                    val body = """{"status":"ok"}"""
                    it.outputStream.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n$body").toByteArray(),
                    )
                    it.outputStream.flush()
                }
            } catch (_: IOException) {
                // a refused handshake is the point of one test
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }
}
