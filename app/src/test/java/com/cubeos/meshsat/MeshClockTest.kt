package com.cubeos.meshsat

import com.cubeos.meshsat.timesync.MeshClock
import com.cubeos.meshsat.timesync.TimeSource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MeshClockTest {
    @Before
    fun setup() {
        MeshClock.reset()
    }

    @Test
    fun `default source is LocalRTC`() {
        assertEquals(TimeSource.LocalRTC, MeshClock.source)
        assertEquals(16, MeshClock.stratum)
        assertFalse(MeshClock.isSynced)
    }

    @Test
    fun `GPS update overrides local RTC`() {
        val gpsTime = System.currentTimeMillis() + 500
        MeshClock.updateFromGps(gpsTime)
        assertTrue(MeshClock.source is TimeSource.GPS)
        assertEquals(1, MeshClock.stratum)
        assertTrue(MeshClock.isSynced)
        assertTrue(kotlin.math.abs(MeshClock.now() - gpsTime) < 50)
    }

    @Test
    fun `lower quality source does not override GPS`() {
        MeshClock.updateFromGps(System.currentTimeMillis())
        MeshClock.updateFromHub(System.currentTimeMillis() + 10000) // Hub is stratum 2
        assertEquals(1, MeshClock.stratum) // Still GPS stratum
    }

    @Test
    fun `Hub NTP overrides LocalRTC`() {
        MeshClock.updateFromHub(System.currentTimeMillis() + 200)
        assertTrue(MeshClock.source is TimeSource.HubNTP)
        assertEquals(2, MeshClock.stratum)
        assertTrue(MeshClock.isSynced)
    }

    @Test
    fun `peer update creates mesh consensus`() {
        MeshClock.updateFromPeer("peer1", 100, 2) // peer stratum 2 → our stratum 3
        assertTrue(MeshClock.source is TimeSource.MeshConsensus)
        assertEquals(3, MeshClock.stratum)
        assertTrue(MeshClock.isSynced)
    }

    @Test
    fun `reset clears everything`() {
        MeshClock.updateFromGps(System.currentTimeMillis() + 1000)
        MeshClock.reset()
        assertEquals(TimeSource.LocalRTC, MeshClock.source)
        assertEquals(0L, MeshClock.offsetMs)
    }

    @Test
    fun `now returns corrected time`() {
        val before = System.currentTimeMillis()
        MeshClock.updateFromGps(before + 5000) // 5s ahead
        val now = MeshClock.now()
        assertTrue(now > before + 4900)
        assertTrue(now < before + 5100)
    }
}
