package net.meshsat.android.hub.relay

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * HTTP/1.1 over TLS to a bridge's own API through a relay tunnel's local port
 * (MESHSAT-1157; Hub docs/relay.md, "Inside the tunnel").
 *
 * The connection goes to `https://127.0.0.1:<port><path>`. The TLS session inside it
 * presents the phone's Hub-issued certificate, trusts only the Hub bridge CA, sends
 * the target bridge id as SNI, and accepts the server certificate only when its DNS
 * SAN is exactly that bridge id ([RelayTls.exactBridgeVerifier]). The platform's own
 * hostname check would compare against 127.0.0.1 and is bypassed on purpose: the
 * identity being verified is the bridge at the far end, not the loopback address.
 *
 * Pure JVM, so the live test can drive it against hub.meshsat.net from a workstation.
 */
class RelayHttp(
    val localPort: Int,
    val bridgeId: String,
    clientCertPem: String,
    clientKeyPem: String,
    caCertPem: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /** A finished response: status, headers and the whole body as bytes. */
    data class Response(val code: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    private val socketFactory: SSLSocketFactory = RelayTls.withServerName(
        RelayTls.clientContext(clientCertPem, clientKeyPem, caCertPem).socketFactory,
        bridgeId,
    )
    private val verifier = RelayTls.exactBridgeVerifier(bridgeId)

    /** Build an unsent connection for [path] (must start with `/`). */
    fun open(path: String, method: String = "GET"): HttpsURLConnection {
        require(path.startsWith("/")) { "path must start with /" }
        val conn = URL("https://${RelayTunnel.LOOPBACK.hostAddress}:$localPort$path").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = socketFactory
        conn.hostnameVerifier = verifier
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "meshsat-android-relay")
        return conn
    }

    /** Perform a request and read the whole body, whatever the status. */
    @Throws(IOException::class)
    fun request(
        path: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val conn = open(path, method)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code >= HttpURLConnection.HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        val hdrs = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
        conn.disconnect()
        return Response(code, hdrs, bytes)
    }

    fun get(path: String, headers: Map<String, String> = emptyMap()): Response = request(path, "GET", null, headers)

    companion object {
        /**
         * Fetch the Hub's bridge CA from `GET <hub>/api/relay/ca` (public PEM, no
         * credentials, system trust roots) for a phone whose settings hold no `ca_pem`.
         * The CA a phone got with its certificate is the same document and is
         * preferred; this is the recovery path, not the normal one.
         */
        @Throws(IOException::class)
        fun fetchHubCa(hubApiBase: String, timeoutMs: Int = 15_000): String {
            val url = URL(RelayTunnel.normalizeBase(hubApiBase) + "/api/relay/ca")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "meshsat-android-relay")
            try {
                val code = conn.responseCode
                if (code != 200) throw IOException("GET /api/relay/ca answered HTTP $code")
                val pem = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                if (!pem.contains("BEGIN CERTIFICATE")) throw IOException("GET /api/relay/ca returned no PEM certificate")
                RelayTls.parseCertificate(pem) // refuse a body that is not a certificate
                return pem
            } finally {
                conn.disconnect()
            }
        }
    }
}
