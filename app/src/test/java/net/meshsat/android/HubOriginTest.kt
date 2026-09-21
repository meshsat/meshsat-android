package net.meshsat.android

import net.meshsat.android.engine.HubOrigin
import net.meshsat.android.hub.HubTopics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The id on a message passed on to the Hub names who sent it (MESHSAT-1274). On 20 Sep an SMS from
 * a person was filed at the Hub under the RockBLOCK's IMEI: the modem was attached, so its number
 * went on everything.
 */
class HubOriginTest {

    private val imei = "300434000000000"
    private val bridge = "msa-flaneur"

    @Test
    fun `a text from a person is that person's, not the modem's`() {
        assertEquals("+31600000000", HubOrigin.deviceIdFor("sms_0", "+31600000000", imei, bridge))
    }

    @Test
    fun `a mesh message is the sending node's, as the Bridge does it`() {
        assertEquals("!4370c1d8", HubOrigin.deviceIdFor("mesh_0", "!4370c1d8", imei, bridge))
    }

    @Test
    fun `what the modem carried keeps the modem's name`() {
        assertEquals(imei, HubOrigin.deviceIdFor("iridium_0", imei, imei, bridge))
        assertEquals(imei, HubOrigin.deviceIdFor("iridium9704_0", "", imei, bridge))
    }

    @Test
    fun `written on this phone, it is this phone's`() {
        assertEquals(bridge, HubOrigin.deviceIdFor("", "", imei, bridge))
    }

    @Test
    fun `an unknown sender falls to the gateway, never to the modem`() {
        assertEquals(bridge, HubOrigin.deviceIdFor("sms_0", "", imei, bridge))
        assertEquals(bridge, HubOrigin.deviceIdFor("mesh_0", "someone", imei, bridge))
        assertEquals(bridge, HubOrigin.deviceIdFor("mesh_0", "  ", imei, bridge))
    }

    @Test
    fun `with no bridge id at all there is still an id`() {
        assertEquals(imei, HubOrigin.deviceIdFor("", "", imei, ""))
    }

    @Test
    fun `a phone number never reaches a topic with its plus sign`() {
        val topic = HubTopics.deviceMODecoded(HubTopics.segment("+31600000000"))
        assertEquals("meshsat/%2B31600000000/mo/decoded", topic)
        assertFalse(topic.contains("+"))
    }
}
