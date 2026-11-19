package net.meshsat.android.age

import androidx.activity.ComponentActivity

/**
 * The fdroid edition never asks for an age signal: it has no Google Play services, and F-Droid
 * builds carry no Play library. The Google Play edition's twin lives in src/play (MESHSAT-1908).
 */
object AgeSignals {
    /** False: this edition does not call the Play Age Signals API. */
    const val INCLUDED = false

    @Suppress("UNUSED_PARAMETER")
    fun onLaunch(activity: ComponentActivity) = Unit
}
