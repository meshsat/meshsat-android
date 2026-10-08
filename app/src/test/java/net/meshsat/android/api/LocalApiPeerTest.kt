package net.meshsat.android.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local API answers adb and the app, nobody else (GHSA-h3j8-w2p3-vw76, MESHSAT-1515). On
 * 127.0.0.1:6051 any app with INTERNET could send SMS through it and replace the Hub settings.
 */
class LocalApiPeerTest {

    private val ownUid = 10234

    @Test
    fun `adb's shell gets in`() {
        assertTrue(LocalApiServer.isTrustedPeer(2000, ownUid))
    }

    @Test
    fun `root after adb root gets in`() {
        assertTrue(LocalApiServer.isTrustedPeer(0, ownUid))
    }

    @Test
    fun `the app itself gets in`() {
        assertTrue(LocalApiServer.isTrustedPeer(ownUid, ownUid))
    }

    @Test
    fun `another app is refused`() {
        assertFalse(LocalApiServer.isTrustedPeer(10235, ownUid))
        assertFalse(LocalApiServer.isTrustedPeer(10234 + 100000, ownUid))
    }

    @Test
    fun `a caller whose uid could not be read is refused`() {
        assertFalse(LocalApiServer.isTrustedPeer(-1, ownUid))
    }
}
