package net.meshsat.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.LXMF_MID_RETENTION_MS
import net.meshsat.android.data.Message
import net.meshsat.android.data.insertLxmfOnce
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The LXMF message ids on the app's real Room database (r40 review R40-R2, MESHSAT-1641): a message
 * and its (sender, mid) are written in one transaction, so a failed write leaves nothing behind and
 * the message is taken when it comes again, after a restart too; a repeat files nothing; senders
 * stay apart; a version-21 database upgrades and keeps its messages.
 */
@RunWith(AndroidJUnit4::class)
class LxmfMidRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "lxmf-mid-test.db"
    private lateinit var db: AppDatabase
    private val now = System.currentTimeMillis()

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

    private fun restart() {
        db.close()
        db = AppDatabase.build(context, name)
    }

    private fun sql(q: String) = db.openHelper.writableDatabase.execSQL(q)
    private fun count(q: String): Int = db.openHelper.readableDatabase.query(q).use { it.moveToFirst(); it.getInt(0) }
    private fun msg(text: String) = Message(transport = "lxmf", direction = "rx", sender = "aa", recipient = "bb", text = text, encrypted = false, timestamp = now)

    @Test
    fun aRepeatFilesNothingAndSendersStayApart() = runBlocking {
        assertNotNull(db.insertLxmfOnce("aa", "m1", now, msg("one")))
        assertNull(db.insertLxmfOnce("aa", "m1", now, msg("one again")))
        assertNotNull(db.insertLxmfOnce("bb", "m1", now, msg("another sender")))
        restart()
        assertNull("after a restart", db.insertLxmfOnce("aa", "m1", now, msg("one, a third time")))
        assertEquals(2, count("SELECT COUNT(*) FROM messages WHERE transport = 'lxmf'"))
        assertEquals(2, db.lxmfMidDao().count())
    }

    /** The r40 review's counterexample: the inbox write fails, the app restarts, the message comes again. */
    @Test
    fun aFailedWriteLeavesNothingAndTheRetryIsTaken() = runBlocking {
        sql("CREATE TRIGGER r40_fail BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'test: inbox write fails'); END")
        val failed = runCatching { db.insertLxmfOnce("aa", "m1", now, msg("lost?")) }
        assertTrue("the write failed", failed.isFailure)
        assertEquals("no id left behind", 0, db.lxmfMidDao().count())
        assertEquals(0, count("SELECT COUNT(*) FROM messages"))
        sql("DROP TRIGGER r40_fail")
        restart()
        assertNotNull(db.insertLxmfOnce("aa", "m1", now, msg("taken now")))
        assertNull(db.insertLxmfOnce("aa", "m1", now, msg("and only once")))
        assertEquals(1, count("SELECT COUNT(*) FROM messages"))
    }

    @Test
    fun deliveriesAtTheSameTimeFileOnce() = runBlocking {
        val ids = (1..16).map { async(kotlinx.coroutines.Dispatchers.IO) { db.insertLxmfOnce("aa", "same", now, msg("once")) } }.awaitAll()
        assertEquals(1, ids.count { it != null })
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE text = 'once'"))
    }

    @Test
    fun idsPastTheRetentionGo() = runBlocking {
        db.insertLxmfOnce("aa", "old", now - LXMF_MID_RETENTION_MS - 60_000, msg("old"))
        db.insertLxmfOnce("aa", "new", now, msg("new"))
        assertEquals(1, db.lxmfMidDao().pruneBefore(now - LXMF_MID_RETENTION_MS))
        assertNotNull("a pruned id is taken again", db.insertLxmfOnce("aa", "old", now, msg("old again")))
    }

    /** The version-21 schema is today's without lxmf_mids; it upgrades and keeps its messages. */
    @Test
    fun aVersion21DatabaseUpgradesTo22AndKeepsItsMessages() = runBlocking {
        db.messageDao().insert(msg("before the upgrade"))
        val path = context.getDatabasePath(name).path
        db.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE lxmf_mids")
            it.version = 21
        }
        db = AppDatabase.build(context, name)
        assertEquals(22, db.openHelper.readableDatabase.version)
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE text = 'before the upgrade'"))
        assertNotNull(db.insertLxmfOnce("aa", "m1", now, msg("after")))
        assertNull(db.insertLxmfOnce("aa", "m1", now, msg("after, again")))
    }
}
