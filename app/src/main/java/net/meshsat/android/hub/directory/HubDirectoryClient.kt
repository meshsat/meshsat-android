package net.meshsat.android.hub.directory

import net.meshsat.android.hub.relay.RelayTunnel
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * The phone's side of the Hub's signed directory (HeMB-NG C9.5): the Hub pushes nothing to a
 * phone, so it reads `GET /api/relay/directory` with its own bridge credentials and applies the
 * answer the way a Bridge applies a push (meshsat-hub docs/bridge-hub-protocol.md, "The phones
 * read it").
 *
 * The key: the one in the provisioning bundle (`dir_sign_pub`) is pinned when the bundle is
 * applied (applyProvisioning, which also sets the context the directory belongs to). Only a phone
 * with none pinned takes it once from `GET /api/relay/directory-key` over HTTPS, with no redirect
 * followed and the key hashing to its id; after that only a rotation record signed by the pinned
 * key, or a new provisioning, moves it. [MESHSAT-1642]
 *
 * [requireHttps] is false only in tests against a plain local server.
 */
class HubDirectoryClient(
    private val store: HubDirectoryStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val requireHttps: Boolean = true,
    private val timeoutMs: Int = 15_000,
) {
    /** What one poll did. */
    sealed class Outcome {
        object NotModified : Outcome()
        data class Applied(val result: DirectoryApply.Result) : Outcome()
        data class Refused(val result: DirectoryApply.Result) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /**
     * A provisioning bundle was applied (r40 review R40-R3). Scanning a provisioning code is a
     * deliberate act with a single-use claim over HTTPS, so it sets the context the directory
     * belongs to. A new [context] (another broker, bridge id, topic root or tenant) starts the
     * directory over even when the signing key is the same, as every tenant of a Hub shares it:
     * the held contacts, versions and tenant go, the bundle's key is pinned, or none when the bundle
     * has none (the HTTPS bootstrap then fetches the new Hub's once), and only snapshots of
     * [tenant] are taken. The same context keeps the directory, and pins the bundle's key when none
     * is pinned; a different key in the same context starts over too.
     */
    fun applyProvisioning(context: String, tenant: String, pkix: ByteArray?): DirectoryState {
        pkix?.let { DirectoryEnvelope.p256(it) }
        val b64 = pkix?.let { Base64.getEncoder().encodeToString(it) }
        return store.update { s ->
            val sameContext = s.context == context && s.expectedTenant == tenant
            when {
                sameContext && (b64 == null || s.pinnedKey == b64) -> s
                sameContext && s.pinnedKey.isEmpty() -> s.copy(pinnedKey = b64!!, pinSource = "bundle")
                else -> DirectoryState(
                    context = context, expectedTenant = tenant,
                    pinnedKey = b64 ?: "", pinSource = if (b64 != null) "bundle" else "",
                )
            }
        }
    }

    /** Take the Hub's key over HTTPS, only while none is pinned. True when a key is pinned after. */
    fun bootstrap(hubApiBase: String): Boolean {
        if (store.current.pinnedKey.isNotEmpty()) return true
        val context = store.current.context
        val url = endpoint(hubApiBase, "/api/relay/directory-key")
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("GET /api/relay/directory-key answered HTTP $code")
            checkNotRedirected(conn, url)
            val k = JSONObject(readCapped(conn))
            val pkix = DirectoryEnvelope.bytes(k, "pkix") ?: throw IOException("directory key: no pkix")
            if (k.optString("alg", "") != DirectoryEnvelope.ALG || DirectoryEnvelope.keyId(pkix) != k.optString("key_id", "")) {
                throw IOException("directory key: the key does not hash to its id")
            }
            DirectoryEnvelope.p256(pkix)
            val b64 = Base64.getEncoder().encodeToString(pkix)
            // A bundle that landed meanwhile wins: this only fills an empty pin, in the context
            // the fetch started in.
            store.update { s -> if (s.pinnedKey.isEmpty() && s.context == context) s.copy(pinnedKey = b64, pinSource = "https") else s }
            return store.current.pinnedKey.isNotEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** One read of the tenant's directory; bootstraps the key first when none is pinned. */
    fun poll(hubApiBase: String, bridgeId: String, password: String): Outcome {
        return try {
            if (!bootstrap(hubApiBase)) return Outcome.Failed("no directory key")
            val held = store.current
            // The answer applies only to the context it was asked in: a provisioning that lands
            // while it is out starts the directory over, and nothing of the old one comes back.
            val context = held.context
            val query = "?since=${held.version}&key_id=" + URLEncoder.encode(held.keyId, "UTF-8")
            val url = endpoint(hubApiBase, "/api/relay/directory$query")
            val conn = open(url)
            conn.setRequestProperty("Authorization", RelayTunnel.basicAuth(bridgeId, password))
            try {
                when (val code = conn.responseCode) {
                    304 -> {
                        store.update { if (it.context == context) it.copy(checkedAt = now()) else it }
                        Outcome.NotModified
                    }
                    200 -> {
                        checkNotRedirected(conn, url)
                        val answer = JSONObject(readCapped(conn))
                        var result: DirectoryApply.Result? = null
                        store.update { s ->
                            if (s.context != context) s
                            else DirectoryApply.answer(s, answer, now()).also { result = it }.state.copy(checkedAt = now())
                        }
                        val r = result ?: return Outcome.Failed("the provisioning changed while the directory was read; answer dropped")
                        if (r.reason.isEmpty()) Outcome.Applied(r) else Outcome.Refused(r)
                    }
                    else -> Outcome.Failed("GET /api/relay/directory answered HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun endpoint(hubApiBase: String, path: String): URL {
        val base = RelayTunnel.normalizeBase(hubApiBase)
        val url = URL(base + path)
        if (url.userInfo != null || url.host.isNullOrEmpty() || (requireHttps && url.protocol != "https")) {
            throw IOException("the Hub API URL $base is not an https URL")
        }
        return url
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        useCaches = false
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "meshsat-android-directory")
    }

    // A 3xx is not followed (instanceFollowRedirects off) and is no 200; this also refuses an
    // answer some proxy served from elsewhere.
    private fun checkNotRedirected(conn: HttpURLConnection, asked: URL) {
        val got = conn.url
        if (got.protocol != asked.protocol || got.host != asked.host || got.port != asked.port) {
            throw IOException("answered from ${got.protocol}://${got.host}, not ${asked.host}")
        }
    }

    private fun readCapped(conn: HttpURLConnection): String {
        val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_BODY + 1) }
        if (bytes.size > MAX_BODY) throw IOException("the directory answer is over ${MAX_BODY / 1024} KiB")
        return String(bytes, Charsets.UTF_8)
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < n) {
            val r = read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    companion object {
        /**
         * The context a provisioning bundle sets: the broker, the bridge id, the topic root and the
         * tenant. A change in any of them is another directory (r40 review R40-R3).
         */
        fun provisioningContext(mqttUrl: String, bridgeId: String, topicPrefix: String, tenant: String): String =
            listOf(mqttUrl.trim().lowercase(), bridgeId, topicPrefix, tenant).joinToString("|")

        /** A directory of a few thousand contacts; anything larger is refused unread. */
        const val MAX_BODY = 4 * 1024 * 1024
    }
}
