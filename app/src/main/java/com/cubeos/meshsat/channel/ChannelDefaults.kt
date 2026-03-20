package com.cubeos.meshsat.channel

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Register the 5 built-in Android transport channels.
 * Android-specific subset of meshsat/internal/channel/defaults.go.
 *
 * Android transports:
 * - mesh: Meshtastic BLE (binary, 237B MTU)
 * - iridium: Iridium 9603N via HC-05 SPP (binary, 340B MTU, paid)
 * - sms: Native Android SMS (text-only, 160 chars)
 * - mqtt: Hub MQTT (TCP, unlimited payload, enables Hub-proxied TAK/APRS-IS)
 * - aprs: APRS via APRSDroid KISS TCP (256B, local RF)
 */
fun registerAndroidDefaults(registry: ChannelRegistry) {
    registry.register(
        ChannelDescriptor(
            id = "mesh",
            label = "Meshtastic BLE",
            isPaid = false,
            canSend = true,
            canReceive = true,
            binaryCapable = true,
            maxPayload = 237,
            retryConfig = RetryConfig(
                enabled = false,
                maxRetries = 1,
            ),
            options = listOf(
                OptionField(key = "channel", label = "Mesh Channel", type = "number", default = "0"),
                OptionField(key = "target_node", label = "Target Node", type = "text"),
            ),
        )
    )

    registry.register(
        ChannelDescriptor(
            id = "iridium",
            label = "Iridium SBD",
            isPaid = true,
            canSend = true,
            canReceive = true,
            binaryCapable = true,
            maxPayload = 340,
            defaultTtl = 3600.seconds,
            isSatellite = true,
            retryConfig = RetryConfig(
                enabled = true,
                initialWait = 180.seconds,
                maxWait = 30.minutes,
                maxRetries = 10,
                backoffFunc = "isu",
            ),
            options = listOf(
                OptionField(
                    key = "priority", label = "Priority", type = "select",
                    default = "1", options = listOf("0", "1", "2"),
                ),
                OptionField(key = "include_gps", label = "Include GPS", type = "checkbox", default = "false"),
            ),
        )
    )

    registry.register(
        ChannelDescriptor(
            id = "sms",
            label = "Cellular SMS",
            isPaid = true,
            canSend = true,
            canReceive = true,
            binaryCapable = false,       // text-only, needs base64 for binary payloads
            maxPayload = 160,
            defaultTtl = 86400.seconds,
            retryConfig = RetryConfig(
                enabled = true,
                initialWait = 30.seconds,
                maxWait = 5.minutes,
                maxRetries = 3,
                backoffFunc = "exponential",
            ),
        )
    )

    registry.register(
        ChannelDescriptor(
            id = "mqtt",
            label = "Hub MQTT",
            isPaid = false,
            canSend = true,
            canReceive = true,
            binaryCapable = true,
            maxPayload = 0, // unlimited
            defaultTtl = 300.seconds,
            retryConfig = RetryConfig(
                enabled = true,
                initialWait = 5.seconds,
                maxWait = 60.seconds,
                maxRetries = 10,
                backoffFunc = "exponential",
            ),
            options = listOf(
                OptionField(key = "broker_url", label = "Hub Broker URL", type = "text"),
                OptionField(key = "device_id", label = "Device ID", type = "text"),
                OptionField(key = "username", label = "Username", type = "text"),
                OptionField(key = "password", label = "Password", type = "text"),
            ),
        )
    )

    registry.register(
        ChannelDescriptor(
            id = "reticulum",
            label = "Reticulum (LoRa)",
            isPaid = false,
            canSend = true,
            canReceive = true,
            binaryCapable = true,
            maxPayload = 383,  // RnsConstants.ENCRYPTED_MDU — encrypted Reticulum MDU
            retryConfig = RetryConfig(
                enabled = false,
                maxRetries = 1,
            ),
            options = listOf(
                OptionField(key = "channel", label = "Mesh Channel", type = "number", default = "0"),
                OptionField(key = "target_node", label = "Target Node", type = "text"),
            ),
        )
    )

    registry.register(
        ChannelDescriptor(
            id = "aprs",
            label = "APRS (APRSDroid)",
            isPaid = false,
            canSend = true,
            canReceive = true,
            binaryCapable = false,
            maxPayload = 256,
            retryConfig = RetryConfig(
                enabled = false,
                maxRetries = 1,
            ),
            options = listOf(
                OptionField(key = "callsign", label = "Callsign", type = "text"),
                OptionField(key = "ssid", label = "SSID", type = "number", default = "7"),
                OptionField(key = "kiss_host", label = "KISS Host", type = "text", default = "localhost"),
                OptionField(key = "kiss_port", label = "KISS Port", type = "number", default = "8001"),
            ),
        )
    )
}
