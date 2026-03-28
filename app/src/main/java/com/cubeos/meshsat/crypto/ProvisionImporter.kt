package com.cubeos.meshsat.crypto

import android.content.Context
import android.util.Log
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ProviderCredential
import com.cubeos.meshsat.data.SettingsRepository
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Two-step Hub provisioning via QR code.
 *
 * Step 1: QR contains a short URL: meshsat://provision/{bid}/{nonce}?hub={host}
 * Step 2: App fetches full credentials: GET https://{hub}/api/bridges/{bid}/provision/{nonce}
 *
 * The nonce is single-use (Hub deletes after claim) with 30-minute TTL.
 */
object ProvisionImporter {
    private const val TAG = "ProvisionImporter"
    private const val URL_PREFIX = "meshsat://provision/"

    /** Parsed QR code content — just the claim parameters, no credentials yet. */
    data class ProvisionRequest(
        val bridgeId: String,
        val nonce: String,
        val hubHost: String,
    )

    /** Full credential bundle fetched from Hub. */
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

    /** Check if a scanned string is a provisioning URL. */
    fun isProvisionUrl(url: String): Boolean = url.startsWith(URL_PREFIX)

    /**
     * Parse a meshsat://provision/{bid}/{nonce}?hub={host} URL.
     * @throws IllegalArgumentException if format is invalid
     */
    fun parseQr(url: String): ProvisionRequest {
        require(url.startsWith(URL_PREFIX)) { "Not a MeshSat provisioning QR code" }

        val withoutPrefix = url.substring(URL_PREFIX.length)
        // Parse: {bid}/{nonce}?hub={host}
        val queryIdx = withoutPrefix.indexOf('?')
        require(queryIdx > 0) { "Missing ?hub= parameter" }

        val path = withoutPrefix.substring(0, queryIdx)
        val query = withoutPrefix.substring(queryIdx + 1)

        val parts = path.split("/")
        require(parts.size == 2) { "Expected meshsat://provision/{bid}/{nonce}" }

        val bid = parts[0]
        val nonce = parts[1]
        require(bid.isNotBlank()) { "Empty bridge ID" }
        require(nonce.length == 32 && nonce.all { it in "0123456789abcdef" }) {
            "Invalid nonce: must be 32 hex chars"
        }

        var hubHost = ""
        query.split("&").forEach { param ->
            val kv = param.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "hub") hubHost = kv[1]
        }
        require(hubHost.isNotBlank()) { "Missing hub host" }

        return ProvisionRequest(bid, nonce, hubHost)
    }

    /**
     * Claim the provision bundle from the Hub.
     * GET https://{hub}/api/bridges/{bid}/provision/{nonce}
     *
     * No auth header — the nonce IS the authentication.
     * Hub deletes the stash after this call (single-use).
     *
     * @throws ProvisionException with user-friendly message on failure
     */
    suspend fun claimBundle(request: ProvisionRequest): ProvisionBundle {
        val claimUrl = "https://${request.hubHost}/api/bridges/${request.bridgeId}/provision/${request.nonce}"
        Log.i(TAG, "Claiming provision: $claimUrl")

        val conn = URL(claimUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            when {
                code == 200 -> {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    return parseBundle(body)
                }
                code == 404 -> throw ProvisionException(
                    "Provisioning token expired or already used. Generate a new QR from the Hub."
                )
                code == 410 -> throw ProvisionException(
                    "Provisioning token expired (>30 minutes). Generate a new QR."
                )
                else -> throw ProvisionException(
                    "Hub returned HTTP $code. Try again or generate a new QR."
                )
            }
        } catch (e: ProvisionException) {
            throw e
        } catch (e: Exception) {
            throw ProvisionException(
                "Cannot reach Hub at ${request.hubHost}. Check network connectivity."
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Parse the JSON bundle returned by the claim endpoint. */
    private fun parseBundle(jsonStr: String): ProvisionBundle {
        val json = JSONObject(jsonStr)
        val v = json.optString("v", "1")
        return ProvisionBundle(
            version = v,
            bridgeId = json.optString("bid", ""),
            mqttUrl = json.optString("mqtt", ""),
            username = json.optString("user", ""),
            password = json.optString("pass", ""),
            clientCertPem = json.optString("cert", ""),
            clientKeyPem = json.optString("key", ""),
            caCertPem = json.optString("ca", ""),
            certExpiry = json.optString("cert_exp", ""),
            reticulumTcp = json.optString("ret_tcp", ""),
        )
    }

    /**
     * Apply a provisioning bundle — writes all settings and stores credentials.
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
        if (bundle.clientCertPem.isNotBlank()) {
            settings.setHubClientCertPem(bundle.clientCertPem)
        }
        if (bundle.clientKeyPem.isNotBlank()) {
            settings.setHubClientKeyPem(bundle.clientKeyPem)
        }
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
        if (bundle.clientCertPem.isNotBlank() && bundle.clientKeyPem.isNotBlank()) {
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
        }

        Log.i(TAG, "Hub provisioned: ${bundle.bridgeId} → ${bundle.mqttUrl}")
        return "Hub provisioned: ${bundle.bridgeId}"
    }

    class ProvisionException(message: String) : Exception(message)
}
