package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ObjectGroupDao {

    @Query("SELECT * FROM object_groups ORDER BY id")
    suspend fun getAll(): List<ObjectGroupEntity>

    @Query("SELECT * FROM object_groups WHERE id = :id")
    suspend fun getById(id: String): ObjectGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: ObjectGroupEntity)

    @Query("DELETE FROM object_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM object_groups")
    suspend fun deleteAll()
}
