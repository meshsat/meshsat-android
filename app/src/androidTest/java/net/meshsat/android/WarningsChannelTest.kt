package net.meshsat.android

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.meshsat.android.data.WarningEntity
import net.meshsat.android.warnings.WarningNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The warning channels as the app creates them at start (MESHSAT-1734): "Warnings" at high
 * importance with the alarm sound on the alarm stream and vibration; "Warning tests and
 * all-clears" at high importance with the default sound; a warning's notification id follows its
 * event so a cancel finds the alarm, and a test keeps its own.
 */
@RunWith(AndroidJUnit4::class)
class WarningsChannelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun theWarningsChannelIsAnAlarm() {
        WarningNotifier.createChannels(manager)
        val ch = manager.getNotificationChannel(WarningNotifier.CHANNEL_WARNINGS)
        assertNotNull(ch)
        assertEquals("Warnings", ch!!.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), ch.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, ch.audioAttributes.usage)
        assertTrue(ch.shouldVibrate())
        assertTrue(ch.vibrationPattern.contentEquals(WarningNotifier.VIBRATION))
        assertTrue(ch.shouldShowLights())
    }

    @Test
    fun theTestsChannelUsesTheNormalSound() {
        WarningNotifier.createChannels(manager)
        val ch = manager.getNotificationChannel(WarningNotifier.CHANNEL_WARNING_TESTS)
        assertNotNull(ch)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch!!.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), ch.sound)
        assertNotEquals(AudioAttributes.USAGE_ALARM, ch.audioAttributes?.usage ?: AudioAttributes.USAGE_NOTIFICATION)
        assertTrue(ch.shouldVibrate())
    }

    @Test
    fun aWarningAndItsCancelShareAnIdAndATestKeepsItsOwn() {
        val real = WarningEntity(receivedAt = 1, port = 11, sender = "!1", senderName = "", kind = "alert", test = false, event = "Wind",
            severity = "Severe", until = "10:11", text = "x", label = "", raw = "", dedupKey = "")
        val cancel = real.copy(kind = "cancel", severity = "", until = "")
        val test = real.copy(test = true)
        assertEquals(WarningNotifier.idFor(real.event, real.test), WarningNotifier.idFor(cancel.event, cancel.test))
        assertNotEquals(WarningNotifier.idFor(real.event, false), WarningNotifier.idFor(test.event, true))
        assertNotEquals(WarningNotifier.idFor("Wind", false), WarningNotifier.idFor("Rain", false))
        assertEquals(WarningNotifier.CHANNEL_WARNINGS, WarningNotifier.channelFor(real))
        assertEquals(WarningNotifier.CHANNEL_WARNING_TESTS, WarningNotifier.channelFor(test))
        assertEquals(WarningNotifier.CHANNEL_WARNING_TESTS, WarningNotifier.channelFor(cancel))
        assertEquals("WARNING Wind Severe until 10:11 UTC", WarningNotifier.title(real))
        assertEquals("TEST WARNING Wind Severe until 10:11 UTC", WarningNotifier.title(test))
        assertEquals("CANCELLED Wind", WarningNotifier.title(cancel))
        assertEquals("Relay of !1", WarningNotifier.relayLine(real))
        assertEquals("Relay of tesseract (!1)", WarningNotifier.relayLine(real.copy(senderName = "tesseract")))
    }
}
