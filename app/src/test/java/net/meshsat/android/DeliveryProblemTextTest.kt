package net.meshsat.android

import net.meshsat.android.ui.screens.deliveryProblemText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the queue tells a person about why a message has not gone (MESHSAT-615).
 *
 * The two waits look the same in the queue and are not the same at all: a radio out of reach is
 * something they can walk over and fix, a satellite that is not overhead is something to wait
 * out. On 20 September the app said the satellite was overhead while the radio was unreachable,
 * and that confusion cost thirteen minutes (MESHSAT-1270).
 */
class DeliveryProblemTextTest {

    @Test
    fun `a radio out of reach is not described as waiting for a satellite`() {
        val text = deliveryProblemText("Could not hand the message to the modem")
        assertTrue(text, text.contains("cannot reach the node's radio"))
        assertTrue("must not blame a satellite: $text", text.contains("not for a satellite"))
    }

    @Test
    fun `no network service says a satellite is what it waits for`() {
        val text = deliveryProblemText("Not sent: status 32, no network service, MOMSN 233")
        assertTrue(text, text.contains("found no network"))
        assertTrue(text, text.contains("satellite"))
    }

    @Test
    fun `the errors the app did not write are passed through, capitalised`() {
        assertTrue(deliveryProblemText("something odd happened").startsWith("Something"))
        assertTrue(deliveryProblemText("").isEmpty())
        assertTrue(deliveryProblemText("cancelled").contains("You cancelled"))
    }
}
