package net.meshsat.android.location

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Build

/**
 * The phone's position from every provider it has: satellite GPS, cell/Wi-Fi ("network",
 * when the user enabled it) and, from Android 12, the platform's fused provider. GPS works
 * with no network at all; the others are faster indoors.
 */
object LocationFixes {

    fun providers(): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }

    /** The most recent last-known fix of any provider: a days-old GPS fix no longer wins. */
    @SuppressLint("MissingPermission")
    fun freshest(lm: LocationManager): Location? =
        providers().mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }

    /**
     * Whether [candidate] should replace [current]: a fix two minutes newer always does,
     * otherwise only a more accurate one, so a coarse cell fix cannot overwrite a GPS fix.
     */
    fun isBetter(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        val newer = candidate.time - current.time
        if (newer > 120_000) return true
        if (newer < -120_000) return false
        return !current.hasAccuracy() || (candidate.hasAccuracy() && candidate.accuracy <= current.accuracy)
    }
}
