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
 * old still places a pass within seconds, far finer than any window. A download from
 * Celestrak only refreshes that data, and a failed one keeps what is there.
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
        private const val FETCH_TIMEOUT_MS = 20_000
        private const val CACHE_MAX_AGE_SEC = 86400L  // 24 hours

        /** A fetcher that falls back to the snapshot in the app's assets. */
        fun forContext(context: Context, db: AppDatabase): TleFetcher = TleFetcher(db) {
            runCatching {
                context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        }

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
        dao.getAll().mapNotNull { entry ->
            TleParser.parse(entry.satelliteName, entry.line1, entry.line2)
        }
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
     * Returns true if the download is older than a day, or there never was one.
     */
    suspend fun isCacheStale(): Boolean {
        val age = cacheAgeSec()
        return age < 0 || age > CACHE_MAX_AGE_SEC
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
            conn.setRequestProperty("User-Agent", "MeshSat-Android (+https://meshsat.net)")

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                Log.w(TAG, "Celestrak answered HTTP $code; keeping the current elements")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val tles = TleParser.parseMulti(body)
            if (tles.isEmpty()) {
                Log.w(TAG, "Celestrak returned no parsable elements; keeping the current ones")
                return@withContext null
            }

            // Replace cache
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
            Log.i(TAG, "Downloaded ${tles.size} Iridium element sets from Celestrak")
            tles
        } catch (e: Exception) {
            Log.w(TAG, "Celestrak download failed (${e.javaClass.simpleName}: ${e.message}); keeping the current elements")
            null
        }
    }

    /**
     * Elements to predict with. With [forceRefresh] (the Refresh button) a download is tried
     * first; otherwise nothing waits on the network.
     */
    suspend fun getTles(forceRefresh: Boolean = false): List<TleElements> {
        if (forceRefresh) refreshFromCelestrak()?.let { return it }
        return localTles().tles
    }
}
