package net.meshsat.android

import net.meshsat.android.hub.connectFailureText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * What the Hub card says when the connection fails (MESHSAT-749): the reason, not only "Cannot
 * reach the Hub". Paho wraps the real cause, so the innermost message is the one worth showing.
 */
class HubConnectFailureTest {

    @Test
    fun `the wrapped cause is shown after the library's words`() {
        val e = IOException("Unable to connect to server", SSLHandshakeException("Chain validation failed"))
        assertEquals("Unable to connect to server: Chain validation failed", connectFailureText(e))
    }

    @Test
    fun `a message that already names its cause is not repeated`() {
        val e = IOException("connect failed: timeout", IOException("timeout"))
        assertEquals("connect failed: timeout", connectFailureText(e))
    }

    @Test
    fun `no message still says what kind of failure`() {
        assertEquals("IllegalStateException", connectFailureText(IllegalStateException()))
    }
}
