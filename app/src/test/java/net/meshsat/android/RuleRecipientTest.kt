package net.meshsat.android

import net.meshsat.android.engine.Dispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A rule saying where its forwarded message goes, for links that need an address.
 *
 * Before this there was nowhere to say it, so every rule that forwarded to SMS fell through to
 * one global number - the old "kit phone number", from when the phone talked to a single
 * Raspberry Pi kit. There is never just one other device.
 *
 * Everything here comes out of a database column someone can edit by hand or through a config
 * import, so the failures matter as much as the success: a rule with nonsense in its options
 * must fall back to the global number rather than throw inside the dispatcher.
 */
class RuleRecipientTest {

    @Test
    fun `a rule names its number`() {
        assertEquals("+31612345678", Dispatcher.recipientFromOptions("""{"to":"+31612345678"}"""))
    }

    @Test
    fun `it lives beside the other forward options`() {
        assertEquals(
            "+31612345678",
            Dispatcher.recipientFromOptions("""{"ttl_seconds":600,"to":"+31612345678"}"""),
        )
    }

    @Test
    fun `no number is not an error`() {
        assertEquals("", Dispatcher.recipientFromOptions(""))
        assertEquals("", Dispatcher.recipientFromOptions("{}"))
        assertEquals("", Dispatcher.recipientFromOptions("""{"ttl_seconds":600}"""))
    }

    @Test
    fun `nonsense falls back rather than throwing`() {
        assertEquals("", Dispatcher.recipientFromOptions("not json at all"))
        assertEquals("", Dispatcher.recipientFromOptions("""{"to":}"""))
        assertEquals("", Dispatcher.recipientFromOptions("""{"to":{"number":"+31"}}"""))
    }

    @Test
    fun `a number typed with spaces around it still works`() {
        assertEquals("+31612345678", Dispatcher.recipientFromOptions("""{"to":"  +31612345678  "}"""))
    }
}
