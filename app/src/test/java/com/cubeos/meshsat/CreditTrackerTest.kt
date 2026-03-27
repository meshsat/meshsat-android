package com.cubeos.meshsat

import com.cubeos.meshsat.data.IridiumCreditDao
import com.cubeos.meshsat.data.IridiumCreditEntry
import com.cubeos.meshsat.engine.CreditTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CreditTrackerTest {

    private class FakeDao : IridiumCreditDao {
        val entries = mutableListOf<IridiumCreditEntry>()
        override suspend fun insert(entry: IridiumCreditEntry) { entries.add(entry) }
        override suspend fun totalCostCents(): Int = entries.sumOf { it.costCents }
        override suspend fun costSince(since: Long): Int = entries.filter { it.timestamp > since }.sumOf { it.costCents }
        override suspend fun messagesSince(since: Long): Int = entries.count { it.timestamp > since }
        override fun getRecent(limit: Int): Flow<List<IridiumCreditEntry>> = flowOf(entries.takeLast(limit))
    }

    @Test
    fun `recordMo inserts entry with default cost`() = runBlocking {
        val dao = FakeDao()
        val tracker = CreditTracker(dao)
        tracker.recordMo(moMsn = 42)
        assertEquals(1, dao.entries.size)
        assertEquals(5, dao.entries[0].costCents)
        assertEquals("mo", dao.entries[0].messageType)
        assertEquals(42, dao.entries[0].moMsn)
    }

    @Test
    fun `recordBurst multiplies cost by message count`() = runBlocking {
        val dao = FakeDao()
        val tracker = CreditTracker(dao, costPerMoCents = 5)
        tracker.recordBurst(messageCount = 4)
        assertEquals(1, dao.entries.size)
        assertEquals(20, dao.entries[0].costCents)
        assertEquals("burst", dao.entries[0].messageType)
    }

    @Test
    fun `totalCostCents sums all entries`() = runBlocking {
        val dao = FakeDao()
        val tracker = CreditTracker(dao)
        tracker.recordMo()
        tracker.recordMo()
        tracker.recordBurst(3)
        assertEquals(25, tracker.totalCostCents())
    }
}
