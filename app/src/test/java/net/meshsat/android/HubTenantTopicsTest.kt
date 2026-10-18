package net.meshsat.android

import net.meshsat.android.hub.HubTopics
import net.meshsat.android.hub.isOwnTakExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tenant's topic root (MESHSAT-1467, HeMB-NG contract C13), as the Bridge's hubreporter.Topics:
 * a phone provisioned into an organisation publishes and subscribes under meshsat/<tenant>, where
 * the broker confines its credential, and the platform tenant's topics are unchanged.
 */
class HubTenantTopicsTest {

    @Test
    fun `a bundle's topic root is meshsat or meshsat slash tenant and nothing else`() {
        for ((prefix, root) in mapOf("" to "meshsat", "meshsat" to "meshsat", " meshsat/acme " to "meshsat/acme", "meshsat/t-1_x" to "meshsat/t-1_x")) {
            assertEquals(prefix, root, HubTopics.of(prefix)?.root)
        }
        for (bad in listOf(
            "acme", "meshsat/", "meshsat/acme/x", "meshsat/+", "meshsat/#", "meshsat/a.b", "meshsat/a b",
            "meshsat/bridge", "meshsat/broadcast", "meshsat/hub", "meshsat/relay", "meshsat/reticulum",
            "other/acme", "meshsat/" + "a".repeat(65),
        )) {
            assertNull(bad, HubTopics.of(bad))
        }
    }

    @Test
    fun `a tenant's topics are all under its root`() {
        val t = HubTopics.of("meshsat/acme")!!
        val all = listOf(
            t.bridgeBirth("pixel"), t.bridgeDeath("pixel"), t.bridgeHealth("pixel"), t.bridgeCmd("pixel"),
            t.bridgeCmdResponse("pixel"), t.bridgeMOAck("pixel"), t.deviceBirth("pixel", "d"), t.deviceDeath("pixel", "d"),
            t.devicePosition("d"), t.deviceTelemetry("d"), t.deviceSOS("d"), t.deviceMODecoded("d"), t.takCotOut("pixel"),
        ) + t.takCotInFilters()
        for (topic in all) assertTrue(topic, topic.startsWith("meshsat/acme/"))
        assertEquals("meshsat/acme/bridge/pixel/cmd", t.bridgeCmd("pixel"))
        assertEquals(listOf("meshsat/acme/broadcast/tak/cot/in", "meshsat/acme/broadcast/tak/cot/in/+"), t.takCotInFilters().toList())
        // The phone's own TAK export comes back on the tenant's delivery topic and is dropped there.
        assertTrue(isOwnTakExport("meshsat/acme/broadcast/tak/cot/in/pixel", "pixel", t))
        assertTrue(!isOwnTakExport("meshsat/broadcast/tak/cot/in/pixel", "pixel", t))
    }

    @Test
    fun `the platform tenant's topics are unchanged`() {
        assertEquals("meshsat/bridge/pi-01/birth", HubTopics.PLATFORM.bridgeBirth("pi-01"))
        assertEquals("meshsat/dev-01/position", HubTopics.PLATFORM.devicePosition("dev-01"))
        assertEquals("meshsat/broadcast/tak/cot/in", HubTopics.PLATFORM.takCotIn)
    }
}
