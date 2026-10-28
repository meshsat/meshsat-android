package net.meshsat.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.Message
import net.meshsat.android.warnings.WarningIntake
import net.meshsat.android.warnings.WarningText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The warnings table on the app's real Room database (MESHSAT-1734): a version-23 database
 * upgrades to 24 and keeps its messages; the intake's queries do on SQLite what the unit test's
 * list did: one row for the kit's two copies, a cancel clears its event, the banner query finds the
 * newest standing warning not dismissed, pruning takes old rows only.
 */
@RunWith(AndroidJUnit4::class)
class WarningRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "warnings-test.db"
    private lateinit var db: AppDatabase
    private val label = "Relay of KNMI via MeteoAlarm warning, issued 28 Oct 10:11 UTC via MeshSat, not an official NL-Alert"
    private val wind = "WARNING Wind Moderate until 10:11 UTC: Kans op windstoten tot 90 km/u\n$label"

    @Before
    fun open() {
        context.deleteDatabase(name)
        db = AppDatabase.build(context, name)
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun count(q: String): Int = db.openHelper.readableDatabase.query(q).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun theKitsTwoCopiesAreOneRowAndACancelClearsIt() = runBlocking {
        var clock = 1_000_000L
        val intake = WarningIntake(db.warningDao()) { clock }
        val a = intake.onMesh("!4370c1d8", "tesseract", WarningText.PORT_ALERT, wind + "\u0007")
        assertTrue(a is WarningIntake.Outcome.Alarm)
        clock += 1_000
        assertEquals(WarningIntake.Outcome.Duplicate, intake.onMesh("!4370c1d8", "tesseract", WarningText.PORT_TEXT, wind))
        assertEquals(1, db.warningDao().count())
        val shown = db.warningDao().unacknowledged().first()
        assertNotNull(shown)
        assertEquals("Wind", shown!!.event)
        assertEquals("10:11", shown.until)
        assertEquals(11, shown.port)

        clock += 1_000
        intake.acknowledge(shown.id)
        assertNull(db.warningDao().unacknowledged().first())
        assertTrue(db.warningDao().byId(shown.id)!!.standing)

        clock += 1_000
        val c = intake.onMesh("!4370c1d8", "tesseract", WarningText.PORT_ALERT, "CANCELLED: Wind\n$label")
        assertTrue(c is WarningIntake.Outcome.Cleared)
        assertEquals(listOf(shown.id), (c as WarningIntake.Outcome.Cleared).cleared.map { it.id })
        assertEquals(clock, db.warningDao().byId(shown.id)!!.cancelledAt)
        assertTrue(db.warningDao().standingOf("Wind", false).isEmpty())
        val rows = db.warningDao().recent(10).first()
        assertEquals(listOf("cancel", "alert"), rows.map { it.kind })

        // Pruning: the older of two rows goes, the newer stays.
        assertEquals(1, db.warningDao().deleteOlderThan(clock))
        assertEquals(1, db.warningDao().count())
    }

    /** The version-23 schema is today's without warnings; it upgrades and keeps its messages. */
    @Test
    fun aVersion23DatabaseUpgradesTo24AndKeepsItsMessages() = runBlocking {
        db.messageDao().insert(Message(transport = "mesh", direction = "rx", sender = "!1", text = "before the upgrade", encrypted = false, timestamp = 1))
        val path = context.getDatabasePath(name).path
        db.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE warnings")
            it.version = 23
        }
        db = AppDatabase.build(context, name)
        assertEquals(24, db.openHelper.readableDatabase.version)
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE text = 'before the upgrade'"))
        val intake = WarningIntake(db.warningDao())
        assertTrue(intake.onMesh("!1", "", WarningText.PORT_ALERT, wind) is WarningIntake.Outcome.Alarm)
        assertEquals(1, db.warningDao().count())
        assertEquals(1, count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_warnings_dedup_key'"))
    }
}
