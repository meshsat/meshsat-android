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
            val clientCert = cf.generateCertificate(clientCertPem.byteInputStream()) as X509Certificate

            val keyPem = clientKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.decode(keyPem, Base64.DEFAULT)
            val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
            val privateKey = try {
                java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            } catch (_: Exception) {
                java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec)
            }

            val clientKs = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            clientKs.load(null, null)
            clientKs.setKeyEntry("client", privateKey, charArrayOf(), arrayOf(clientCert))

            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(clientKs, charArrayOf())

            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            if (caCertPem != null) {
                val caKs = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
                caKs.load(null, null)
                val caCert = cf.generateCertificate(caCertPem.byteInputStream()) as X509Certificate
                caKs.setCertificateEntry("ca", caCert)
                tmf.init(caKs)
            } else {
                tmf.init(null as java.security.KeyStore?)
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
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
