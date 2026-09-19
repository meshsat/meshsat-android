package net.meshsat.android.sms

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager

/**
 * Whether this device can send SMS at all. FEATURE_TELEPHONY_MESSAGING exists only from Android 13
 * (API 33): asked on Android 8 to 12 it is always false, so the app told every such phone it could
 * not send SMS (seen on the API 30 emulator, MESHSAT-1249). Before 13, telephony means SMS.
 */
object SmsCapability {
    fun canSend(context: Context): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }
    }

    /**
     * The SmsManager. Context.getSystemService returns one only from Android 12 (API 31); before
     * that it answers null, and every send from the app failed on Android 8 to 11 (MESHSAT-1249).
     */
    fun manager(context: Context): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
}
