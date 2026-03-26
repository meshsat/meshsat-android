package com.cubeos.meshsat.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Stores provider TLS certificates and credentials received from Hub or imported via QR.
 * Data is stored encrypted via Android Keystore (EncryptedSharedPreferences handles this
 * at the secure storage layer — Room stores the already-encrypted blob from Hub).
 */
@Entity(tableName = "provider_credentials")
data class ProviderCredential(
    @PrimaryKey val id: String,
    val provider: String,       // cloudloop_mqtt, rockblock, astrocast, etc.
    val name: String,
    @ColumnInfo(name = "cred_type") val credType: String, // mtls_bundle, api_key, webhook_secret
    @ColumnInfo(name = "encrypted_data") val encryptedData: ByteArray,
    @ColumnInfo(name = "cert_not_after") val certNotAfter: String? = null,
    @ColumnInfo(name = "cert_subject") val certSubject: String = "",
    @ColumnInfo(name = "cert_fingerprint") val certFingerprint: String = "",
    val version: Int = 1,
    val source: String = "local", // local, hub, qr
    @ColumnInfo(name = "received_at") val receivedAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderCredential) return false
        return id == other.id && version == other.version
    }

    override fun hashCode(): Int = id.hashCode() * 31 + version
}

@Dao
interface ProviderCredentialDao {
    @Query("SELECT * FROM provider_credentials ORDER BY provider, name")
    fun getAll(): Flow<List<ProviderCredential>>

    @Query("SELECT * FROM provider_credentials WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProviderCredential?

    @Query("SELECT * FROM provider_credentials WHERE provider = :provider")
    suspend fun getByProvider(provider: String): List<ProviderCredential>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(credential: ProviderCredential)

    @Query("DELETE FROM provider_credentials WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM provider_credentials")
    suspend fun count(): Int
}
