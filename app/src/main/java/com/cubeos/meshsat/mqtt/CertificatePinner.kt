package com.cubeos.meshsat.mqtt

import android.util.Log
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import android.util.Base64

/**
 * TLS certificate pinner for MQTT connections.
 * Pins SHA-256 hashes of the Subject Public Key Info (SPKI) of certificates.
 * Supports primary + backup pin for zero-downtime certificate rotation.
 */
class CertificatePinner private constructor(
    private val pins: Set<String>,
) {
    companion object {
        private const val TAG = "CertPinner"

        /**
         * Compute the base64-encoded SHA-256 hash of a certificate's SPKI.
         */
        fun spkiHash(cert: X509Certificate): String {
            val spki = cert.publicKey.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(spki)
            return Base64.encodeToString(digest, Base64.NO_WRAP)
        }

        /**
         * Create an SSLSocketFactory configured for mutual TLS (mTLS).
         * Parses PEM-encoded client certificate + private key, optionally a CA cert.
         */
        fun createMtlsSSLSocketFactory(
            clientCertPem: String,
            clientKeyPem: String,
            caCertPem: String? = null,
        ): SSLSocketFactory {
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")

            // PEM parsing (including the SEC1 -> PKCS#8 wrap the Hub's EC keys need) is
            // shared with the relay tunnel's inner TLS (MESHSAT-1157), pure JVM.
            val kmf = com.cubeos.meshsat.hub.relay.RelayTls.keyManagers(clientCertPem, clientKeyPem)

            // Server verification uses the SYSTEM roots only (Let's Encrypt at NATS and at
            // the stunnel edge). The Hub bridge CA that arrives beside the client
            // certificate (ca_pem) is deliberately NOT added here: it is the root for the
            // TLS session INSIDE a relay tunnel (hub/relay/RelayTls) and for nothing
            // else. The parameter stays so callers need not change; it is validated and
            // otherwise unused on this path (MESHSAT-1157, Hub docs/relay.md).
            if (caCertPem != null && caCertPem.isNotBlank()) {
                cf.generateCertificate(caCertPem.byteInputStream()) as X509Certificate
            }
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            val trustManagers = tmf.trustManagers

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, trustManagers, null)
            return sslContext.socketFactory
        }

    }

    /**
     * Create an SSLSocketFactory that validates the server certificate against pinned hashes.
     * Standard certificate chain validation is performed first by the system default TrustManager,
     * then pin verification is applied on top.
     */
    fun createSSLSocketFactory(): Pair<SSLSocketFactory, X509TrustManager> {
        val defaultTrustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        defaultTrustManagerFactory.init(null as java.security.KeyStore?)
        val defaultTrustManager = defaultTrustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val pinningTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTrustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // First: standard certificate validation
                defaultTrustManager.checkServerTrusted(chain, authType)

                // Then: pin verification
                if (chain == null || chain.isEmpty()) {
                    throw CertificateException("certpin: empty certificate chain")
                }

                val matched = chain.any { cert ->
                    val hash = spkiHash(cert)
                    pins.contains(hash)
                }

                if (!matched) {
                    Log.w(TAG, "Certificate pin mismatch — rejecting connection")
                    throw CertificateException("certpin: no certificate in chain matches pinned hashes")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                defaultTrustManager.acceptedIssuers
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(pinningTrustManager), null)
        return Pair(sslContext.socketFactory, pinningTrustManager)
    }

    /**
     * Builder for CertificatePinner.
     */
    class Builder {
        private val pins = mutableSetOf<String>()

        /** Add a base64-encoded SHA-256 SPKI pin hash. */
        fun addPin(hash: String): Builder {
            if (hash.isNotBlank()) pins.add(hash)
            return this
        }

        fun hasPins(): Boolean = pins.isNotEmpty()

        fun build(): CertificatePinner = CertificatePinner(pins.toSet())
    }
}
