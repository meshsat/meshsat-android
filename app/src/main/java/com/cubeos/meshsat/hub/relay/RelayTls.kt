package com.cubeos.meshsat.hub.relay

import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * TLS material for the inside of a Hub relay tunnel (MESHSAT-1157, contract in the
 * Hub's docs/relay.md, "Inside the tunnel").
 *
 * Pure JVM on purpose: no android.* import, so the same code runs in unit tests and in
 * the live test against hub.meshsat.net. PEM parsing uses java.util.Base64, and the
 * SEC1 ("EC PRIVATE KEY") wrapping the Hub's IssueBridgeCert produces is handled here
 * so the MQTT path ([com.cubeos.meshsat.mqtt.CertificatePinner]) can share it.
 *
 * The trust store built by [clientContext] holds the Hub bridge CA and NOTHING else:
 * the bridge end of a tunnel presents a Hub-issued certificate, and a system root
 * must never be able to stand in for it.
 */
object RelayTls {

    /** Parse every CERTIFICATE block in [pem]. */
    fun parseCertificates(pem: String): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificates(pem.byteInputStream()).map { it as X509Certificate }
    }

    /** Parse the first CERTIFICATE block in [pem]. */
    fun parseCertificate(pem: String): X509Certificate =
        parseCertificates(pem).firstOrNull() ?: throw IllegalArgumentException("no certificate in PEM")

    /**
     * Parse a PEM private key. Accepts PKCS#8 ("PRIVATE KEY"), SEC1 EC ("EC PRIVATE KEY",
     * what the Hub issues) and PKCS#1 RSA ("RSA PRIVATE KEY" is not wrapped and only
     * works where the platform KeyFactory accepts it).
     */
    fun parsePrivateKey(pem: String): PrivateKey {
        val isSec1 = pem.contains("BEGIN EC PRIVATE KEY")
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val raw = Base64.getMimeDecoder().decode(body)
        val der = if (isSec1) wrapSec1InPkcs8(raw) else raw
        val spec = PKCS8EncodedKeySpec(der)
        return try {
            KeyFactory.getInstance("EC").generatePrivate(spec)
        } catch (_: Exception) {
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        }
    }

    /**
     * Wrap a SEC1 EC private key (P-256) in a PKCS#8 envelope. The platform KeyFactory
     * only reads PKCS#8; the Hub emits SEC1 (Go's x509.MarshalECPrivateKey).
     */
    fun wrapSec1InPkcs8(sec1Der: ByteArray): ByteArray {
        // AlgorithmIdentifier { id-ecPublicKey, prime256v1 }
        val algId = byteArrayOf(
            0x30, 0x13,
            0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07,
        )
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val octetString = derLength(0x04, sec1Der.size) + sec1Der
        val inner = version + algId + octetString
        return derLength(0x30, inner.size) + inner
    }

    private fun derLength(tag: Int, len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(tag.toByte(), len.toByte())
        len < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), len.toByte())
        else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (len shr 8).toByte(), (len and 0xff).toByte())
    }

    /**
     * A KeyManagerFactory holding one client identity: [clientCertPem] (leaf first,
     * intermediates after) and [clientKeyPem].
     */
    fun keyManagers(clientCertPem: String, clientKeyPem: String): KeyManagerFactory {
        val chain = parseCertificates(clientCertPem)
        require(chain.isNotEmpty()) { "client certificate PEM holds no certificate" }
        val key = parsePrivateKey(clientKeyPem)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("client", key, charArrayOf(), chain.toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        return kmf
    }

    /**
     * An SSLContext that presents the Hub-issued client certificate and trusts ONLY the
     * Hub bridge CA in [caCertPem]. Used for the TLS session inside a relay tunnel.
     */
    fun clientContext(clientCertPem: String, clientKeyPem: String, caCertPem: String): SSLContext {
        val kmf = keyManagers(clientCertPem, clientKeyPem)
        val roots = parseCertificates(caCertPem)
        require(roots.isNotEmpty()) { "Hub CA PEM holds no certificate" }
        val ts = KeyStore.getInstance(KeyStore.getDefaultType())
        ts.load(null, null)
        roots.forEachIndexed { i, c -> ts.setCertificateEntry("hub-ca-$i", c) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ts)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }

    /**
     * The DNS names a certificate is valid for: its subjectAltName dNSName entries
     * (type 2). The Hub puts exactly one there, equal to the bridge id.
     */
    fun dnsNames(cert: X509Certificate): List<String> =
        (cert.subjectAlternativeNames ?: emptyList()).mapNotNull { entry ->
            val type = entry.getOrNull(0) as? Int
            val value = entry.getOrNull(1) as? String
            if (type == 2 && value != null) value else null
        }

    /**
     * A HostnameVerifier that accepts exactly [bridgeId] as a DNS SAN of the peer's
     * leaf certificate, case-insensitively, no wildcards, and nothing else. The URL
     * the request is made to names 127.0.0.1, which is why the platform's own
     * verifier cannot be used: the identity to check is the bridge, not the address.
     */
    fun exactBridgeVerifier(bridgeId: String): HostnameVerifier = HostnameVerifier { _, session ->
        peerMatches(bridgeId, session)
    }

    internal fun peerMatches(bridgeId: String, session: SSLSession): Boolean {
        if (bridgeId.isBlank()) return false
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: Exception) {
            null
        } ?: return false
        return dnsNames(leaf).any { it.equals(bridgeId, ignoreCase = true) }
    }

    /**
     * Wrap [base] so every socket it creates sends [serverName] as SNI. The tunnel
     * socket is opened to 127.0.0.1, so the platform would otherwise send none, and
     * the contract says the client verifies "under ServerName = the bridge id".
     * A bridge id that is not a valid SNI hostname simply sends none.
     */
    fun withServerName(base: SSLSocketFactory, serverName: String): SSLSocketFactory =
        object : SSLSocketFactory() {
            private fun sni(s: Socket): Socket {
                if (s is SSLSocket && serverName.isNotBlank()) {
                    try {
                        val p = s.sslParameters
                        p.serverNames = listOf(SNIHostName(serverName))
                        s.sslParameters = p
                    } catch (_: IllegalArgumentException) {
                        // not a DNS-shaped id: no SNI, the verifier still checks the SAN
                    }
                }
                return s
            }

            override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
            override fun createSocket(): Socket = sni(base.createSocket())
            override fun createSocket(host: String?, port: Int): Socket = sni(base.createSocket(host, port))
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                sni(base.createSocket(host, port, localHost, localPort))
            override fun createSocket(host: InetAddress?, port: Int): Socket = sni(base.createSocket(host, port))
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                sni(base.createSocket(address, port, localAddress, localPort))
            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                sni(base.createSocket(s, host, port, autoClose))
        }
}
