package com.cubeos.meshsat

import com.cubeos.meshsat.data.TelemetryDao
import com.cubeos.meshsat.data.TelemetryEntity
import com.cubeos.meshsat.engine.TelemetryLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TelemetryLogger] covering heap/health/event paths, retention
 * trim, and opt-out enforcement. Crash recovery (file → DB) is tested
 * indirectly: the file I/O path requires an Android Context so it's covered
 * by the on-device verification, not here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryLoggerTest {

    // --- Fake DAO ---

    private class FakeTelemetryDao : TelemetryDao {
        val rows = mutableListOf<TelemetryEntity>()
        private var nextId = 1L

        override suspend fun insert(entry: TelemetryEntity): Long {
            val id = nextId++
            rows.add(entry.copy(id = id))
            return id
        }

        override suspend fun getByType(type: String, limit: Int): List<TelemetryEntity> =
            rows.filter { it.type == type }.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun getRecent(limit: Int): List<TelemetryEntity> =
            rows.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun getSince(sinceId: Long, limit: Int): List<TelemetryEntity> =
            rows.filter { it.id > sinceId }.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun countByType(type: String): Int =
            rows.count { it.type == type }

        override suspend fun count(): Int = rows.size

        override suspend fun trimType(type: String, keep: Int) {
            val keepIds = rows
                .filter { it.type == type }
                .sortedByDescending { it.timestamp }
                .take(keep)
                .map { it.id }
                .toSet()
            rows.removeAll { it.type == type && it.id !in keepIds }
        }

        override suspend fun deleteAll() { rows.clear() }

        override suspend fun deleteByType(type: String) {
            rows.removeAll { it.type == type }
        }
    }

    private fun buildLogger(
        dao: TelemetryDao,
        testScope: TestScope,
        enabled: Boolean = true,
    ): TelemetryLogger =
        TelemetryLogger(
            dao = dao,
            scope = testScope,
            enabledProvider = { enabled },
        )

    // --- Heap ---

    @Test
    fun `recordHeap writes a sample entry`() = runTest {
        val dao = FakeTelemetryDao()
        val logger = buildLogger(dao, this)

        logger.recordHeap()
        advanceUntilIdle()

        assertEquals(1, dao.rows.size)
        val entry = dao.rows.first()
        assertEquals(TelemetryLogger.TYPE_HEAP, entry.type)
        assertEquals(TelemetryLogger.SEV_SAMPLE, entry.severity)
        assertEquals("HeapSampler", entry.tag)

        // BirthSigner canonical JSON produces {"key":value,...} — assert keys
        // are present via simple substring check (no org.json in JVM tests).
        val detail = entry.detail
        assertTrue("dalvikUsed present: $detail", detail.contains("\"dalvikUsed\":"))
        assertTrue("dalvikTotal present: $detail", detail.contains("\"dalvikTotal\":"))
        assertTrue("dalvikMax present: $detail", detail.contains("\"dalvikMax\":"))
    }

    // --- Health ---

    @Test
    fun `recordHealth serializes map values to JSON`() = runTest {
        val dao = FakeTelemetryDao()
        val logger = buildLogger(dao, this)

        logger.recordHealth(
            message = "iface 3/5 online, pass mode Active",
            detail = mapOf(
                "interfacesOnline" to 3,
                "interfacesTotal" to 5,
                "passMode" to "Active",
                "sosActive" to false,
                "nested" to mapOf("inner" to 42),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, dao.rows.size)
        val entry = dao.rows.first()
        assertEquals(TelemetryLogger.TYPE_HEALTH, entry.type)
        assertEquals("iface 3/5 online, pass mode Active", entry.message)

        // BirthSigner canonical JSON: sorted keys, {"key":value,...}
        val detail = entry.detail
        assertTrue(detail.contains("\"interfacesOnline\":3"))
        assertTrue(detail.contains("\"interfacesTotal\":5"))
        assertTrue(detail.contains("\"passMode\":\"Active\""))
        assertTrue(detail.contains("\"sosActive\":false"))
        assertTrue(detail.contains("\"nested\":{\"inner\":42}"))
    }

    // --- Events ---

    @Test
    fun `recordEvent with default severity is info`() = runTest {
        val dao = FakeTelemetryDao()
        val logger = buildLogger(dao, this)

        logger.recordEvent("KeyBundleImporter", "Bridge pinned", mapOf("hash" to "deadbeef"))
        advanceUntilIdle()

        assertEquals(1, dao.rows.size)
        val e = dao.rows.first()
        assertEquals(TelemetryLogger.TYPE_EVENT, e.type)
        assertEquals(TelemetryLogger.SEV_INFO, e.severity)
        assertEquals("KeyBundleImporter", e.tag)
        assertEquals("Bridge pinned", e.message)
        assertTrue(e.detail.contains("\"hash\":\"deadbeef\""))
    }

    @Test
    fun `recordEvent with warn severity captures degraded state`() = runTest {
        val dao = FakeTelemetryDao()
        val logger = buildLogger(dao, this)

        logger.recordEvent(
            tag = "PassScheduler",
            message = "Predictor call exceeded 500ms",
            detail = mapOf("elapsedMs" to 1234),
            severity = TelemetryLogger.SEV_WARN,
        )
        advanceUntilIdle()

        assertEquals(TelemetryLogger.SEV_WARN, dao.rows.first().severity)
    }

    // --- Retention trim ---

    @Test
    fun `event retention trims oldest above cap`() = runTest {
        val dao = FakeTelemetryDao()
        val logger = buildLogger(dao, this)

        val cap = TelemetryLogger.MAX_EVENTS
        // Write cap + 5 events (use distinct timestamps so trim order is deterministic)
        repeat(cap + 5) { i ->
            logger.recordEvent("Test", "event-$i", mapOf("i" to i))
            // No delay — the FakeDao preserves insertion order via `nextId`.
            // Trim is called after every insert, so the count never exceeds cap.
        }
        advanceUntilIdle()

        assertEquals(cap, dao.countByType(TelemetryLogger.TYPE_EVENT))
    }

    // --- Opt-out ---

    @Test
    fun `telemetry disabled discards all writes`() = runTest {
        val dao = FakeTelemetryDao()
        val logger = buildLogger(dao, this, enabled = false)

        logger.recordHeap()
        logger.recordHealth("test", mapOf("k" to "v"))
        logger.recordEvent("Test", "should not persist")
        advanceUntilIdle()

        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `telemetry toggle state is read per-write`() = runTest {
        val dao = FakeTelemetryDao()
        var enabled = true
        val logger = TelemetryLogger(
            dao = dao,
            scope = this,
            enabledProvider = { enabled },
        )

        logger.recordEvent("Test", "first")
        advanceUntilIdle()
        assertEquals(1, dao.rows.size)

        enabled = false
        logger.recordEvent("Test", "second should be dropped")
        advanceUntilIdle()
        assertEquals(1, dao.rows.size)

        enabled = true
        logger.recordEvent("Test", "third resumed")
        advanceUntilIdle()
        assertEquals(2, dao.rows.size)
    }

    // --- Isolation across types ---

    @Test
    fun `retention per type is independent`() = runTest {
        val dao = FakeTelemetryDao()
        val logger = buildLogger(dao, this)

        logger.recordEvent("Test", "e1")
        logger.recordEvent("Test", "e2")
        logger.recordHealth("h1", emptyMap())
        logger.recordHeap()
        advanceUntilIdle()

        assertEquals(2, dao.countByType(TelemetryLogger.TYPE_EVENT))
        assertEquals(1, dao.countByType(TelemetryLogger.TYPE_HEALTH))
        assertEquals(1, dao.countByType(TelemetryLogger.TYPE_HEAP))
        assertEquals(0, dao.countByType(TelemetryLogger.TYPE_CRASH))
    }
}
