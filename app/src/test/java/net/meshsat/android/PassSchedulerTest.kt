package net.meshsat.android

import net.meshsat.android.satellite.PassPrediction
import net.meshsat.android.satellite.PassScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class PassSchedulerTest {

    @Test
    fun `idle mode when no passes`() = runTest {
        val scheduler = PassScheduler(
            passProvider = { emptyList() },
            scope = this,
        )
        assertEquals(PassScheduler.PassMode.Idle, scheduler.mode.value)
    }

    @Test
    fun `timing params vary by mode`() {
        val scheduler = PassScheduler(
            passProvider = { emptyList() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val idle = scheduler.timingForMode(PassScheduler.PassMode.Idle)
        val active = scheduler.timingForMode(PassScheduler.PassMode.Active)
        assertTrue(idle.signalPollIntervalMs > active.signalPollIntervalMs)
        assertFalse(idle.burstFlushOnEntry)
        assertTrue(active.burstFlushOnEntry)
    }

    @Test
    fun `active mode polling is most aggressive`() {
        val scheduler = PassScheduler(
            passProvider = { emptyList() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val active = scheduler.timingForMode(PassScheduler.PassMode.Active)
        assertEquals(5_000L, active.signalPollIntervalMs)
    }

    @Test
    fun `idle polling is conservative`() {
        val scheduler = PassScheduler(
            passProvider = { emptyList() },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val idle = scheduler.timingForMode(PassScheduler.PassMode.Idle)
        assertEquals(120_000L, idle.signalPollIntervalMs)
    }
}
