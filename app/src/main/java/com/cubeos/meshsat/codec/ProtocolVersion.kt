package com.cubeos.meshsat.codec

import android.util.Log

/**
 * Protocol version byte support for MeshSat wire protocol future-proofing.
 * All MeshSat-originated payloads can optionally start with a version byte.
 *
 * Detection:
 *   - 0x01 = MeshSat protocol v1 (current: SMAZ2/MSVQSC, AES-256-GCM, 2-byte frag)
 *   - 0x50, 0x44, 0xCA = known magic bytes (no version prefix)
 *   - Other values = legacy (no version byte, treat as raw payload)
 *
 * The version byte is OPTIONAL and backwards compatible — existing devices
 * without version bytes work unchanged. New devices prepend it.
 */
object ProtocolVersion {

    private const val TAG = "ProtocolVersion"

    /** Current MeshSat wire protocol version. */
    const val PROTO_VERSION_1: Byte = 0x01

    // Known magic bytes that indicate payload type (not a version byte).
    private const val MAGIC_GPS_BRIDGE_FULL: Byte = 0x50
    private const val MAGIC_GPS_BRIDGE_DELTA: Byte = 0x44
    private val MAGIC_CANNED_MESSAGE: Byte = 0xCA.toByte()

    /**
     * Result of stripping a version byte from a payload.
     * @param version The detected protocol version (0 = legacy/no version byte).
     * @param data The payload with the version byte removed (or unchanged if legacy).
     */
    data class VersionResult(val version: Byte, val data: ByteArray)

    /**
     * Check if a payload starts with a known version byte.
     * If so, returns the version and the payload without the version prefix.
     * If not (legacy/magic byte), returns version 0 and the original payload.
     */
    @JvmStatic
    fun stripVersionByte(payload: ByteArray): VersionResult {
        if (payload.isEmpty()) {
            return VersionResult(0, payload)
        }

        val first = payload[0]

        // Check if first byte is a known magic (not a version byte).
        if (first == MAGIC_GPS_BRIDGE_FULL || first == MAGIC_GPS_BRIDGE_DELTA || first == MAGIC_CANNED_MESSAGE) {
            return VersionResult(0, payload) // Known payload type, no version byte
        }

        // Check if it's a known version byte.
        if (first == PROTO_VERSION_1) {
            return VersionResult(first, payload.copyOfRange(1, payload.size))
        }

        // Unknown first byte — treat as legacy (no version).
        return VersionResult(0, payload)
    }

    /**
     * Add the current protocol version byte to a payload.
     */
    @JvmStatic
    fun prependVersionByte(payload: ByteArray): ByteArray {
        val result = ByteArray(1 + payload.size)
        result[0] = PROTO_VERSION_1
        System.arraycopy(payload, 0, result, 1, payload.size)
        return result
    }

    /**
     * Log protocol version detection results.
     */
    @JvmStatic
    fun logVersionInfo(version: Byte, source: String) {
        when {
            version.toInt() == 0 -> Log.d(TAG, "protocol: legacy message from $source (no version byte)")
            version != PROTO_VERSION_1 -> Log.w(TAG, "protocol: version mismatch from $source (got $version, expected $PROTO_VERSION_1)")
            else -> Log.d(TAG, "protocol: v$version message from $source")
        }
    }
}
