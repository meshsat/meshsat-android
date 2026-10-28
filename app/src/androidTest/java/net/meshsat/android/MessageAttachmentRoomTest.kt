package net.meshsat.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.Message
import net.meshsat.android.data.insertAttachments
import net.meshsat.android.lxmf.LxAttachment
import net.meshsat.android.lxmf.LxAttachments
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Voice notes and photos on the app's real Room database (MESHSAT-1735): a version-22 database
 * upgrades to 23 and keeps its messages; attachments file with their row and come back in
 * order, image first; the store trims oldest first and never the rows just written; the chat
 * view's query finds both directions of one conversation.
 */
@RunWith(AndroidJUnit4::class)
class MessageAttachmentRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "message-attachments-test.db"
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

    private fun count(q: String): Int = db.openHelper.readableDatabase.query(q).use { it.moveToFirst(); it.getInt(0) }
    private fun msg(direction: String, peer: String, text: String) = Message(
        transport = "lxmf", direction = direction, sender = if (direction == "rx") peer else "me", recipient = if (direction == "tx") peer else "me",
        text = text, encrypted = false, timestamp = now,
    )
    private fun audio(n: Int, seed: Int = 1) = LxAttachment.audio(LxAttachments.AUDIO_OPUS_OGG, ByteArray(n) { (it * seed).toByte() })
    private fun image(n: Int) = LxAttachment.image("webp", ByteArray(n) { (it * 5).toByte() })

    @Test
    fun attachmentsFileWithTheirRowImageFirst() = runBlocking {
        val id = db.messageDao().insert(msg("rx", "aa", "listen"))
        val ids = db.insertAttachments(id, listOf(image(2048), audio(600)))
        assertEquals(2, ids.size)
        val rows = db.messageAttachmentDao().forMessageOnce(id)
        assertEquals(listOf("image", "audio"), rows.map { it.kind })
        assertEquals("webp", rows[0].format); assertEquals(0x10, rows[1].mode)
        assertEquals(2048, rows[0].size); assertEquals(600, rows[1].size)
        assertArrayEquals(audio(600).data, rows[1].toAttachment().data)
        assertEquals(2648L, db.messageAttachmentDao().totalBytes())
        assertEquals(2, db.messageAttachmentDao().deleteForMessage(id))
        assertEquals(0, db.messageAttachmentDao().count())
    }

    @Test
    fun theStoreTrimsOldestFirstAndKeepsWhatWasJustWritten() = runBlocking {
        val cap = 10_000L
        val m1 = db.messageDao().insert(msg("rx", "aa", "one"))
        val m2 = db.messageDao().insert(msg("rx", "aa", "two"))
        val m3 = db.messageDao().insert(msg("tx", "aa", "three"))
        db.insertAttachments(m1, listOf(audio(4000, 1)), now = now - 3000, cap = cap)
        db.insertAttachments(m2, listOf(audio(4000, 2)), now = now - 2000, cap = cap)
        // 8000 in the store; a 6000 note takes it to 14000, so the oldest (m1) goes and m2 stays.
        val kept = db.insertAttachments(m3, listOf(audio(6000, 3)), now = now - 1000, cap = cap)
        assertEquals(1, kept.size)
        assertTrue(db.messageAttachmentDao().forMessageOnce(m1).isEmpty())
        assertEquals(1, db.messageAttachmentDao().forMessageOnce(m2).size)
        assertEquals(1, db.messageAttachmentDao().forMessageOnce(m3).size)
        assertEquals(10_000L, db.messageAttachmentDao().totalBytes())
        // One attachment larger than everything else: the rest goes, the new one is never evicted.
        val m4 = db.messageDao().insert(msg("rx", "aa", "four"))
        db.insertAttachments(m4, listOf(audio(9000, 4)), now = now, cap = cap)
        assertEquals(1, db.messageAttachmentDao().count())
        assertEquals(1, db.messageAttachmentDao().forMessageOnce(m4).size)
        // Even over the cap by itself it is kept: the cap trims others, it does not refuse.
        val m5 = db.messageDao().insert(msg("rx", "aa", "five"))
        db.insertAttachments(m5, listOf(audio(12_000, 5)), now = now + 1, cap = cap)
        assertEquals(1, db.messageAttachmentDao().count())
        assertEquals(12_000L, db.messageAttachmentDao().totalBytes())
    }

    @Test
    fun theConversationQueryFindsBothDirections() = runBlocking {
        val inbound = db.messageDao().insert(msg("rx", "aa", "in"))
        val outbound = db.messageDao().insert(msg("tx", "aa", "out"))
        val other = db.messageDao().insert(msg("rx", "bb", "other"))
        db.insertAttachments(inbound, listOf(audio(100)))
        db.insertAttachments(outbound, listOf(image(200)))
        db.insertAttachments(other, listOf(audio(300)))
        val rows = db.messageAttachmentDao().forConversation("aa").first()
        assertEquals(listOf(inbound, outbound), rows.map { it.messageId })
        assertEquals(listOf(100, 200), rows.map { it.size })
    }

    /** The version-22 schema is today's without message_attachments; it upgrades and keeps its messages. */
    @Test
    fun aVersion22DatabaseUpgradesTo23AndKeepsItsMessages() = runBlocking {
        db.messageDao().insert(msg("rx", "aa", "before the upgrade"))
        val path = context.getDatabasePath(name).path
        db.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE message_attachments")
            it.version = 22
        }
        db = AppDatabase.build(context, name)
        assertEquals(23, db.openHelper.readableDatabase.version)
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE text = 'before the upgrade'"))
        val id = db.messageDao().insert(msg("rx", "aa", "after"))
        assertEquals(1, db.insertAttachments(id, listOf(audio(10))).size)
        assertEquals(1, db.messageAttachmentDao().forMessageOnce(id).size)
    }
}
