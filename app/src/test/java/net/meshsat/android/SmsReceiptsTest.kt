package net.meshsat.android

import kotlinx.coroutines.runBlocking
import net.meshsat.android.data.MessageDao
import net.meshsat.android.data.MessageDeliveryDao
import net.meshsat.android.data.SmsPartReport
import net.meshsat.android.data.SmsPartReportDao
import net.meshsat.android.data.SmsSend
import net.meshsat.android.sms.SmsPartKey
import net.meshsat.android.sms.SmsReceipts
import net.meshsat.android.sms.SmsReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * What a delivery report writes, in order (review 2026-10-18 19:34, FE-R2, MESHSAT-1584): the
 * evidence, then the outcome, a failure only through the update that never overwrites a delivered
 * message. The transaction itself is SmsReceiptRoomTest's, on a device.
 */
class SmsReceiptsTest {

    private val calls = Collections.synchronizedList(mutableListOf<String>())

    private inline fun <reified T> recorder(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            calls.add("${method.name}:${args?.dropLast(1)?.joinToString(",")}")
            if (method.returnType == Integer.TYPE) 0 else Unit
        } as T

    private class Parts(val send: SmsSend?) : SmsPartReportDao {
        val rows = mutableListOf<SmsPartReport>()
        override suspend fun partsOf(attempt: String) = rows.filter { it.attempt == attempt }
        override suspend fun put(report: SmsPartReport) { rows.removeAll { it.part == report.part }; rows += report }
        override suspend fun pruneBefore(before: Long) = 0
        override suspend fun register(send: SmsSend) = 1L
        override suspend fun sendOf(attempt: String) = send?.takeIf { it.attempt == attempt }
        override suspend fun pruneSendsBefore(before: Long) = 0
    }

    private fun apply(parts: Parts, key: SmsPartKey?, r: SmsReport) = runBlocking {
        SmsReceipts.project(parts, recorder<MessageDao>(), recorder<MessageDeliveryDao>(), key, r, 5, 9, 1_000L) { calls.add("evidence written") }
    }

    @Test
    fun `a delivered send writes the evidence, then the message and the delivery`() {
        apply(Parts(SmsSend("a", 1, 5, 9, 1_000L)), SmsPartKey("a", 0, 1), SmsReport.Delivered)
        assertEquals(listOf("evidence written", "setForwardedTo:5,sms:delivered", "setAcked:9,1000"), calls.toList())
    }

    @Test
    fun `a failure never writes over a delivered message`() {
        apply(Parts(SmsSend("a", 1, 5, 9, 1_000L)), SmsPartKey("a", 0, 1), SmsReport.Failed)
        apply(Parts(null), null, SmsReport.Failed)
        assertEquals(
            listOf("evidence written", "setForwardedToUnlessDelivered:5,sms:failed", "evidence written", "setForwardedToUnlessDelivered:5,sms:failed"),
            calls.toList(),
        )
    }
}
