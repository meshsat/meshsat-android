package net.meshsat.android.hub.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Inet4Address

/**
 * The tunnel and the client inside it must agree on one loopback address (MESHSAT-616). The JVM
 * this test runs on answers getLoopbackAddress() with 127.0.0.1 and Android with ::1, which is
 * why every relay test passed here while every request from a phone was refused. So the address
 * is pinned as IPv4 127.0.0.1, whatever the platform would pick.
 */
class RelayLoopbackTest {

    @Test
    fun `the tunnel listens on IPv4 127_0_0_1, never the platform's loopback`() {
        assert(RelayTunnel.LOOPBACK is Inet4Address)
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), RelayTunnel.LOOPBACK.address)
        assertEquals("127.0.0.1", RelayTunnel.LOOPBACK.hostAddress)
    }
}
