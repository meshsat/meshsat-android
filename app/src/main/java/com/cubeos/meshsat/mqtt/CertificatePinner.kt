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

            val isSec1 = clientKeyPem.contains("BEGIN EC PRIVATE KEY")
            val keyPem = clientKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val rawKeyBytes = Base64.decode(keyPem, Base64.DEFAULT)

            // SEC1 (BEGIN EC PRIVATE KEY) must be wrapped in PKCS#8 for Android KeyFactory
            val keyBytes = if (isSec1) wrapSec1InPkcs8(rawKeyBytes) else rawKeyBytes

            val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
            val privateKey = try {
                java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec)
            } catch (_: Exception) {
                java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            }

            val clientKs = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            clientKs.load(null, null)
            clientKs.setKeyEntry("client", privateKey, charArrayOf(), arrayOf(clientCert))

            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(clientKs, charArrayOf())

            // Always trust system CAs (Let's Encrypt, etc.) for server cert verification.
            // If a custom CA is provided, add it to the trust store alongside system CAs.
            val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            ks.load(null, null)

            // Load system CAs into our keystore
            val systemTmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            systemTmf.init(null as java.security.KeyStore?)
            for (tm in systemTmf.trustManagers) {
                if (tm is javax.net.ssl.X509TrustManager) {
                    for ((i, cert) in tm.acceptedIssuers.withIndex()) {
                        ks.setCertificateEntry("system-$i", cert)
                    }
                }
            }

            // Add custom CA if provided (e.g., Hub Bridge CA)
            if (caCertPem != null) {
                val caCert = cf.generateCertificate(caCertPem.byteInputStream()) as X509Certificate
                ks.setCertificateEntry("custom-ca", caCert)
            }

            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(ks)
            val trustManagers = tmf.trustManagers

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, trustManagers, null)
            return sslContext.socketFactory
        }

        /**
         * Wrap a SEC1 EC private key in PKCS#8 envelope.
         * SEC1 = "BEGIN EC PRIVATE KEY", PKCS#8 = "BEGIN PRIVATE KEY"
         * Android's KeyFactory requires PKCS#8.
         */
        private fun wrapSec1InPkcs8(sec1Der: ByteArray): ByteArray {
            // PKCS#8 header for EC P-256 (secp256r1)
            val algId = byteArrayOf(
                0x30, 0x13,
                0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
                0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07,
            )
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val octetString = if (sec1Der.size < 128) {
                byteArrayOf(0x04, sec1Der.size.toByte()) + sec1Der
            } else {
                byteArrayOf(0x04, 0x81.toByte(), sec1Der.size.toByte()) + sec1Der
            }
            val innerLen = version.size + algId.size + octetString.size
            val outer = if (innerLen < 128) {
                byteArrayOf(0x30, innerLen.toByte())
            } else {
                byteArrayOf(0x30, 0x81.toByte(), innerLen.toByte())
            }
            return outer + version + algId + octetString
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
