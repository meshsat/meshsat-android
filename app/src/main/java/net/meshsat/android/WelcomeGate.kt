package net.meshsat.android

/**
 * Which permissions the welcome asks for at a start: the ones not granted that it has never asked
 * for. A permission declined on the welcome is not asked again on every cold start (F-Droid's
 * device test of 2.23.0 saw the welcome and Android's three questions on each start after a denial,
 * MESHSAT-1752); the rows under Setup and Android's own settings page remain the way to allow it
 * later. A permission a newer version adds is asked once, because it was never asked before.
 */
object WelcomeGate {
    /** The SharedPreferences file and the key of the set of permissions the welcome has asked for. */
    const val PREFS = "welcome"
    const val KEY_ASKED = "asked"

    /**
     * The permissions to explain and ask for now, or null when there is nothing new to ask:
     * [missing] are the required permissions not granted, [asked] those the welcome asked before.
     */
    fun toAsk(missing: List<String>, asked: Set<String>): List<String>? =
        missing.filter { it !in asked }.ifEmpty { null }
}
