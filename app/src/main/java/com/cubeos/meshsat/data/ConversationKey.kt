package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-conversation encryption key.
 * Maps a sender (phone number or node ID) to a specific AES-256-GCM hex key.
 * If set, this key takes priority over the global encryption key for that sender.
 */
@Entity(tableName = "conversation_keys")
data class ConversationKey(
    @PrimaryKey val sender: String,
    val hexKey: String,
    val label: String = "",
)
