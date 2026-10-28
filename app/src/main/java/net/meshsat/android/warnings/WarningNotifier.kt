package net.meshsat.android.warnings

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.meshsat.android.MainActivity
import net.meshsat.android.R
import net.meshsat.android.data.WarningEntity

/**
 * The notification a relayed warning raises (MESHSAT-1734).
 *
 * Two channels, because a channel's sound is fixed once it exists:
 * - [CHANNEL_WARNINGS], "Warnings": the phone's alarm sound on the alarm stream, vibration, lights,
 *   high importance. Do Not Disturb is respected as the platform does for alarms: a DND mode that
 *   lets alarms through lets this through; [NotificationChannel.setBypassDnd] is asked for and
 *   honoured once the person grants the app Do Not Disturb access, never before.
 * - [CHANNEL_WARNING_TESTS], "Warning tests and all-clears": a `TEST` warning and a cancel, with
 *   the normal notification sound.
 *
 * A warning's notification id follows its event, so the cancel of that event takes the alarm down.
 */
object WarningNotifier {

    const val CHANNEL_WARNINGS = "meshsat_warnings"
    const val CHANNEL_WARNING_TESTS = "meshsat_warning_tests"

    /** The route a tap opens. */
    const val ROUTE = "warnings"

    private const val ID_BASE = 7700
    private const val ID_SPAN = 200

    fun createChannels(manager: NotificationManager) {
        val alarm = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNINGS, "Warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Public warnings a MeshSat kit relayed over the mesh: alarm sound and vibration"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm)
                enableVibration(true)
                vibrationPattern = VIBRATION
                enableLights(true)
                lightColor = Color.RED
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNING_TESTS, "Warning tests and all-clears", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A test warning, or a warning taken back, with the normal notification sound"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    val VIBRATION = longArrayOf(0, 600, 250, 600, 250, 600)

    /** The notification id of the warnings of one event; a test keeps its own. */
    fun idFor(event: String, test: Boolean): Int =
        ID_BASE + (Math.floorMod(event.lowercase().hashCode(), ID_SPAN / 2)) + if (test) ID_SPAN / 2 else 0

    fun channelFor(w: WarningEntity): String = if (w.test || w.isCancel) CHANNEL_WARNING_TESTS else CHANNEL_WARNINGS

    /** The first line of a notification or a banner: "WARNING Wind Severe until 10:11 UTC". */
    fun title(w: WarningEntity): String =
        (WarningText.kindLabel(WarningText.Kind.of(w.kind), w.test) + " " + WarningText.summary(w.event, w.severity, w.until)).trim()

    /** "Relay of Node tesseract (!4370c1d8)": who put it on the mesh. */
    fun relayLine(w: WarningEntity): String =
        "Relay of " + (if (w.senderName.isNotBlank()) "${w.senderName} (${w.sender})" else w.sender)

    /** A warning stands: alarm. */
    fun alarm(context: Context, w: WarningEntity) {
        val text = listOf(w.text, w.label).filter { it.isNotBlank() }.joinToString("\n") + "\n" + relayLine(w)
        post(context, idFor(w.event, w.test), w, title(w), text, ongoing = false)
    }

    /** A cancel: the alarms of what it cleared go, and the all-clear is said once, quietly. */
    fun cleared(context: Context, cancel: WarningEntity, cleared: List<WarningEntity>) {
        val nm = NotificationManagerCompat.from(context)
        cleared.forEach { nm.cancel(idFor(it.event, it.test)) }
        if (cleared.isEmpty()) nm.cancel(idFor(cancel.event, cancel.test))
        val text = listOf(cancel.text, cancel.label).filter { it.isNotBlank() }.joinToString("\n") + "\n" + relayLine(cancel)
        post(context, idFor(cancel.event, cancel.test), cancel, title(cancel), text.trim(), ongoing = false)
    }

    private fun post(context: Context, id: Int, w: WarningEntity, title: String, text: String, ongoing: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, ROUTE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alarm = !w.test && !w.isCancel
        val builder = NotificationCompat.Builder(context, channelFor(w))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(if (alarm) 0xFFF87171.toInt() else 0xFFFBBF24.toInt())
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(ongoing)
            .setContentIntent(open)
        if (alarm) builder.setVibrate(VIBRATION)
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
            // Notifications not allowed: the banner in the app still shows it.
        }
    }
}
