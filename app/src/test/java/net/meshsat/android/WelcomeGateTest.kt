package net.meshsat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The welcome asks once per permission (MESHSAT-1752). */
class WelcomeGateTest {
    private val location = "android.permission.ACCESS_FINE_LOCATION"
    private val bluetooth = "android.permission.BLUETOOTH_CONNECT"
    private val notifications = "android.permission.POST_NOTIFICATIONS"

    @Test
    fun `everything granted asks nothing`() {
        assertNull(WelcomeGate.toAsk(emptyList(), emptySet()))
        assertNull(WelcomeGate.toAsk(emptyList(), setOf(location)))
    }

    @Test
    fun `first start asks for everything missing`() {
        assertEquals(
            listOf(location, bluetooth, notifications),
            WelcomeGate.toAsk(listOf(location, bluetooth, notifications), emptySet()),
        )
    }

    @Test
    fun `a permission declined on the welcome is not asked again at start`() {
        assertNull(WelcomeGate.toAsk(listOf(location), setOf(location, bluetooth, notifications)))
    }

    @Test
    fun `a permission a newer version adds is asked once, alone`() {
        assertEquals(
            listOf(notifications),
            WelcomeGate.toAsk(listOf(location, notifications), setOf(location, bluetooth)),
        )
    }
}
