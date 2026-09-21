package net.meshsat.android

import net.meshsat.android.ui.components.AskOutcome
import net.meshsat.android.ui.components.askOutcome
import net.meshsat.android.ui.components.goToSettingsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Allow SMS" did nothing on Elli's phone: the app was installed from a downloaded file, Android
 * treats SMS as a restricted setting for such an app, and it answered the request at once without
 * a dialog (MESHSAT-1295). The app must notice that and send the person to the right page.
 */
class PermissionAskTest {

    private val send = "android.permission.SEND_SMS"
    private val receive = "android.permission.RECEIVE_SMS"

    @Test
    fun `everything granted is granted`() {
        assertEquals(AskOutcome.Granted, askOutcome(emptyList(), emptySet()))
    }

    @Test
    fun `refused without a question means the settings page`() {
        assertEquals(AskOutcome.GoToSettings, askOutcome(listOf(send, receive), emptySet()))
    }

    @Test
    fun `declined once, Android will still ask`() {
        assertEquals(AskOutcome.AskAgain, askOutcome(listOf(send, receive), setOf(send, receive)))
        assertEquals(AskOutcome.AskAgain, askOutcome(listOf(send, receive), setOf(receive)))
    }

    @Test
    fun `the words name the way out, restricted settings included`() {
        val t = goToSettingsText("SMS")
        assertTrue(t.contains("Open MeshSat settings"))
        assertTrue(t.contains("Allow restricted settings"))
        assertTrue(t.contains("Permissions, SMS, Allow"))
    }
}
