package net.meshsat.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bonding layer left the app (MESHSAT-1763): a version-24 database, which still has the bond-group
 * table, upgrades to 25 without it and keeps its messages, so the destructive fallback never runs.
 */
@RunWith(AndroidJUnit4::class)
class BondTableRemovalRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "bond-table-removal-test.db"
    private lateinit var db: AppDatabase

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
    fun aVersion24DatabaseUpgradesTo25WithoutTheBondTableAndKeepsItsMessages() = runBlocking {
        db.messageDao().insert(Message(transport = "mesh", direction = "rx", sender = "!1", text = "before the upgrade", encrypted = false, timestamp = 1))
        val path = context.getDatabasePath(name).path
        db.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS hemb_bond_groups (id TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL DEFAULT '', " +
                    "members TEXT NOT NULL DEFAULT '[]', costBudget REAL NOT NULL DEFAULT 0.0, createdAt INTEGER NOT NULL DEFAULT 0)"
            )
            it.execSQL("INSERT INTO hemb_bond_groups (id, label) VALUES ('bond1', 'Mesh+APRS')")
            it.version = 24
        }
        db = AppDatabase.build(context, name)
        assertEquals(25, db.openHelper.readableDatabase.version)
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE text = 'before the upgrade'"))
        assertEquals(0, count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'hemb_bond_groups'"))
    }
}
