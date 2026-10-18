package net.meshsat.android

import net.meshsat.android.sms.SmsReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** A delivery report's TP-Status decides the second tick on an SMS (MESHSAT-1246). */
class SmsReportTest {
    @Test
    fun `only a completed report is a delivery`() {
        assertEquals(SmsReport.Delivered, SmsReport.fromStatus(0x00))
        // 0x01 forwarded without confirmation, 0x02 replaced by the SC: completed, but neither says
        // the phone got it (3GPP TS 23.040 9.2.3.15), so not delivered (MESHSAT-1584).
        assertEquals(SmsReport.CompletedUnconfirmed, SmsReport.fromStatus(0x01))
        assertEquals(SmsReport.CompletedUnconfirmed, SmsReport.fromStatus(0x02))
        assertEquals(SmsReport.CompletedUnconfirmed, SmsReport.fromStatus(0x1F))
        assertEquals(SmsReport.Pending, SmsReport.fromStatus(0x20)) // congestion, the carrier keeps trying
        assertEquals(SmsReport.Pending, SmsReport.fromStatus(0x3F))
        assertEquals(SmsReport.Failed, SmsReport.fromStatus(0x40))
        assertEquals(SmsReport.Failed, SmsReport.fromStatus(0x65))
        assertEquals(SmsReport.Pending, SmsReport.fromStatus(-1))
    }
}
