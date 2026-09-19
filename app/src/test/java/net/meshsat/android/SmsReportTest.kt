package net.meshsat.android

import net.meshsat.android.sms.SmsReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** A delivery report's TP-Status decides the second tick on an SMS (MESHSAT-1246). */
class SmsReportTest {
    @Test
    fun `only a completed report is a delivery`() {
        assertEquals(SmsReport.Delivered, SmsReport.fromStatus(0x00))
        assertEquals(SmsReport.Delivered, SmsReport.fromStatus(0x02)) // replaced by the SC, still received
        assertEquals(SmsReport.Pending, SmsReport.fromStatus(0x20)) // congestion, the carrier keeps trying
        assertEquals(SmsReport.Pending, SmsReport.fromStatus(0x3F))
        assertEquals(SmsReport.Failed, SmsReport.fromStatus(0x40))
        assertEquals(SmsReport.Failed, SmsReport.fromStatus(0x65))
        assertEquals(SmsReport.Pending, SmsReport.fromStatus(-1))
    }
}
