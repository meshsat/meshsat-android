package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AuditLogDao {

    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    /** Get audit log entries, newest first. */
    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AuditLogEntity>

    /** Get audit log entries filtered by interface, newest first. */
    @Query("SELECT * FROM audit_log WHERE interface_id = :interfaceId ORDER BY id DESC LIMIT :limit")
    suspend fun getByInterface(interfaceId: String, limit: Int = 100): List<AuditLogEntity>

    /** Count all audit log entries. */
    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int
}
