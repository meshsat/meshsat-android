package com.cubeos.meshsat.satellite

import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.TleCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and caches Iridium TLE data from Celestrak.
 */
class TleFetcher(private val db: AppDatabase) {

    companion object {
        private const val CELESTRAK_IRIDIUM_URL =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=iridium-NEXT&FORMAT=3le"
        private const val FETCH_TIMEOUT_MS = 30_000
        private const val CACHE_MAX_AGE_SEC = 86400L  // 24 hours
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
     * Cache age in seconds, or -1 if no cache.
     */
    suspend fun cacheAgeSec(): Long = withContext(Dispatchers.IO) {
        val dao = db.tleCacheDao()
        val oldest = dao.getOldestFetchTime() ?: return@withContext -1L
        System.currentTimeMillis() / 1000 - oldest
    }

    /**
     * Returns true if cache is stale (>24h) or empty.
     */
    suspend fun isCacheStale(): Boolean {
        val age = cacheAgeSec()
        return age < 0 || age > CACHE_MAX_AGE_SEC
    }

    /**
     * Fetch TLEs from Celestrak and update cache.
     * Returns the parsed TLE list on success, or null on failure.
     */
    suspend fun refreshFromCelestrak(): List<TleElements>? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(CELESTRAK_IRIDIUM_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = FETCH_TIMEOUT_MS
            conn.readTimeout = FETCH_TIMEOUT_MS

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val tles = TleParser.parseMulti(body)
            if (tles.isEmpty()) return@withContext null

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

            tles
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get TLEs from cache, refreshing if stale.
     */
    suspend fun getTles(forceRefresh: Boolean = false): List<TleElements> {
        if (forceRefresh || isCacheStale()) {
            refreshFromCelestrak()?.let { return it }
        }
        return getCachedTles()
    }
}
