package com.cubeos.meshsat.reticulum

/**
 * Reticulum-compatible transport interface abstraction.
 *
 * Each transport (BLE, SMS, Iridium, APRS, MQTT) implements this interface
 * to participate in the Reticulum network. The interface handles:
 * - Sending/receiving Reticulum packets over the underlying transport
 * - Reporting online/offline state
 * - Exposing MTU and cost for routing decisions
 *
 * Implementations wrap existing MeshSat transport classes (MeshtasticBle,
 * IridiumSpp, SmsSender, KissClient, MqttTransport) without modifying them.
 */
interface RnsInterface {

    /** Unique interface identifier (e.g., "mesh_0", "iridium_0"). */
    val interfaceId: String

    /** Human-readable name (e.g., "Meshtastic BLE", "Iridium SBD"). */
    val name: String

    /** Maximum Reticulum packet size this interface can carry (bytes). */
    val mtu: Int

    /** Cost per message in USD cents (0 = free). Used for routing. */
    val costCents: Int

    /** Whether this interface is currently online and can send/receive. */
    val isOnline: Boolean

    /**
     * Send a Reticulum packet over this interface.
     *
     * The packet is already marshaled to wire format. The implementation
     * must encapsulate it appropriately for the underlying transport
     * (e.g., as a Meshtastic PRIVATE_APP portnum, base64 SMS, SBD binary).
     *
     * @param packet Serialized RnsPacket bytes
     * @return null on success, error message on failure
     */
    suspend fun send(packet: ByteArray): String?

    /**
     * Set the callback for received Reticulum packets.
     * The implementation must parse incoming transport data and invoke
     * this callback with raw RnsPacket bytes.
     *
     * @param callback Function receiving (interfaceId, packetBytes)
     */
    fun setReceiveCallback(callback: RnsReceiveCallback?)

    /** Start the interface (connect, begin listening). */
    suspend fun start()

    /** Stop the interface (disconnect, stop listening). */
    suspend fun stop()

    /**
     * Estimated latency for this interface in milliseconds.
     * Used for path cost calculations. 0 = negligible.
     */
    val latencyMs: Int get() = 0

    /**
     * Whether this interface supports bidirectional communication.
     * Some interfaces (e.g., APRS beacon-only) may be send-only.
     */
    val isBidirectional: Boolean get() = true
}

/**
 * Callback for received Reticulum packets.
 */
fun interface RnsReceiveCallback {
    fun onReceive(interfaceId: String, packet: ByteArray)
}

/**
 * Reticulum packet encapsulation type.
 * Defines how Reticulum packets are carried over each transport.
 */
enum class RnsEncapsulation {
    /** Raw binary (Iridium SBD, direct serial). */
    RAW_BINARY,

    /** Base64-encoded in text (SMS). */
    BASE64_TEXT,

    /** Meshtastic protobuf with portnum 256. */
    MESHTASTIC_PORTNUM,

    /** AX.25 UI frame with Reticulum payload. */
    AX25_UI_FRAME,

    /** MQTT topic with binary payload. */
    MQTT_BINARY,

    /** HDLC-framed TCP (stock RNS wire format). */
    TCP_HDLC,
}
