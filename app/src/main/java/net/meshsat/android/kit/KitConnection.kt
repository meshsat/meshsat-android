package net.meshsat.android.kit

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A field kit this phone reaches directly, on the LAN (MESHSAT-1738): a name, the kit's address,
 * and an id the API token is filed under in the encrypted preferences. The token is never part of
 * this record, so a settings export or a log line that carries it carries no secret.
 *
 * The Hub relay is the other way to a kit and needs nothing of this: the Hub verified the phone's
 * certificate before it opened the tunnel, and the kit takes the tunnel as the credential.
 */
data class KitConnection(
    val id: String,
    val name: String,
    /** As typed; [KitApiClient.normalizeBase] makes it a base URL. */
    val url: String,
) {
    val base: String get() = KitApiClient.normalizeBase(url)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)

    companion object {
        fun create(name: String = "", url: String = ""): KitConnection =
            KitConnection(UUID.randomUUID().toString().take(8), name, url)

        /** Where the kit's API token lives in the encrypted preferences. */
        fun tokenKey(id: String): String = "kit_api_token:$id"

        fun fromJson(o: JSONObject): KitConnection? {
            val id = o.optString("id", "")
            if (id.isEmpty()) return null
            return KitConnection(id, o.optString("name", ""), o.optString("url", ""))
        }

        fun encodeList(list: List<KitConnection>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun decodeList(s: String): List<KitConnection> {
            if (s.isBlank()) return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { fromJson(it) } }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
