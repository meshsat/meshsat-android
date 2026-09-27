package net.meshsat.android

import net.meshsat.android.ble.IridiumPipeContract.PassWindow
import net.meshsat.android.satellite.PassPrediction
import net.meshsat.android.service.GatewayService
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pass windows the node gets over PASS (contract v2, MESHSAT-1378). */
class PassWindowsForNodeTest {
    private fun pass(aos: Long, los: Long, elev: Double) = PassPrediction("IRIDIUM 1", aos, los, (los - aos) / 60.0, elev, 180.0, false)

    @Test
    fun `passes not yet over, soonest first, at most eight`() {
        val now = 1_000_000L
        val passes = listOf(
            pass(now - 600, now - 100, 40.0), // over
            pass(now + 3_000, now + 3_500, 12.7), // later
            pass(now - 100, now + 300, 55.0), // up now
        ) + (1..9).map { pass(now + 10_000L * it, now + 10_000L * it + 480, 20.0) }
        val windows = GatewayService.passWindowsForNode(passes, now)
        assertEquals(8, windows.size)
        assertEquals(PassWindow(now - 100, 400, 55), windows[0])
        assertEquals(PassWindow(now + 3_000, 500, 12), windows[1])
        assertEquals(now + 10_000L, windows[2].startEpochS)
        assertEquals(emptyList<PassWindow>(), GatewayService.passWindowsForNode(emptyList(), now))
    }
}
