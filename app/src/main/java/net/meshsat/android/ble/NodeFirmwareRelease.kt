package net.meshsat.android.ble

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The newest MeshSat node firmware, as meshsat-firmware publishes it on GitHub (MESHSAT-1672):
 * every release carries one app image per node build and a `manifest.json` that names them,
 * and the manifest of the newest release is always at [MANIFEST_URL]:
 *
 *     {"schema": 1, "version": "2.8.0.4012de5", "tag": "meshsat-node-2.8.0.4012de5",
 *      "commit": "<sha>", "released_at": "<ISO 8601>",
 *      "images": [{"env": "meshsat-tbeam-s3-rockblock9704",
 *                  "file": "firmware-meshsat-tbeam-s3-rockblock9704-2.8.0.4012de5.bin",
 *                  "size": 2419024, "sha256": "<64 hex>"}]}
 *
 * The phone asks only when someone taps "Check for update"; nothing here runs on its own. An image
 * is taken only for the node's own build (pio_env, exact match) and only when its size and SHA-256
 * are the manifest's; [NodeFirmwareImage.check] then runs as for a file.
 */
object NodeFirmwareRelease {
    const val MANIFEST_URL = "https://github.com/meshsat/meshsat-firmware/releases/latest/download/manifest.json"
    const val DOWNLOAD_BASE = "https://github.com/meshsat/meshsat-firmware/releases/download/"
    const val SCHEMA = 1

    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")

    data class Image(val env: String, val file: String, val size: Int, val sha256: String)

    data class Manifest(
        val version: String,
        val tag: String,
        val commit: String,
        val releasedAt: String,
        val images: List<Image>,
    )

    sealed class Parsed {
        data class Ok(val manifest: Manifest) : Parsed()
        data class Refused(val reason: String) : Parsed()
    }

    sealed class Pick {
        data class Found(val manifest: Manifest, val image: Image) : Pick()
        data class Refused(val reason: String) : Pick()
    }

    /** The manifest's text, checked field by field; anything off refuses the whole release. */
    fun parse(text: String): Parsed {
        val o = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Parsed.Refused("The release list could not be read.")
        }
        val schema = o.optInt("schema", -1)
        if (schema != SCHEMA) {
            return Parsed.Refused("The release list is in a newer format (schema $schema). Update MeshSat first.")
        }
        val version = o.optString("version").trim()
        val tag = o.optString("tag").trim()
        if (version.isEmpty() || !SAFE_NAME.matches(tag)) return Parsed.Refused("The release list names no version.")
        val arr = o.optJSONArray("images") ?: return Parsed.Refused("The release list has no firmware images.")
        val images = mutableListOf<Image>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: return Parsed.Refused("The release list has a broken image entry.")
            val env = it.optString("env").trim()
            val file = it.optString("file").trim()
            val size = it.optLong("size", -1)
            val sha = it.optString("sha256").trim().lowercase()
            if (env.isEmpty() || !SAFE_NAME.matches(file) || size <= 0 || size > NodeFirmwareImage.APP_SLOT_BYTES || !HEX64.matches(sha)) {
                return Parsed.Refused("The release list has a broken image entry ($env).")
            }
            images.add(Image(env, file, size.toInt(), sha))
        }
        if (images.isEmpty()) return Parsed.Refused("The release list has no firmware images.")
        return Parsed.Ok(Manifest(version, tag, o.optString("commit"), o.optString("released_at"), images))
    }

    /** The image built for [nodeEnv], by exact name: a 9603 node never takes the 9704 build. */
    fun pick(manifest: Manifest, nodeEnv: String): Pick {
        if (nodeEnv.isBlank()) return Pick.Refused("The node did not say which firmware build it runs; reconnect to the node and try again.")
        val image = manifest.images.firstOrNull { it.env == nodeEnv }
            ?: return Pick.Refused("Release ${manifest.version} has no firmware for this node's build ($nodeEnv).")
        return Pick.Found(manifest, image)
    }

    fun imageUrl(manifest: Manifest, image: Image): String =
        DOWNLOAD_BASE + URLEncoder.encode(manifest.tag, "UTF-8") + "/" + URLEncoder.encode(image.file, "UTF-8")

    /** Null when [bytes] are the manifest's image, else why not. */
    fun verify(bytes: ByteArray, image: Image): String? {
        if (bytes.size != image.size) return "The download is ${bytes.size} bytes, the release says ${image.size}. Try again."
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        if (sha != image.sha256) return "The download does not match the release's SHA-256. Nothing was sent to the node."
        return null
    }

    /** True when the node already runs [manifest]'s version ("2.8.0.4012de5" against "2.8.0.4012de5"). */
    fun isCurrent(manifest: Manifest, running: String): Boolean = running.isNotBlank() && running.trim() == manifest.version

    /**
     * The network side: HTTPS only, GitHub's redirects to its download host followed, sizes capped.
     * Blocking; call it off the main thread.
     */
    class Fetcher(private val timeoutMs: Int = 20_000) {
        fun manifest(): Parsed = try {
            parse(String(get(MANIFEST_URL, MAX_MANIFEST_BYTES, null), Charsets.UTF_8))
        } catch (e: IOException) {
            Parsed.Refused("Could not reach the release list: ${e.message ?: "no answer"}.")
        }

        /** The image's bytes, at most [Image.size]; [onProgress] gets the fraction read. */
        fun download(manifest: Manifest, image: Image, onProgress: (Float) -> Unit): ByteArray =
            get(imageUrl(manifest, image), image.size, onProgress)

        private fun get(url: String, maxBytes: Int, onProgress: ((Float) -> Unit)?): ByteArray {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("User-Agent", "meshsat-android-node-firmware")
            }
            try {
                val code = conn.responseCode
                if (conn.url.protocol != "https") throw IOException("the download left HTTPS")
                if (code == 404) throw IOException("no release published yet")
                if (code != 200) throw IOException("HTTP $code")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        if (out.size() > maxBytes) throw IOException("the answer is larger than expected")
                        onProgress?.invoke(out.size().toFloat() / maxBytes)
                    }
                }
                return out.toByteArray()
            } finally {
                conn.disconnect()
            }
        }
    }
}
