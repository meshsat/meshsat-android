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

    @Test
    fun `a report is read in its own format`() {
        assertEquals(SmsReport.Delivered, SmsReport.from("3gpp", 0x00))
        assertEquals(SmsReport.Failed, SmsReport.from("3gpp", 0x40))
        assertEquals(SmsReport.Delivered, SmsReport.from(null, 0x00)) // no format extra: GSM, as before
        // CDMA: error class in bits 25-24, status code in bits 23-16; 2 << 16 is the receipt
        // (SmsMessage.getStatus, 3GPP2 C.S0015-B 4.5.21). The GSM decoder called it a failure.
        assertEquals(SmsReport.Delivered, SmsReport.from("3gpp2", 2 shl 16))
        assertEquals(SmsReport.Pending, SmsReport.from("3gpp2", 0)) // class 0 code 0: accepted, not delivered
        assertEquals(SmsReport.Pending, SmsReport.from("3gpp2", 3 shl 16)) // class 0 code 3: cancelled, not final here
        assertEquals(SmsReport.Pending, SmsReport.from("3gpp2", (2 shl 24) or (2 shl 16))) // temporary error
        assertEquals(SmsReport.Failed, SmsReport.from("3gpp2", (3 shl 24) or (0x07 shl 16))) // permanent error
        assertEquals(null, SmsReport.from("ims", 0x00)) // a format we cannot read says nothing
    }
}
