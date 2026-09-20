package net.meshsat.android

import net.meshsat.android.ui.Words
import net.meshsat.android.ui.screens.ruleLinkChoices
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the rule editor offers, and what it calls it (MESHSAT-1281).
 *
 * On 20 September the list showed "hub_0" and "Hub" side by side. "Hub" was mqtt_0, a link that
 * is switched off on every phone and holds whatever is routed to it for ever; the link that
 * reaches the Hub had no name at all. The obvious choice was the one that delivered nothing.
 */
class RuleLinkChoicesTest {

    @Test
    fun `the link that reaches the Hub is the one called Hub`() {
        assertEquals("Hub", Words.channel("hub_0"))
        assertEquals("Hub relay", Words.channel("hub_relay"))
        assertEquals("MQTT broker", Words.channel("mqtt_0"))
    }

    @Test
    fun `no two links share a name`() {
        val ids = listOf(
            "mesh_0", "iridium_0", "iridium9704_0", "sms_0", "hub_0", "hub_relay", "mqtt_0", "aprs_0",
        )
        val names = ids.map { Words.channel(it) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `a switched-off link is not offered`() {
        val all = listOf("mesh_0" to false, "mqtt_0" to true, "hub_0" to false, "aprs_0" to true)
        assertEquals(listOf("mesh_0", "hub_0"), ruleLinkChoices(all, keep = ""))
    }

    @Test
    fun `the link a saved rule already names stays, switched off or not`() {
        val all = listOf("mesh_0" to false, "mqtt_0" to true, "hub_0" to false)
        assertEquals(listOf("mesh_0", "mqtt_0", "hub_0"), ruleLinkChoices(all, keep = "mqtt_0"))
    }
}
