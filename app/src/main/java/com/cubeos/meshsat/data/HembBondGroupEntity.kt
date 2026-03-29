package com.cubeos.meshsat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "hemb_bond_groups")
data class HembBondGroupEntity(
    @PrimaryKey val id: String,
    val label: String = "",
    val members: String = "[]",  // JSON array of interface IDs
    val costBudget: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface HembBondGroupDao {
    @Query("SELECT * FROM hemb_bond_groups ORDER BY createdAt ASC")
    suspend fun getAll(): List<HembBondGroupEntity>

    @Query("SELECT * FROM hemb_bond_groups WHERE id = :id")
    suspend fun getById(id: String): HembBondGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: HembBondGroupEntity)

    @Query("DELETE FROM hemb_bond_groups WHERE id = :id")
    suspend fun delete(id: String)
}
