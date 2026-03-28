package com.cubeos.meshsat.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ProviderCredential
import com.cubeos.meshsat.data.SettingsRepository
import org.json.JSONObject

/**
 * Imports Hub provisioning bundles from meshsat://provision/ URLs.
 *
 * The Hub generates these via POST /api/bridges/{id}/provision/qr.
 * Contains MQTT credentials, mTLS cert+key, CA cert, and Reticulum TCP peer.
 *
 * Security: each POST generates fresh credentials (new password, new cert+key).
 * Previous credentials are overwritten — old QR codes auto-invalidate.
 */
object ProvisionImporter {
    private const val TAG = "ProvisionImporter"
    private const val URL_PREFIX = "meshsat://provision/"

    /**
     * Parsed provisioning bundle.
     */
    data class ProvisionBundle(
        val version: String,
        val bridgeId: String,
        val mqttUrl: String,
        val username: String,
        val password: String,
        val clientCertPem: String,
        val clientKeyPem: String,
        val caCertPem: String,
        val certExpiry: String,
        val reticulumTcp: String,
    )

    /**
     * Parse a meshsat://provision/ URL into a ProvisionBundle.
     * @throws IllegalArgumentException if URL format is invalid
     */
    fun parse(url: String): ProvisionBundle {
        require(url.startsWith(URL_PREFIX)) { "Not a meshsat provision URL" }

        val encoded = url.substring(URL_PREFIX.length)
        val jsonBytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8))

        val version = json.optString("v", "")
        require(version == "1") { "Unsupported provision version: $version" }

        val bid = json.optString("bid", "")
        require(bid.isNotBlank()) { "Missing bridge ID (bid)" }

        val mqtt = json.optString("mqtt", "")
        require(mqtt.startsWith("wss://") || mqtt.startsWith("ssl://") || mqtt.startsWith("tcp://")) {
            "Invalid MQTT URL: $mqtt"
        }

        val cert = json.optString("cert", "")
        val key = json.optString("key", "")
        require(cert.contains("BEGIN CERTIFICATE")) { "Missing client certificate" }
        require(key.contains("BEGIN") && key.contains("KEY")) { "Missing client private key" }

        return ProvisionBundle(
            version = version,
            bridgeId = bid,
            mqttUrl = mqtt,
            username = json.optString("user", bid),
            password = json.optString("pass", ""),
            clientCertPem = cert,
            clientKeyPem = key,
            caCertPem = json.optString("ca", ""),
            certExpiry = json.optString("cert_exp", ""),
            reticulumTcp = json.optString("ret_tcp", ""),
        )
    }

    /**
     * Apply a provisioning bundle — writes all settings and stores credentials.
     *
     * @return Summary string for toast notification
     */
    suspend fun apply(bundle: ProvisionBundle, context: Context): String {
        val settings = SettingsRepository(context)

        // Hub MQTT settings
        settings.setHubUrl(bundle.mqttUrl)
        settings.setHubBridgeId(bundle.bridgeId)
        settings.setHubUsername(bundle.username)
        settings.setHubPassword(bundle.password)
        settings.setHubEnabled(true)

        // mTLS certificates
        settings.setHubClientCertPem(bundle.clientCertPem)
        settings.setHubClientKeyPem(bundle.clientKeyPem)
        if (bundle.caCertPem.isNotBlank()) {
            settings.setHubCaCertPem(bundle.caCertPem)
        }

        // Reticulum TCP peer
        if (bundle.reticulumTcp.isNotBlank()) {
            val parts = bundle.reticulumTcp.split(":")
            if (parts.size == 2) {
                settings.setRnsTcpHost(parts[0])
                settings.setRnsTcpPort(parts[1])
                settings.setRnsTcpEnabled(true)
                settings.setRnsTransportEnabled(true)
            }
        }

        // Store cert as ProviderCredential for credential management UI
        val db = AppDatabase.getInstance(context)
        db.providerCredentialDao().upsert(
            ProviderCredential(
                id = "hub_mtls_${bundle.bridgeId}",
                provider = "hub_mqtt",
                name = "Hub mTLS (${bundle.bridgeId})",
                credType = "mtls_bundle",
                encryptedData = (bundle.clientCertPem + "\n" + bundle.clientKeyPem).toByteArray(),
                certNotAfter = bundle.certExpiry.ifBlank { null },
                certSubject = "CN=${bundle.bridgeId}",
                source = "qr",
                version = 1,
            )
        )

        Log.i(TAG, "Hub provisioned: ${bundle.bridgeId} → ${bundle.mqttUrl}")
        return "Hub provisioned: ${bundle.bridgeId}"
    }

    /** Check if a URL is a provisioning URL. */
    fun isProvisionUrl(url: String): Boolean = url.startsWith(URL_PREFIX)
}
