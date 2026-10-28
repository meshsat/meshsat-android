package net.meshsat.android.kit

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One kit's API on the LAN (MESHSAT-1738): plain HTTP to port 6050, with the kit's API token as
 * `Authorization: Bearer <token>` on every request when the phone holds one. A kit with no token
 * set answers as before; a kit with one answers 401 to a request without it, and that 401 is what
 * the kit screen turns into its one-line prompt (see [KitProbe]).
 *
 * Through the Hub relay nothing of this applies: that path is `RelayHttp`, the tunnel is the
 * credential, and no token is sent.
 *
 * The token goes on the wire and nowhere else: not in [toString], not in the log callback, not in
 * an exception's message.
 */
class KitApiClient(
    baseUrl: String,
    private val token: String = "",
    private val timeoutMs: Int = 8_000,
    private val log: (String) -> Unit = {},
) {
    val base: String = normalizeBase(baseUrl)

    data class Response(val code: Int, val body: String) {
        /** The kit asks for its API token (`WWW-Authenticate: Bearer realm="meshsat"`). */
        val needsToken: Boolean get() = code == HttpURLConnection.HTTP_UNAUTHORIZED
        val ok: Boolean get() = code in 200..299
    }

    fun get(path: String): Response = request("GET", path, null)

    fun post(path: String, json: String?): Response = request("POST", path, json)

    private fun request(method: String, path: String, json: String?): Response {
        val url = URL(base + (if (path.startsWith("/")) path else "/$path"))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "meshsat-android-kit")
            authHeader(token)?.let { conn.setRequestProperty("Authorization", it) }
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= HttpURLConnection.HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream
            val body = stream?.use { String(it.readNBytesCapped(MAX_BODY), Charsets.UTF_8) } ?: ""
            log("$method $path -> $code (token ${if (token.isBlank()) "not sent" else "sent"})")
            return Response(code, body)
        } catch (e: IOException) {
            // The message names the host and the failure, never the header.
            log("$method $path failed: ${scrub(e.message ?: e.javaClass.simpleName)}")
            throw IOException(scrub(e.message ?: e.javaClass.simpleName), e)
        } finally {
            conn.disconnect()
        }
    }

    /** A text with the token, if it ever got in, taken out. */
    fun scrub(s: String): String = if (token.isBlank()) s else s.replace(token, "<token>")

    override fun toString(): String = "KitApiClient($base, token ${if (token.isBlank()) "not set" else "set"})"

    private fun java.io.InputStream.readNBytesCapped(n: Int): ByteArray {
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
        /** The Bridge's port. */
        const val DEFAULT_PORT = 6050
        private const val MAX_BODY = 256 * 1024

        /** The header's value for [token], or null when there is none to send. */
        fun authHeader(token: String): String? = token.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

        /**
         * "192.168.1.20", "kit-a.local:6050/", "http://kit-a" become "http://192.168.1.20:6050",
         * "http://kit-a.local:6050", "http://kit-a:6050": a scheme when none is given, the Bridge's
         * port when none is given, no trailing slash. An https URL is kept as typed.
         */
        fun normalizeBase(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return ""
            if (!u.contains("://")) u = "http://$u"
            val parsed = try { URL(u) } catch (_: Exception) { return u }
            val host = parsed.host.orEmpty()
            if (host.isEmpty()) return u
            val port = if (parsed.port == -1) DEFAULT_PORT else parsed.port
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "${parsed.protocol}://$hostPart:$port" + parsed.path.trimEnd('/')
        }
    }
}

/**
 * What one request to a kit says about reaching it: the kit screen's line.
 */
object KitProbe {
    sealed class Result {
        /** The kit answered; [summary] is a word or two from `/api/status`. */
        data class Reachable(val summary: String) : Result()

        /** 401 with no token held: the kit asks for its API token. */
        data object NeedsToken : Result()

        /** 401 with a token held: the kit refused it. */
        data object WrongToken : Result()

        data class Unreachable(val reason: String) : Result()
    }

    const val STATUS_PATH = "/api/status"

    /** One GET of `/api/status`. [hasToken] says whether the client was given one. */
    fun check(client: KitApiClient, hasToken: Boolean): Result = try {
        val r = client.get(STATUS_PATH)
        when {
            r.needsToken && !hasToken -> Result.NeedsToken
            r.needsToken -> Result.WrongToken
            r.ok -> Result.Reachable(summarise(r.body))
            else -> Result.Unreachable("HTTP ${r.code}")
        }
    } catch (e: Exception) {
        Result.Unreachable(client.scrub(e.message ?: e.javaClass.simpleName))
    }

    /** The one line under a kit on its screen. */
    fun line(r: Result): String = when (r) {
        is Result.Reachable -> if (r.summary.isEmpty()) "Reachable." else "Reachable: ${r.summary}"
        Result.NeedsToken -> "This kit asks for its API token: enter it above and check again."
        Result.WrongToken -> "The kit refused this API token. Check it on the kit (GET /api/auth/status) and enter it again."
        is Result.Unreachable -> "Not reachable: ${r.reason}"
    }

    /** A line is a prompt for the token when the kit asked for one. */
    fun asksForToken(r: Result): Boolean = r == Result.NeedsToken || r == Result.WrongToken

    private fun summarise(body: String): String = try {
        val o = org.json.JSONObject(body)
        listOfNotNull(
            o.optString("bridge_id", "").takeIf { it.isNotEmpty() },
            o.optString("version", "").takeIf { it.isNotEmpty() }?.let { "v$it" },
            o.optString("mode", "").takeIf { it.isNotEmpty() },
        ).joinToString(", ")
    } catch (_: Exception) {
        ""
    }
}
