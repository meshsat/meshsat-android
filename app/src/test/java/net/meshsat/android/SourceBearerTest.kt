package net.meshsat.android

import net.meshsat.android.engine.Dispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a forwarded message came from (MESHSAT-1274).
 *
 * The dispatcher writes the source interface first in a delivery's visited list, and a link
 * that passes the message onwards reads it back out. The Hub stores that value verbatim in
 * `messages.channel` and its routing engine reads it as the source of the message, so getting
 * it wrong is not cosmetic: a forwarded SMS that says "mqtt" both loses its provenance and
 * stops any Hub route scoped to sms from firing.
 */
class SourceBearerTest {

    @Test
    fun `the source interface is the first one visited`() {
        assertEquals("sms_0", Dispatcher.sourceBearerOf("""["sms_0"]"""))
        assertEquals("sms_0", Dispatcher.sourceBearerOf("""["sms_0","mesh_0"]"""))
        assertEquals("iridium_0", Dispatcher.sourceBearerOf("""[ "iridium_0", "hub_0" ]"""))
    }

    @Test
    fun `a message written on this phone has visited nothing`() {
        assertEquals("", Dispatcher.sourceBearerOf(""))
        assertEquals("", Dispatcher.sourceBearerOf("[]"))
    }

    @Test
    fun `nonsense in the column does not throw`() {
        assertEquals("", Dispatcher.sourceBearerOf("not json"))
        assertEquals("", Dispatcher.sourceBearerOf("[,]"))
        assertEquals("", Dispatcher.sourceBearerOf("""{"visited":["sms_0"]}"""))
    }
}
