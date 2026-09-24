package net.meshsat.android

import net.meshsat.android.sms.SmsCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two editions (MESHSAT-1335): the play flavor has no SMS, whatever the phone can do. This
 * runs under both testFdroidDebugUnitTest and testPlayDebugUnitTest, each with its own BuildConfig.
 */
class SmsEditionTest {
    @Test
    fun `the gate follows the flavor`() {
        assertEquals(BuildConfig.SMS_INCLUDED, SmsCapability.included)
        when (BuildConfig.FLAVOR) {
            "fdroid" -> assertTrue("the fdroid edition is the full app", SmsCapability.included)
            "play" -> assertFalse("the play edition has no SMS", SmsCapability.included)
            else -> fail("unknown flavor ${BuildConfig.FLAVOR}")
        }
    }
}
