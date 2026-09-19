package net.meshsat.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.meshsat.android.engine.InterfaceConfig
import net.meshsat.android.engine.InterfaceManager
import net.meshsat.android.engine.InterfaceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Iridium refusal (an SBDIX held after a failed session) is not a link failure: recording it
 * must leave the interface online, or the dispatcher stops the worker and holds every queued
 * satellite message (MESHSAT-1243).
 */
class InterfaceManagerNoteErrorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a noted error keeps the interface online and fires no state change`() {
        val mgr = InterfaceManager(scope)
        val changes = mutableListOf<Pair<InterfaceState, InterfaceState>>()
        mgr.setStateChangeCallback { _, _, old, new -> changes.add(old to new) }
        mgr.register(InterfaceConfig(id = "iridium_0", channelType = "iridium"))
        mgr.setOnline("iridium_0")
        changes.clear()

        mgr.noteError("iridium_0", "SBDIX held for 42 s after a failed session")

        val status = mgr.states.value.getValue("iridium_0")
        assertEquals(InterfaceState.Online, status.state)
        assertEquals("SBDIX held for 42 s after a failed session", status.error)
        assertTrue(changes.isEmpty())
    }
}
