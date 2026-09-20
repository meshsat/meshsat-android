package net.meshsat.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Someone whose card this phone has taken in, by QR code face to face (MESHSAT-566, 575).
 *
 * The key is the fingerprint rather than the name, because the name is whatever the other
 * person typed and two people may well share one. Scanning a second card from the same key
 * replaces the row: the person changed their name or added a way to reach them, and it is the
 * same key that signed it.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val fingerprint: String,
    val name: String,
    /** Base64 of the raw 32-byte Ed25519 public key that signed the card. */
    @ColumnInfo(name = "signing_pub") val signingPub: String,
    @ColumnInfo(name = "mesh_node_id") val meshNodeId: String = "",
    @ColumnInfo(name = "bridge_id") val bridgeId: String = "",
    /** "SCANNED" off a screen in person, or "IMPORTED" from text that anyone could have passed on. */
    val trust: String = "IMPORTED",
    @ColumnInfo(name = "issued_at") val issuedAt: Long = 0,
    @ColumnInfo(name = "added_at") val addedAt: Long = 0,
)

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE fingerprint = :fingerprint")
    suspend fun get(fingerprint: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE fingerprint = :fingerprint")
    suspend fun delete(fingerprint: String)
}
