package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val transport: String,      // "sms", "mesh", "iridium"
    val direction: String,      // "rx", "tx"
    val sender: String,         // phone number, node ID, etc.
    val recipient: String = "",
    val text: String,           // decrypted plaintext
    val rawText: String = "",   // original ciphertext (if encrypted)
    val encrypted: Boolean = false,
    val forwarded: Boolean = false,
    val forwardedTo: String = "",
)
