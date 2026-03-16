package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FailoverGroupDao {

    @Query("SELECT * FROM failover_groups ORDER BY id")
    suspend fun getAllGroups(): List<FailoverGroupEntity>

    @Query("SELECT * FROM failover_groups WHERE id = :id")
    suspend fun getGroup(id: String): FailoverGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: FailoverGroupEntity)

    @Query("DELETE FROM failover_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    // --- Members ---

    @Query("SELECT * FROM failover_members WHERE group_id = :groupId ORDER BY priority ASC")
    suspend fun getMembers(groupId: String): List<FailoverMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: FailoverMemberEntity)

    @Query("DELETE FROM failover_members WHERE group_id = :groupId AND interface_id = :interfaceId")
    suspend fun deleteMember(groupId: String, interfaceId: String)

    @Query("DELETE FROM failover_members WHERE group_id = :groupId")
    suspend fun deleteAllMembers(groupId: String)
}
