package net.meshsat.android.rns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The AutoInterface derivations against values computed with Python RNS 1.5.5, and its address text rules. [MESHSAT-1553 P4] */
class AutoInterfaceTest {
    @Test
    fun multicastGroupMatchesUpstream() {
        // AutoInterface.mcast_discovery_address for the default group "reticulum"
        assertEquals("ff120000d70bfb1c16e45e39485e31e1", AutoInterface.multicastGroup("reticulum").hex())
        assertEquals("ff12:0:d70b:fb1c:16e4:5e39:485e:31e1", AutoInterface.compress(AutoInterface.multicastGroup("reticulum")))
        assertEquals("ff12000008e15fef2248d6feb6f6e0df", AutoInterface.multicastGroup("meshsat-test").hex())
    }

    @Test
    fun tokenMatchesUpstream() {
        // RNS.Identity.full_hash(group_id + link_local_address)
        assertEquals("97b25576749ea936b0d8a8536ffaf442d157cf47d460dcf13c48b7bd18b6c163", AutoInterface.token("reticulum", "fe80::1").hex())
        assertEquals("8f55515369a0f2af8df9fa724c08bbf5d915b9e4f46607118d93d6f38a5aa53b", AutoInterface.token("reticulum", "fe80::fc0a:1eff:fef2:f2ac").hex())
    }

    @Test
    fun addressTextAsInetNtop() {
        assertEquals("fe80::fc0a:1eff:fef2:f2ac", AutoInterface.compress("fe80000000000000fc0a1efffef2f2ac".unhex()))
        assertEquals("fe80::1", AutoInterface.compress("fe800000000000000000000000000001".unhex()))
        assertEquals("::", AutoInterface.compress(ByteArray(16)))
        assertEquals("::1", AutoInterface.compress(ByteArray(16).also { it[15] = 1 }))
        assertEquals("2001:db8::1:0:0:1", AutoInterface.compress("20010db8000000000001000000000001".unhex()))   // the longest run wins, leftmost on a tie
        assertEquals("1:0:0:2::3", AutoInterface.compress("00010000000000020000000000000003".unhex()))
        assertEquals("1:0:2::3", AutoInterface.compress("00010000000200000000000000000003".unhex()))
        assertEquals("1::2:0:0:3", AutoInterface.compress("00010000000000000002000000000003".unhex()))
    }

    @Test
    fun descopeAsUpstream() {
        assertEquals("fe80::fc0a:1eff:fef2:f2ac", AutoInterface.descope("fe80::fc0a:1eff:fef2:f2ac%veth1"))
        assertEquals("fe80::1", AutoInterface.descope("fe80:2::1"))        // an embedded scope index (the BSDs)
        assertEquals("fe80::1", AutoInterface.descope("fe80::1"))
    }

    /** Two instances on one host cannot peer (same address), but the sockets bind and release cleanly. */
    @Test
    fun startsWhenALinkLocalAddressExistsAndStops() {
        val got = LinkedBlockingQueue<ByteArray>()
        val a = AutoInterface("t", groupId = "meshsat-unit-${System.nanoTime()}", onPacket = { got.add(it) }, log = { })
        val started = a.start()
        if (!started) { assertFalse(a.online); return }   // a CI container without IPv6 link-local addresses
        assertTrue(a.online)
        assertTrue(a.adoptedDevices().isNotEmpty())
        assertFalse(a.send(ByteArray(10)))   // no peers yet
        a.stop()
        assertFalse(a.online)
        assertEquals(null, got.poll(100, TimeUnit.MILLISECONDS))
    }
}
