package net.meshsat.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.WarningDao
import net.meshsat.android.data.WarningEntity
import net.meshsat.android.warnings.WarningIntake
import net.meshsat.android.warnings.WarningIntake.Outcome
import net.meshsat.android.warnings.WarningText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state behind the alarm (MESHSAT-1734), on an in-memory copy of the warnings table: the
 * kit's two copies alarm once, a second kit's copy adds nothing, a newer warning of an event
 * replaces the older, a cancel clears what stands and only that, a test cancel clears tests.
 */
class WarningIntakeTest {

    /** The DAO as a list: what Room does for the same queries. */
    private class MemoryDao : WarningDao {
        val rows = mutableListOf<WarningEntity>()
        private var nextId = 1L
        override suspend fun insert(w: WarningEntity): Long { val id = nextId++; rows += w.copy(id = id); return id }
        override suspend fun countRecent(key: String, since: Long): Int = rows.count { it.dedupKey == key && it.receivedAt >= since }
        override suspend fun standingOf(event: String, test: Boolean): List<WarningEntity> =
            rows.filter { !it.isCancel && it.event == event && it.test == test && it.cancelledAt == 0L && it.replacedAt == 0L }.sortedWith(compareBy({ it.receivedAt }, { it.id }))
        override suspend fun markReplaced(ids: List<Long>, at: Long) = replace(ids) { it.copy(replacedAt = at) }
        override suspend fun markCancelled(ids: List<Long>, at: Long) = replace(ids) { it.copy(cancelledAt = at) }
        override suspend fun acknowledge(id: Long, at: Long) = replace(listOf(id)) { if (it.acknowledgedAt == 0L) it.copy(acknowledgedAt = at) else it }
        override suspend fun byId(id: Long): WarningEntity? = rows.firstOrNull { it.id == id }
        override fun recent(limit: Int): Flow<List<WarningEntity>> = flowOf(rows.sortedByDescending { it.receivedAt }.take(limit))
        override fun unacknowledged(): Flow<WarningEntity?> = flowOf(rows.filter { it.standing && it.acknowledgedAt == 0L }.maxByOrNull { it.receivedAt })
        override suspend fun count(): Int = rows.size
        override suspend fun deleteOlderThan(before: Long): Int { val n = rows.count { it.receivedAt < before }; rows.removeAll { it.receivedAt < before }; return n }
        private fun replace(ids: List<Long>, f: (WarningEntity) -> WarningEntity) {
            for (i in rows.indices) if (rows[i].id in ids) rows[i] = f(rows[i])
        }
    }

    private val label = "Relay of KNMI via MeteoAlarm warning, issued 28 Oct 10:11 UTC via MeshSat, not an official NL-Alert"
    private val wind = "WARNING Wind Moderate until 10:11 UTC: Kans op windstoten tot 90 km/u\n$label"
    private val windUpdate = "UPDATE: WARNING Wind Severe until 12:00 UTC: Windstoten tot 110 km/u\n$label"
    private val rain = "WARNING Rain Severe until 14:00 UTC: Veel regen\n$label"
    private val cancelWind = "CANCELLED: Wind\n$label"
    private val testLabel = "Relay of MeshSat test warning, issued 28 Oct 10:11 UTC via MeshSat, not an official NL-Alert"

    private var clock = 1_000_000L
    private val dao = MemoryDao()
    private val intake = WarningIntake(dao) { clock }

    @Test
    fun `the kit's port 11 copy alarms and its text copy is the same warning`() = runBlocking {
        val a = intake.onMesh("!4370c1d8", "tesseract", WarningText.PORT_ALERT, wind + "\u0007")
        assertTrue(a is Outcome.Alarm)
        val w = (a as Outcome.Alarm).warning
        assertEquals("Wind", w.event); assertEquals("Moderate", w.severity); assertEquals("10:11", w.until)
        assertEquals(11, w.port); assertEquals("!4370c1d8", w.sender); assertEquals("tesseract", w.senderName)
        assertEquals(WarningEntity.KIND_ALERT, w.kind)
        assertTrue(w.standing)
        clock += 2_000
        assertEquals(Outcome.Duplicate, intake.onMesh("!4370c1d8", "tesseract", WarningText.PORT_TEXT, wind))
        // The other kit in range relays the same digest: still the same warning.
        assertEquals(Outcome.Duplicate, intake.onMesh("!de11f199", "parallax", WarningText.PORT_ALERT, wind + "\u0007"))
        assertEquals(1, dao.count())
        // After the window the same words are heard as new (the kit does not repeat a digest; a
        // re-issued warning is a new one).
        clock += WarningIntake.DEDUP_MS
        assertTrue(intake.onMesh("!4370c1d8", "tesseract", WarningText.PORT_ALERT, wind) is Outcome.Alarm)
        assertEquals(2, dao.count())
    }

    @Test
    fun `an ordinary text is not a warning`() = runBlocking {
        assertEquals(Outcome.NotAWarning, intake.onMesh("!4370c1d8", "", WarningText.PORT_TEXT, "WARNING Wind Severe: the tent is gone"))
        assertEquals(Outcome.NotAWarning, intake.onMesh("!4370c1d8", "", WarningText.PORT_TEXT, "hello"))
        assertEquals(0, dao.count())
    }

    @Test
    fun `an update replaces the standing warning of its event and leaves another event alone`() = runBlocking {
        val first = (intake.onMesh("!1", "", 11, wind) as Outcome.Alarm).warning
        clock += 1_000
        val other = (intake.onMesh("!1", "", 11, rain) as Outcome.Alarm).warning
        clock += 1_000
        val second = (intake.onMesh("!1", "", 11, windUpdate) as Outcome.Alarm).warning
        assertEquals(WarningEntity.KIND_UPDATE, second.kind)
        assertEquals(clock, dao.byId(first.id)!!.replacedAt)
        assertEquals(0L, dao.byId(other.id)!!.replacedAt)
        assertEquals(listOf(second.id), dao.standingOf("Wind", false).map { it.id })
        assertEquals(listOf(other.id), dao.standingOf("Rain", false).map { it.id })
    }

    @Test
    fun `a cancel clears the standing warnings of its event and is kept as the all-clear`() = runBlocking {
        val w = (intake.onMesh("!1", "", 11, wind) as Outcome.Alarm).warning
        val r = (intake.onMesh("!1", "", 11, rain) as Outcome.Alarm).warning
        clock += 5_000
        val c = intake.onMesh("!1", "", 11, cancelWind)
        assertTrue(c is Outcome.Cleared)
        c as Outcome.Cleared
        assertEquals(listOf(w.id), c.cleared.map { it.id })
        assertEquals(WarningEntity.KIND_CANCEL, c.cancel.kind)
        assertEquals(clock, dao.byId(w.id)!!.cancelledAt)
        assertEquals(0L, dao.byId(r.id)!!.cancelledAt)
        assertTrue(dao.standingOf("Wind", false).isEmpty())
        // The cancel never asks to be dismissed, and the rain warning is what the banner shows.
        assertEquals(clock, c.cancel.acknowledgedAt)
        assertEquals(r.id, dao.unacknowledged().first()!!.id)
        // A cancel with nothing standing is still an all-clear, clearing nothing.
        clock += 5_000
        val again = intake.onMesh("!1", "", 11, "CANCELLED: Wind - Voorbij\n$label") as Outcome.Cleared
        assertTrue(again.cleared.isEmpty())
        assertEquals("Voorbij", again.cancel.text)
    }

    @Test
    fun `a test warning stands apart and a test cancel clears tests only`() = runBlocking {
        val real = (intake.onMesh("!1", "", 11, wind) as Outcome.Alarm).warning
        clock += 1_000
        val test = (intake.onMesh("!1", "", 11, "TEST WARNING Wind Minor until 10:11 UTC: proef\n$testLabel") as Outcome.Alarm).warning
        assertTrue(test.test)
        // The test did not replace the real warning.
        assertEquals(0L, dao.byId(real.id)!!.replacedAt)
        clock += 1_000
        val c = intake.onMesh("!1", "", 11, "TEST CANCELLED: Wind\n$testLabel") as Outcome.Cleared
        assertEquals(listOf(test.id), c.cleared.map { it.id })
        assertEquals(0L, dao.byId(real.id)!!.cancelledAt)
    }

    @Test
    fun `dismissing marks the warning acknowledged and it still stands`() = runBlocking {
        val w = (intake.onMesh("!1", "", 11, wind) as Outcome.Alarm).warning
        clock += 1_000
        intake.acknowledge(w.id)
        val row = dao.byId(w.id)!!
        assertEquals(clock, row.acknowledgedAt)
        assertTrue(row.standing)
        assertEquals(null, dao.unacknowledged().first())
    }
}
