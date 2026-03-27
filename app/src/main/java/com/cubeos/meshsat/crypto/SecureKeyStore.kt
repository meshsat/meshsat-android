package com.cubeos.meshsat.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cubeos.meshsat.routing.KeyValueStore

/**
 * Hardware-backed secure key storage using Android Keystore (MESHSAT-194).
 *
 * Uses EncryptedSharedPreferences backed by a MasterKey in Android Keystore.
 * Prefers StrongBox (hardware security module) on devices that support it.
 *
 * Migration: on first access, copies existing keys from old SharedPreferences
 * and DataStore, then deletes them from the old location.
 *
 * Implements [KeyValueStore] so it drops into Identity and SigningService.
 */
class SecureKeyStore private constructor(
    private val prefs: SharedPreferences,
) : KeyValueStore {

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val PREFS_NAME = "meshsat_secure_keys"
        private const val MIGRATION_DONE_KEY = "_migration_done"

        // Old storage locations
        private const val OLD_SIGNING_PREFS = "meshsat_signing"

        // Key names (must match Identity.kt and SigningService.kt constants)
        private const val KEY_ENCRYPTION_KEY = "encryption_key"
        private const val KEY_SIGNING_PRIV = "signing_private_key"
        private const val KEY_SIGNING_PUB = "signing_public_key"
        private const val KEY_ROUTING_SIGNING_PRIV = "routing_signing_key_private"
        private const val KEY_ROUTING_SIGNING_PUB = "routing_signing_key_public"
        private const val KEY_ROUTING_ENCRYPTION_PRIV = "routing_encryption_key_private"
        private const val KEY_ROUTING_ENCRYPTION_PUB = "routing_encryption_key_public"

        @Volatile
        private var instance: SecureKeyStore? = null

        /**
         * Get or create the singleton SecureKeyStore.
         * Performs migration from old storage on first creation.
         */
        fun getInstance(context: Context): SecureKeyStore {
            return instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }
        }

        private fun create(context: Context): SecureKeyStore {
            val prefs = try {
                openEncryptedPrefs(context)
            } catch (e: Exception) {
                // AEADBadTagException / InvalidProtocolBufferException: the Keystore master
                // key was invalidated (OS update, app reinstall over existing data, Keystore
                // corruption). Delete the corrupted file and recreate from scratch.
                Log.w(TAG, "EncryptedSharedPreferences corrupted, resetting: ${e.message}")
                deleteEncryptedPrefsFile(context)
                try {
                    openEncryptedPrefs(context)
                } catch (e2: Exception) {
                    // Keystore itself may be in a bad state — nuke the master key and retry once.
                    Log.w(TAG, "Retry after file delete failed, clearing master key: ${e2.message}")
                    try {
                        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                        ks.load(null)
                        ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    } catch (ksErr: Exception) {
                        Log.w(TAG, "Could not remove master key: ${ksErr.message}")
                    }
                    openEncryptedPrefs(context)
                }
            }

            val store = SecureKeyStore(prefs)

            // Migrate from old storage if not done yet
            if (!prefs.getBoolean(MIGRATION_DONE_KEY, false)) {
                migrateOldKeys(context, store)
                prefs.edit().putBoolean(MIGRATION_DONE_KEY, true).apply()
            }

            Log.i(TAG, "Secure key store initialized (Keystore-backed)")
            return store
        }

        private fun openEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = buildMasterKey(context)
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun deleteEncryptedPrefsFile(context: Context) {
            val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            val prefsFile = java.io.File(prefsDir, "$PREFS_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
                Log.i(TAG, "Deleted corrupted encrypted prefs file")
            }
        }

        private fun buildMasterKey(context: Context): MasterKey {
            val specBuilder = KeyGenParameterSpec.Builder(
                MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)

            val builder = MasterKey.Builder(context)
                .setKeyGenParameterSpec(specBuilder.build())

            // Prefer StrongBox on API 28+ if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val strongBoxSpec = KeyGenParameterSpec.Builder(
                        MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setIsStrongBoxBacked(true)
                        .build()

                    return MasterKey.Builder(context)
                        .setKeyGenParameterSpec(strongBoxSpec)
                        .build()
                        .also { Log.i(TAG, "Using StrongBox-backed master key") }
                } catch (_: Exception) {
                    Log.d(TAG, "StrongBox not available, falling back to TEE")
                }
            }

            return builder.build()
        }

        /**
         * Migrate existing keys from old SharedPreferences and DataStore
         * into the encrypted store, then delete from old location.
         */
        private fun migrateOldKeys(context: Context, store: SecureKeyStore) {
            var migrated = 0

            // Migrate signing/routing keys from old SharedPreferences
            try {
                val oldPrefs = context.getSharedPreferences(OLD_SIGNING_PREFS, Context.MODE_PRIVATE)
                val keysToMigrate = listOf(
                    KEY_SIGNING_PRIV,
                    KEY_SIGNING_PUB,
                    KEY_ROUTING_SIGNING_PRIV,
                    KEY_ROUTING_SIGNING_PUB,
                    KEY_ROUTING_ENCRYPTION_PRIV,
                    KEY_ROUTING_ENCRYPTION_PUB,
                )

                val editor = oldPrefs.edit()
                for (key in keysToMigrate) {
                    val value = oldPrefs.getString(key, null)
                    if (value != null && !store.contains(key)) {
                        store.set(key, value)
                        editor.remove(key)
                        migrated++
                    }
                }
                editor.apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to migrate signing keys: ${e.message}")
            }

            // Migrate AES encryption key from DataStore
            // DataStore is async — we read it via the preferences file directly
            try {
                val dsFile = java.io.File(
                    context.filesDir,
                    "datastore/meshsat_settings.preferences_pb",
                )
                if (dsFile.exists()) {
                    // DataStore migration happens asynchronously in SettingsRepository
                    // via migrateEncryptionKeyToSecureStore() — see SettingsRepository.kt
                    Log.d(TAG, "DataStore file exists, AES key migration deferred to SettingsRepository")
                }
            } catch (e: Exception) {
                Log.w(TAG, "DataStore check failed: ${e.message}")
            }

            if (migrated > 0) {
                Log.i(TAG, "Migrated $migrated keys from old storage to secure store")
            }
        }
    }
}
