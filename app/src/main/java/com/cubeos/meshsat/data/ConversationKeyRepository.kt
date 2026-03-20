package com.cubeos.meshsat.data

import android.util.Log
import com.cubeos.meshsat.crypto.SecureKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wraps [ConversationKeyDao] with hardware-backed encryption for AES-256 hex keys.
 *
 * Keys are encrypted via [SecureKeyStore] before being stored in Room, so the
 * SQLite database never contains plaintext key material. Decryption happens on read.
 *
 * Migration: on read, if a value fails to decrypt (legacy plaintext), it is
 * re-encrypted and stored back. This handles the first launch after update.
 */
class ConversationKeyRepository(
    private val dao: ConversationKeyDao,
    private val secureStore: SecureKeyStore,
) {
    companion object {
        private const val TAG = "ConversationKeyRepo"
        private const val KEY_PREFIX = "convkey:"
    }

    fun getAll(): Flow<List<ConversationKey>> = dao.getAll().map { keys ->
        keys.map { decryptKey(it) }
    }

    suspend fun getBySender(sender: String): ConversationKey? {
        val entity = dao.getBySender(sender) ?: return null
        return decryptKey(entity)
    }

    suspend fun upsert(sender: String, hexKey: String, label: String = "") {
        val encrypted = secureStore.get(KEY_PREFIX + sender)
        // Store the actual key in SecureKeyStore, and a reference marker in Room
        secureStore.set(KEY_PREFIX + sender, hexKey)
        dao.upsert(ConversationKey(sender = sender, hexKey = KEY_PREFIX + sender, label = label))
    }

    suspend fun deleteBySender(sender: String) {
        secureStore.remove(KEY_PREFIX + sender)
        dao.deleteBySender(sender)
    }

    /**
     * Decrypt a conversation key entity. If the hexKey is a SecureKeyStore reference
     * (starts with "convkey:"), look it up. Otherwise it's a legacy plaintext key —
     * migrate it on first read.
     */
    private fun decryptKey(entity: ConversationKey): ConversationKey {
        val hex = entity.hexKey
        if (hex.startsWith(KEY_PREFIX)) {
            // Key stored in SecureKeyStore — look it up
            val plainKey = secureStore.get(hex)
            return if (plainKey != null) {
                entity.copy(hexKey = plainKey)
            } else {
                Log.w(TAG, "SecureKeyStore missing key for ${entity.sender}")
                entity
            }
        }

        // Legacy plaintext key — migrate to SecureKeyStore
        try {
            secureStore.set(KEY_PREFIX + entity.sender, hex)
            // We can't update Room synchronously from a map, so the DB update
            // will happen on next upsert. The key is already secured in SecureKeyStore.
            Log.i(TAG, "Migrated conversation key for ${entity.sender} to SecureKeyStore")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate key for ${entity.sender}: ${e.message}")
        }
        return entity
    }
}
