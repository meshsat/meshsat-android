package net.meshsat.android

import net.meshsat.android.data.EmergencyContact
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.sos.SosProgress
import net.meshsat.android.sos.SosRouteStatus.State
import net.meshsat.android.sos.SosRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where each route of an SOS stands, and the keys that tie its deliveries to it (MESHSAT-1249). */
class SosRunTest {

    private fun run(test: Boolean = false, hubWanted: Boolean = true, hubSentAt: Long? = null, cancelledAt: Long? = null) = SosRun(
        id = 1_000L, test = test, trigger = "button", name = "flaneur", fix = null,
        routes = listOf(SosRun.Route("sat", "Satellite, to the Hub"), SosRun.Route("sms:+31600000000", "SMS to Anna")),
        skipped = emptyList(), hubWanted = hubWanted, deviceId = "300434067943980",
        hubAlertId = "sos-sbd-msa-flaneur-1", hubSentAt = hubSentAt, cancelledAt = cancelledAt,
    )

    private fun del(ref: String, status: String, lastError: String = "", retries: Int = 0) =
        MessageDeliveryEntity(msgRef = ref, channel = "x", status = status, lastError = lastError, retries = retries)

    @Test
    fun `each route reads its own delivery and the Hub its own link`() {
        val statuses = SosProgress.routes(
            run(),
            listOf(del("sos:1000:sat", "retry", "Not sent: status 32, no network", 1), del("sos:1000:sms:+31600000000", "sent")),
        )
        assertEquals(listOf(State.Waiting, State.Sent, State.Waiting), statuses.map { it.state })
        assertTrue(statuses[0].detail.contains("status 32"))
        assertEquals("Hub, over the internet", statuses[2].label)
    }

    @Test
    fun `a cancelled delivery is stopped, not failed`() {
        val statuses = SosProgress.routes(run(hubWanted = false), listOf(del("sos:1000:sat", "dead", "cancelled"), del("sos:1000:sms:+31600000000", "dead", "SMS failed")))
        assertEquals(listOf(State.Stopped, State.Failed), statuses.map { it.state })
    }

    @Test
    fun `after a cancel each route shows its cancellation`() {
        val statuses = SosProgress.routes(
            run(hubWanted = false, cancelledAt = 2_000L),
            listOf(del("sos:1000:sat", "sent"), del("sos:1000:cancel:sat", "queued")),
        )
        assertEquals(State.Waiting, statuses[0].cancel)
        assertNull(statuses[1].cancel)
    }

    @Test
    fun `refs name the run and whether it is a cancellation`() {
        assertEquals(1_000L to false, SosRun.parseRef("sos:1000:sms:+31600000000"))
        assertEquals(1_000L to true, SosRun.parseRef("sos:1000:cancel:sat"))
        assertNull(SosRun.parseRef("msg:42"))
        assertNull(SosRun.parseRef("sos:x:sat"))
    }

    @Test
    fun `a test is settled only when every route and the Hub are done`() {
        val r = run(test = true)
        val dels = listOf(del("sos:1000:sat", "sent"), del("sos:1000:sms:+31600000000", "sent"))
        assertFalse(SosProgress.allSettled(r, SosProgress.routes(r, dels)))
        val told = r.copy(hubSentAt = 1_500L)
        assertTrue(SosProgress.allSettled(told, SosProgress.routes(told, dels)))
    }

    @Test
    fun `the notification line says what went and what is still trying`() {
        val line = SosProgress.summary(SosProgress.routes(run(hubSentAt = 1_500L), listOf(del("sos:1000:sat", "retry"), del("sos:1000:sms:+31600000000", "sent"))))
        assertEquals("Sent by SMS to Anna and the Hub online. Still trying satellite.", line)
    }

    @Test
    fun `phone numbers are cleaned or refused`() {
        assertEquals("+31612345678", EmergencyContact.normalisePhone(" +31 (6) 12-34.56 78 "))
        assertEquals("112", EmergencyContact.normalisePhone("112"))
        assertNull(EmergencyContact.normalisePhone("+31 6 CALL ME"))
        assertNull(EmergencyContact.normalisePhone("12"))
        assertNull(EmergencyContact.normalisePhone("+1234567890123456"))
    }

    @Test
    fun `contacts survive their own storage format`() {
        val list = listOf(EmergencyContact("Anna\tB", "+31612345678"), EmergencyContact("", "0612345678"))
        assertEquals(
            listOf(EmergencyContact("Anna B", "+31612345678"), EmergencyContact("", "0612345678")),
            EmergencyContact.decode(EmergencyContact.encode(list)),
        )
    }
}
