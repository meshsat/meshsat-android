package com.cubeos.meshsat.engine

import android.util.Log
import com.cubeos.meshsat.data.FailoverGroupDao

/**
 * Resolves failover group IDs to the best available interface.
 * Port of Go's engine.FailoverResolver.
 *
 * If [targetId] is a plain interface ID (not a failover group), returns it as-is.
 * If it's a failover group, returns the highest-priority online member.
 * Returns empty string if no member is available.
 */
class FailoverResolver(
    private val failoverGroupDao: FailoverGroupDao,
    private val statusProvider: InterfaceStatusProvider,
) {
    /**
     * Resolve a target ID to a concrete interface ID.
     * This is a suspend function because it queries the database.
     */
    suspend fun resolve(targetId: String): String {
        // Check if it's a failover group
        val group = failoverGroupDao.getGroup(targetId) ?: return targetId // not a group, return as-is

        val members = failoverGroupDao.getMembers(group.id)
        if (members.isEmpty()) {
            Log.w(TAG, "Failover group '${group.id}': no members")
            return ""
        }

        // Members are ordered by priority ASC (lowest = highest priority)
        // First pass: find highest-priority online member
        for (member in members) {
            if (statusProvider.isOnline(member.interfaceId)) {
                Log.d(TAG, "Failover '${group.id}' resolved to ${member.interfaceId} (priority=${member.priority})")
                return member.interfaceId
            }
        }

        // No online member — fall back to first member (deliveries will be held)
        val fallback = members.first().interfaceId
        Log.w(TAG, "Failover '${group.id}': no online member, using $fallback (deliveries will be held)")
        return fallback
    }

    companion object {
        private const val TAG = "FailoverResolver"
    }
}
