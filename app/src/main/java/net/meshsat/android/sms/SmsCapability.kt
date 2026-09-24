package net.meshsat.android.sms

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import net.meshsat.android.BuildConfig

/**
 * Whether this edition and this phone can send SMS at all. Everything that would send a text, or
 * offer to, asks here first.
 *
 * Two editions of the app exist since 2.19.0 (MESHSAT-1335): `fdroid`, the full app that GitHub
 * releases and F-Droid ship, and `play`, the Google Play edition, which has no SMS because Play
 * does not allow SEND_SMS or RECEIVE_SMS in an app that is not the phone's SMS app. The play
 * manifest declares neither permission and registers no SMS receiver, and [included] is false
 * there, so nothing in that edition ever reaches SmsManager.
 *
 * On the phone side, FEATURE_TELEPHONY_MESSAGING exists only from Android 13 (API 33): asked on
 * Android 8 to 12 it is always false, so the app told every such phone it could not send SMS
 * (seen on the API 30 emulator, MESHSAT-1249). Before 13, telephony means SMS.
 */
object SmsCapability {
    /** False in the Google Play edition, whatever the phone can do. */
    val included: Boolean = BuildConfig.SMS_INCLUDED

    const val NOT_IN_THIS_EDITION = "SMS is not part of the Google Play edition."
    const val NO_TELEPHONY = "This phone cannot send SMS."

    fun canSend(context: Context): Boolean {
        if (!included) return false
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }
    }

    /** Why [canSend] is false, in words for the screen; null when SMS works here. */
    fun unavailableReason(context: Context): String? = when {
        !included -> NOT_IN_THIS_EDITION
        !canSend(context) -> NO_TELEPHONY
        else -> null
    }

    /**
     * The SmsManager, or null where SMS cannot be sent. Context.getSystemService returns one only
     * from Android 12 (API 31); before that it answers null, and every send from the app failed on
     * Android 8 to 11 (MESHSAT-1249).
     */
    fun manager(context: Context): SmsManager? = when {
        !included -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> context.getSystemService(SmsManager::class.java)
        else -> @Suppress("DEPRECATION") SmsManager.getDefault()
    }
}
