package net.meshsat.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.Message
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.sms.SmsCompletion
import net.meshsat.android.sms.SmsPartKey
import net.meshsat.android.sms.SmsPartLedger
import net.meshsat.android.sms.SmsReceipt
import net.meshsat.android.sms.SmsReceipts
import net.meshsat.android.sms.SmsReport
import net.meshsat.android.sms.SmsStatusReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SMS delivery reports against the app's real Room database on a device (review 2026-10-18 19:34,
 * FE-R2 and FE-R3, MESHSAT-1584): a part's evidence and the outcome it leads to are one
 * transaction, outcomes only rise, and a send's part count and owner are fixed when it is
 * registered. The database is a file, closed and opened again where a test says restart.
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiptRoomTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "sms-receipt-test.db"
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

    private fun forwardedTo(id: Long): String =
        db.openHelper.readableDatabase.query("SELECT forwardedTo FROM messages WHERE id = ?", arrayOf<Any>(id)).use {
            it.moveToFirst()
            it.getString(0)
        }

    private fun deliveryStatus(id: Long): String =
        db.openHelper.readableDatabase.query("SELECT status FROM message_deliveries WHERE id = ?", arrayOf<Any>(id)).use {
            it.moveToFirst()
            it.getString(0)
        }

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }

    /** A chat message and a queued delivery sent as one [parts]-part SMS, registered as [attempt]. */
    private suspend fun sent(attempt: String, parts: Int): Pair<Long, Long> {
        val m = db.messageDao().insert(Message(transport = "sms", direction = "tx", sender = "self", text = "x", forwardedTo = SmsStatusReceiver.SENT))
        val d = db.messageDeliveryDao().insert(MessageDeliveryEntity(msgRef = "msg:$m", channel = "sms_0", textPreview = "x", status = "sent"))
        SmsPartLedger.register(db.smsPartReportDao(), attempt, parts, SmsReceipt(m, d), now)
        return m to d
    }

    private suspend fun report(attempt: String, part: Int, parts: Int, r: SmsReport, ids: Pair<Long, Long>) =
        SmsReceipts.apply(db, SmsPartKey(attempt, part, parts), r, ids.first, ids.second, now)

    @Test
    fun everyPartDeliveredIsDeliveredAndSurvivesARestart() = runBlocking {
        val ids = sent("a", 2)
        assertEquals(SmsCompletion.Open, report("a", 1, 2, SmsReport.Delivered, ids))
        assertEquals(SmsStatusReceiver.SENT, forwardedTo(ids.first))
        restart()
        assertEquals(SmsCompletion.Delivered, report("a", 0, 2, SmsReport.Delivered, ids))
        restart()
        assertEquals(SmsStatusReceiver.DELIVERED, forwardedTo(ids.first))
        assertEquals("delivered", deliveryStatus(ids.second))
    }

    @Test
    fun aDelayedFailureNeverTakesBackADeliveredMessage() = runBlocking {
        val ids = sent("a", 2)
        report("a", 0, 2, SmsReport.Delivered, ids)
        report("a", 1, 2, SmsReport.Delivered, ids)
        assertEquals(SmsCompletion.Delivered, report("a", 1, 2, SmsReport.Failed, ids))
        // an old-format report (no send id) and another send of the same message fail nothing either
        assertEquals(SmsCompletion.Failed, SmsReceipts.apply(db, null, SmsReport.Failed, ids.first, ids.second, now))
        SmsPartLedger.register(db.smsPartReportDao(), "b", 1, SmsReceipt(ids.first, ids.second), now)
        assertEquals(SmsCompletion.Failed, report("b", 0, 1, SmsReport.Failed, ids))
        assertEquals(SmsStatusReceiver.DELIVERED, forwardedTo(ids.first))
        assertEquals("delivered", deliveryStatus(ids.second))
    }

    @Test
    fun aFailureWaitingInsideItsTransactionCannotLetADeliveryInBetween() = runBlocking {
        val ids = sent("a", 2)
        report("a", 1, 2, SmsReport.Delivered, ids)
        val failureInside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A: part 0 failed; it stops after writing its evidence, before its outcome
        val a = async(Dispatchers.IO) {
            SmsReceipts.apply(db, SmsPartKey("a", 0, 2), SmsReport.Failed, ids.first, ids.second, now) {
                failureInside.complete(Unit)
                release.await()
            }
        }
        withTimeout(10_000) { failureInside.await() }
        // B: part 0 delivered, while A holds its transaction
        val b = async(Dispatchers.IO) { report("a", 0, 2, SmsReport.Delivered, ids) }
        delay(500)
        assertFalse("a second report committed inside another report's transaction", b.isCompleted)
        release.complete(Unit)
        assertEquals(SmsCompletion.Failed, a.await())
        assertEquals(SmsCompletion.Delivered, b.await())
        assertEquals(SmsStatusReceiver.DELIVERED, forwardedTo(ids.first))
        assertEquals("delivered", deliveryStatus(ids.second))
    }

    @Test
    fun aReportStoppedBetweenEvidenceAndOutcomeLeavesNeither() = runBlocking {
        val ids = sent("a", 1)
        val stopped = runCatching {
            SmsReceipts.apply(db, SmsPartKey("a", 0, 1), SmsReport.Delivered, ids.first, ids.second, now) {
                error("process stopped")
            }
        }
        assertEquals("process stopped", stopped.exceptionOrNull()?.message)
        restart()
        assertEquals(0, count("sms_part_reports"))
        assertEquals(SmsStatusReceiver.SENT, forwardedTo(ids.first))
        assertEquals("sent", deliveryStatus(ids.second))
    }

    @Test
    fun anotherPartCountOrOwnerLeavesTheEvidenceAndCompletesNothing() = runBlocking {
        val ids = sent("a", 2)
        report("a", 0, 2, SmsReport.Delivered, ids)
        assertEquals(SmsCompletion.Open, report("a", 0, 1, SmsReport.Delivered, ids))
        assertEquals(SmsCompletion.Open, report("a", 2, 3, SmsReport.Delivered, ids))
        assertEquals(SmsCompletion.Open, report("a", 1, 2, SmsReport.Delivered, ids.first to ids.second + 99))
        assertEquals(1, count("sms_part_reports"))
        assertEquals(SmsStatusReceiver.SENT, forwardedTo(ids.first))
        assertEquals(SmsCompletion.Delivered, report("a", 1, 2, SmsReport.Delivered, ids))
    }
}
