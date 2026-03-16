package com.cubeos.meshsat.channel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Describes a transport channel's capabilities and constraints.
 * Port of meshsat/internal/channel/descriptor.go for Android transports.
 */
data class ChannelDescriptor(
    val id: String,
    val label: String,
    val isPaid: Boolean = false,
    val canSend: Boolean = true,
    val canReceive: Boolean = true,
    val binaryCapable: Boolean = false,
    val maxPayload: Int = 0,           // 0 = unlimited
    val defaultTtl: Duration = Duration.ZERO, // 0 = no default TTL
    val isSatellite: Boolean = false,
    val retryConfig: RetryConfig = RetryConfig(),
    val options: List<OptionField> = emptyList(),
)

/**
 * Channel-specific retry behavior.
 */
data class RetryConfig(
    val enabled: Boolean = false,
    val initialWait: Duration = 0.seconds,
    val maxWait: Duration = 0.seconds,
    val maxRetries: Int = 0,           // 0 = infinite
    val backoffFunc: String = "exponential", // "exponential", "linear", "isu"
)

/**
 * Per-channel config field for the rule editor UI.
 */
data class OptionField(
    val key: String,
    val label: String,
    val type: String,      // "text", "number", "select", "checkbox"
    val default: String = "",
    val options: List<String> = emptyList(), // for select type
)
