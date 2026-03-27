package com.cubeos.meshsat.engine

import android.util.Log
import com.cubeos.meshsat.data.IridiumCreditDao
import com.cubeos.meshsat.data.IridiumCreditEntry
import java.util.Calendar
import java.util.TimeZone

/**
 * Tracks Iridium satellite messaging costs.
 * Default: $0.05 per SBD MO message (5 cents).
 */
class CreditTracker(
    private val dao: IridiumCreditDao,
    private val costPerMoCents: Int = 5,
) {
    companion object {
        private const val TAG = "CreditTracker"
    }

    suspend fun recordMo(moMsn: Int = 0) {
        dao.insert(IridiumCreditEntry(messageType = "mo", costCents = costPerMoCents, moMsn = moMsn))
        Log.d(TAG, "Recorded MO send (cost=${costPerMoCents}c, msn=$moMsn)")
    }

    suspend fun recordBurst(messageCount: Int) {
        val cost = costPerMoCents * messageCount
        dao.insert(IridiumCreditEntry(messageType = "burst", costCents = cost))
        Log.d(TAG, "Recorded burst send (messages=$messageCount, cost=${cost}c)")
    }

    suspend fun todayCostCents(): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return dao.costSince(cal.timeInMillis) ?: 0
    }

    suspend fun todayMessageCount(): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return dao.messagesSince(cal.timeInMillis)
    }

    suspend fun totalCostCents(): Int = dao.totalCostCents() ?: 0
}
