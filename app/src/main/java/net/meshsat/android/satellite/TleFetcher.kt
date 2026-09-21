package net.meshsat.android.satellite

import android.content.Context
import android.util.Log
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.TleCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Iridium orbital elements for pass prediction, offline first.
 *
 * The app must predict passes with no network at all, like the Bridge. So predictions use
 * [localTles]: the elements downloaded last, or the snapshot shipped in the app
 * ([BUNDLED_ASSET]), whichever is newer. Iridium orbits drift slowly: a snapshot a few weeks
 * old still places a pass within seconds, far finer than any window. A download only
 * refreshes that data: Celestrak first, then the public TLE API (tle.ivanstanojevic.me)
 * when Celestrak cannot be reached, and a failed one keeps what is there.
 */
class TleFetcher(
    private val db: AppDatabase,
    private val bundledText: () -> String? = { null },
) {

    companion object {
        private const val TAG = "TleFetcher"
        const val BUNDLED_ASSET = "tle/iridium-next.3le"
        private const val CELESTRAK_IRIDIUM_URL =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=iridium-NEXT&FORMAT=3le"
        // Fallback source (MESHSAT-1240): the same public element sets, searchable by name.
        // Sorted, because without an order the API shuffles between requests: pages fetched one
        // after another repeated some satellites and skipped others (MESHSAT-1304).
        private const val TLE_API_URL =
            "https://tle.ivanstanojevic.me/api/tle/?search=IRIDIUM&page-size=100&sort=id&sort-dir=asc&page="
        private const val TLE_API_MAX_PAGES = 6
        private const val USER_AGENT = "MeshSat-Android (+https://meshsat.net)"
        private val IRIDIUM_NEXT_NAME = Regex("IRIDIUM 1\\d\\d")
        private const val FETCH_TIMEOUT_MS = 20_000
        private const val CACHE_MAX_AGE_SEC = 86400L  // 24 hours

        /** A fetcher that falls back to the snapshot in the app's assets. */
        fun forContext(context: Context, db: AppDatabase): TleFetcher = TleFetcher(db) {
            runCatching {
                context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        }

        /**
         * Whether [name] is an Iridium NEXT satellite ("IRIDIUM 100".."IRIDIUM 199"): the name
         * search also returns the retired first generation and debris.
         */
        fun isIridiumNext(name: String): Boolean = IRIDIUM_NEXT_NAME.matches(name.trim())

        /**
         * One element set per satellite, the newest, ordered by name. A download that holds a
         * satellite twice would predict each of its passes twice.
         */
        fun onePerSatellite(tles: List<TleElements>): List<TleElements> =
            tles.groupBy { it.catalogNumber }
                .map { (_, sets) -> sets.maxBy { it.epochJd } }
                .sortedBy { it.name }

        /** Newest element epoch in unix seconds, or 0 for an empty set. */
        fun newestEpochUnix(tles: List<TleElements>): Long =
            tles.maxOfOrNull { TleParser.jdToUnix(it.epochJd) } ?: 0L

        /**
         * The downloaded set, unless the bundled one is newer: an app update after a long
         * offline spell brings fresher elements than the last download.
         */
        fun choose(downloaded: List<TleElements>, bundled: List<TleElements>): TleSet {
            val d = newestEpochUnix(downloaded)
            val b = newestEpochUnix(bundled)
            return when {
                downloaded.isNotEmpty() && d >= b -> TleSet(downloaded, Source.Downloaded, d)
                bundled.isNotEmpty() -> TleSet(bundled, Source.Bundled, b)
                else -> TleSet(emptyList(), Source.None, 0)
            }
        }
    }

    enum class Source { Downloaded, Bundled, None }

    data class TleSet(val tles: List<TleElements>, val source: Source, val newestEpochUnix: Long) {
        /** Age of the newest element set in seconds, or -1 without data. */
        fun ageSec(nowUnix: Long = System.currentTimeMillis() / 1000): Long =
            if (tles.isEmpty()) -1 else (nowUnix - newestEpochUnix).coerceAtLeast(0)
    }

    private val bundledTles: List<TleElements> by lazy {
        bundledText()?.let { TleParser.parseMulti(it) } ?: emptyList()
    }

    /**
     * Get cached TLEs as parsed elements. Returns empty list if no cache.
     */
    suspend fun getCachedTles(): List<TleElements> = withContext(Dispatchers.IO) {
        val dao = db.tleCacheDao()
        onePerSatellite(dao.getAll().mapNotNull { entry ->
            TleParser.parse(entry.satelliteName, entry.line1, entry.line2)
        })
    }

    /**
     * Seconds since the last successful download, or -1 if there was none.
     */
    suspend fun cacheAgeSec(): Long = withContext(Dispatchers.IO) {
        val dao = db.tleCacheDao()
        val oldest = dao.getOldestFetchTime() ?: return@withContext -1L
        System.currentTimeMillis() / 1000 - oldest
    }

    /**
     * Returns true if the download is older than a day, there never was one, or it lacks
     * satellites the snapshot in the app has (a download made before MESHSAT-1304 could skip
     * some).
     */
    suspend fun isCacheStale(): Boolean {
        val age = cacheAgeSec()
        if (age < 0 || age > CACHE_MAX_AGE_SEC) return true
        return getCachedTles().size < onePerSatellite(bundledTles).size
    }

    /** The elements to predict with. Never touches the network. */
    suspend fun localTles(): TleSet = choose(getCachedTles(), bundledTles)

    /**
     * Fetch TLEs from Celestrak and update the cache. Returns the parsed list on success, or
     * null on failure, in which case the cache is left as it was.
     */
    suspend fun refreshFromCelestrak(): List<TleElements>? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(CELESTRAK_IRIDIUM_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = FETCH_TIMEOUT_MS
            conn.readTimeout = FETCH_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                Log.w(TAG, "Celestrak answered HTTP $code; keeping the current elements")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val tles = onePerSatellite(TleParser.parseMulti(body))
            if (tles.isEmpty()) {
                Log.w(TAG, "Celestrak returned no parsable elements; keeping the current ones")
                return@withContext null
            }

            store(tles)
            Log.i(TAG, "Downloaded ${tles.size} Iridium element sets from Celestrak")
            tles
        } catch (e: Exception) {
            Log.w(TAG, "Celestrak download failed (${e.javaClass.simpleName}: ${e.message}); keeping the current elements")
            null
        }
    }

    /**
     * Fetch the Iridium NEXT elements from the public TLE API, page by page, and update the
     * cache. Returns null on failure, leaving the cache as it was.
     */
    suspend fun refreshFromTleApi(): List<TleElements>? = withContext(Dispatchers.IO) {
        try {
            val found = mutableListOf<TleElements>()
            for (page in 1..TLE_API_MAX_PAGES) {
                val conn = URL(TLE_API_URL + page).openConnection() as HttpURLConnection
                conn.connectTimeout = FETCH_TIMEOUT_MS
                conn.readTimeout = FETCH_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    Log.w(TAG, "TLE API answered HTTP $code on page $page")
                    return@withContext null
                }
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val members = json.optJSONArray("member") ?: break
                for (i in 0 until members.length()) {
                    val m = members.getJSONObject(i)
                    val name = m.optString("name")
                    if (!isIridiumNext(name)) continue
                    TleParser.parse(name.trim(), m.optString("line1"), m.optString("line2"))?.let { found.add(it) }
                }
                if (json.optJSONObject("view")?.has("next") != true) break
            }
            if (found.isEmpty()) {
                Log.w(TAG, "TLE API returned no Iridium NEXT elements; keeping the current ones")
                return@withContext null
            }
            val tles = onePerSatellite(found)
            store(tles)
            Log.i(TAG, "Downloaded ${tles.size} Iridium element sets from the TLE API")
            tles
        } catch (e: Exception) {
            Log.w(TAG, "TLE API download failed (${e.javaClass.simpleName}: ${e.message}); keeping the current elements")
            null
        }
    }

    /** Refresh from the network: Celestrak, then the TLE API. Null when both failed. */
    suspend fun refreshFromNetwork(): List<TleElements>? = refreshFromCelestrak() ?: refreshFromTleApi()

    private suspend fun store(tles: List<TleElements>) {
        val now = System.currentTimeMillis() / 1000
        val dao = db.tleCacheDao()
        dao.deleteAll()
        dao.insertAll(tles.map { tle ->
            TleCacheEntity(
                satelliteName = tle.name,
                line1 = tle.line1,
                line2 = tle.line2,
                fetchedAt = now,
            )
        })
    }

    /**
     * Elements to predict with. With [forceRefresh] (the Refresh button) a download is tried
     * first; otherwise nothing waits on the network.
     */
    suspend fun getTles(forceRefresh: Boolean = false): List<TleElements> {
        if (forceRefresh) refreshFromNetwork()?.let { return it }
        return localTles().tles
    }
}
