package net.meshsat.android

import net.meshsat.android.ui.screens.duplicatesTheHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warning shown while someone writes a rule that hands the Hub something it already has
 * (MESHSAT-1276).
 *
 * A RockBLOCK's own message reaches the Hub through the provider's webhook, so forwarding
 * satellite traffic there delivers a second copy under a different message id - two TAK
 * events, two webhook posts, two notifications for one message. Mesh and SMS are the
 * opposite: the Hub has no other copy, which is the whole point of forwarding them, and a
 * warning there would train people to ignore warnings.
 */
class DuplicatesTheHubTest {

    @Test
    fun `satellite to the Hub duplicates what the provider already sends`() {
        assertTrue(duplicatesTheHub("iridium_0", "hub_0"))
        assertTrue(duplicatesTheHub("iridium9704_0", "hub_0"))
    }

    @Test
    fun `mesh and SMS to the Hub are the point of the feature`() {
        assertFalse(duplicatesTheHub("mesh_0", "hub_0"))
        assertFalse(duplicatesTheHub("sms_0", "hub_0"))
        assertFalse(duplicatesTheHub("aprs_0", "hub_0"))
    }

    @Test
    fun `satellite anywhere else is not the Hub's business`() {
        assertFalse(duplicatesTheHub("iridium_0", "sms_0"))
        assertFalse(duplicatesTheHub("iridium_0", "mesh_0"))
        assertFalse(duplicatesTheHub("iridium_0", "hub_relay"))
    }
}
