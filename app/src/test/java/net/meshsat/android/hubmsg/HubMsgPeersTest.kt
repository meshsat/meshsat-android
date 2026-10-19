package net.meshsat.android.hubmsg

import net.meshsat.android.hub.HubTopics
import net.meshsat.android.ui.Peers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation through the Hub is keyed by the other app's principal, "bridge:<id>", in both
 * directions, and goes only by the Hub; the phone's msg topics are its own bridge subtree (C4.1).
 * [MESHSAT-1584]
 */
class HubMsgPeersTest {

    @Test
    fun aBridgePrincipalIsAHubPeer() {
        assertTrue(Peers.isHub("bridge:kit-b"))
        assertTrue(Peers.isHub("bridge:3f2a9c1e-phone"))
        for (p in listOf("bridge:", "bridge:a/b", "bridge:a+b", "bridge:a#b", "kit-b", "!a1b2c3d4", "+31612345678",
            "0123456789abcdef0123456789abcdef", "^all", "satellite")) {
            assertFalse(p, Peers.isHub(p))
        }
    }

    @Test
    fun aHubPeerGoesOnlyByTheHub() {
        val p = Peers.hubPeer(" kit-b ")
        assertEquals("bridge:kit-b", p)
        assertEquals(HubMsgWire.principal("kit-b"), p)
        assertEquals("hub", Peers.defaultTransport(p))
        assertEquals(listOf("hub"), Peers.transportsFor(p))
        assertEquals("kit-b", Peers.displayName(p))
        assertEquals("Through the Hub to its MeshSat app", Peers.detail(p))
        // The other kinds keep their own way.
        assertEquals("sms", Peers.defaultTransport("+31612345678"))
        assertEquals("mesh", Peers.defaultTransport("!a1b2c3d4"))
    }

    @Test
    fun theMsgTopicsAreThisPhonesOwnBridgeSubtree() {
        assertEquals("meshsat/bridge/pi-01/msg/out", HubTopics.PLATFORM.msg("pi-01", "out"))
        assertEquals("meshsat/bridge/pi-01/msg/ack", HubTopics.PLATFORM.msg("pi-01", "ack"))
        assertEquals("meshsat/bridge/pi-01/msg/in", HubTopics.PLATFORM.msg("pi-01", "in"))
        assertEquals("meshsat/bridge/pi-01/msg/status", HubTopics.PLATFORM.msg("pi-01", "status"))
        assertEquals("meshsat/bridge/a%2Fb/msg/out", HubTopics.PLATFORM.msg("a/b", "out"))
    }
}
