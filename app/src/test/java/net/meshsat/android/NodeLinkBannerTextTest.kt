package net.meshsat.android

import net.meshsat.android.ui.components.nodeLinkBannerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The banner names the cause when the phone knows it (MESHSAT-615). With Bluetooth off, "Cannot
 * reach your MeshSat node" is true and useless: the person looks at the radio, and the answer is
 * a switch on the phone in their hand.
 */
class NodeLinkBannerTextTest {

    @Test
    fun `Bluetooth off is said first, with what to do about it`() {
        val text = nodeLinkBannerText(bluetoothOff = true, meshUp = false, since = "02:27", minutes = 1)
        assertTrue(text.startsWith("Bluetooth is off since 02:27 (1 min)"))
        assertTrue(text.endsWith("Tap to switch it on."))
        assertFalse(text.contains("Cannot reach"))
    }

    @Test
    fun `with Bluetooth on it says which link is down`() {
        assertEquals(
            "Cannot reach your MeshSat node since 02:27. Nothing goes out by mesh or satellite. Tap to see.",
            nodeLinkBannerText(bluetoothOff = false, meshUp = false, since = "02:27", minutes = 0),
        )
        assertTrue(
            nodeLinkBannerText(bluetoothOff = false, meshUp = true, since = "02:27", minutes = 3)
                .startsWith("Cannot reach the node's modem since 02:27 (3 min)."),
        )
    }

    @Test
    fun `no time yet, no since`() {
        assertEquals(
            "Cannot reach your MeshSat node. Nothing goes out by mesh or satellite. Tap to see.",
            nodeLinkBannerText(bluetoothOff = false, meshUp = false, since = null, minutes = 0),
        )
    }
}
