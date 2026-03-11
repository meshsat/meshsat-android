package com.cubeos.meshsat.data

/** Projection for conversation grouping query. */
data class ConversationSummary(
    val sender: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val messageCount: Int,
    val transport: String,
    val hasEncrypted: Boolean,
)
