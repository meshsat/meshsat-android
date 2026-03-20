package com.cubeos.meshsat.reticulum

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reticulum wire-compatible packet header and framing.
 *
 * HEADER_1 layout (19+ bytes):
 *   [0]     flags byte (header_type | context_flag | propagation | dest_type | packet_type)
 *   [1]     hops
 *   [2:18]  destination hash (16 bytes)
 *   [18]    context byte (if context_flag set)
 *   [19..]  data/payload
 *
 * HEADER_2 layout (35+ bytes):
 *   [0]     flags byte
 *   [1]     hops
 *   [2:18]  transport ID (16 bytes)
 *   [18:34] destination hash (16 bytes)
 *   [34]    context byte (if context_flag set)
 *   [35..]  data/payload
 */
data class RnsPacket(
    val headerType: Int,         // HEADER_1 or HEADER_2
    val packetType: Int,         // DATA, ANNOUNCE, LINKREQUEST, PROOF
    val destType: Int,           // SINGLE, GROUP, PLAIN, LINK
    val propagationType: Int,    // BROADCAST or TRANSPORT
    val contextFlag: Boolean,    // whether context byte is present
    var hops: Int,               // hop count (0–255)
    val transportId: ByteArray?, // 16 bytes, only for HEADER_2
    val destHash: ByteArray,     // 16 bytes
    val context: Byte,           // context byte (CTX_* constant)
    val data: ByteArray,         // payload
) {
    /**
     * Encode the flags byte from header fields.
     *
     * Bit layout (MSB→LSB):
     *   [7:6] header_type
     *   [5]   context_flag
     *   [4]   propagation_type
     *   [3:2] dest_type
     *   [1:0] packet_type
     */
    fun encodeFlags(): Int {
        var flags = 0
        flags = flags or ((headerType and 0x03) shl 6)
        flags = flags or (if (contextFlag) 1 shl 5 else 0)
        flags = flags or ((propagationType and 0x01) shl 4)
        flags = flags or ((destType and 0x03) shl 2)
        flags = flags or (packetType and 0x03)
        return flags
    }

    /** Serialize to wire format. */
    fun marshal(): ByteArray {
        val headerSize = if (headerType == RnsConstants.HEADER_2)
            RnsConstants.HEADER_MAXSIZE else RnsConstants.HEADER_MINSIZE
        val contextSize = if (contextFlag) 1 else 0
        val totalSize = headerSize - 1 + contextSize + data.size
        // headerSize includes the context byte slot, so adjust

        val buf = ByteBuffer.allocate(2 + addressFieldSize() + contextSize + data.size)
        buf.order(ByteOrder.BIG_ENDIAN)

        // Byte 0: flags
        buf.put(encodeFlags().toByte())

        // Byte 1: hops
        buf.put((hops and 0xFF).toByte())

        // Address fields
        if (headerType == RnsConstants.HEADER_2) {
            buf.put(transportId ?: ByteArray(RnsConstants.DEST_HASH_LEN))
        }
        buf.put(destHash)

        // Context byte (if flagged)
        if (contextFlag) {
            buf.put(context)
        }

        // Payload
        buf.put(data)

        return buf.array()
    }

    /** Total size of address fields (16 or 32 bytes). */
    private fun addressFieldSize(): Int =
        if (headerType == RnsConstants.HEADER_2) RnsConstants.DEST_HASH_LEN * 2
        else RnsConstants.DEST_HASH_LEN

    /** Total wire size of this packet. */
    fun wireSize(): Int = 2 + addressFieldSize() + (if (contextFlag) 1 else 0) + data.size

    companion object {
        /**
         * Decode flags byte into individual fields.
         *
         * Returns: (headerType, contextFlag, propagationType, destType, packetType)
         */
        fun decodeFlags(flags: Int): FlagFields {
            return FlagFields(
                headerType = (flags shr 6) and 0x03,
                contextFlag = (flags shr 5) and 0x01 == 1,
                propagationType = (flags shr 4) and 0x01,
                destType = (flags shr 2) and 0x03,
                packetType = flags and 0x03,
            )
        }

        /** Minimum wire size for unmarshal: flags(1) + hops(1) + dest(16) = 18 */
        private const val UNMARSHAL_MIN = 2 + RnsConstants.DEST_HASH_LEN

        /** Parse a packet from wire format. */
        fun unmarshal(raw: ByteArray): RnsPacket {
            require(raw.size >= UNMARSHAL_MIN) {
                "packet too short: ${raw.size} < $UNMARSHAL_MIN"
            }

            val buf = ByteBuffer.wrap(raw)
            buf.order(ByteOrder.BIG_ENDIAN)

            val flagsByte = buf.get().toInt() and 0xFF
            val fields = decodeFlags(flagsByte)
            val hops = buf.get().toInt() and 0xFF

            // Read address fields
            var transportId: ByteArray? = null
            if (fields.headerType == RnsConstants.HEADER_2) {
                require(raw.size >= RnsConstants.HEADER_MAXSIZE) {
                    "HEADER_2 packet too short: ${raw.size} < ${RnsConstants.HEADER_MAXSIZE}"
                }
                transportId = ByteArray(RnsConstants.DEST_HASH_LEN)
                buf.get(transportId)
            }

            val destHash = ByteArray(RnsConstants.DEST_HASH_LEN)
            buf.get(destHash)

            // Context byte
            val context = if (fields.contextFlag) {
                require(buf.hasRemaining()) { "missing context byte" }
                buf.get()
            } else {
                RnsConstants.CTX_NONE
            }

            // Remaining bytes are payload
            val data = ByteArray(buf.remaining())
            if (data.isNotEmpty()) buf.get(data)

            return RnsPacket(
                headerType = fields.headerType,
                packetType = fields.packetType,
                destType = fields.destType,
                propagationType = fields.propagationType,
                contextFlag = fields.contextFlag,
                hops = hops,
                transportId = transportId,
                destHash = destHash,
                context = context,
                data = data,
            )
        }

        /** Create a HEADER_1 data packet. */
        fun data(
            destHash: ByteArray,
            payload: ByteArray,
            destType: Int = RnsConstants.DEST_SINGLE,
            context: Byte = RnsConstants.CTX_NONE,
        ): RnsPacket {
            val hasContext = context != RnsConstants.CTX_NONE
            return RnsPacket(
                headerType = RnsConstants.HEADER_1,
                packetType = RnsConstants.PACKET_DATA,
                destType = destType,
                propagationType = RnsConstants.PROPAGATION_BROADCAST,
                contextFlag = hasContext,
                hops = 0,
                transportId = null,
                destHash = destHash.copyOf(),
                context = context,
                data = payload,
            )
        }

        /** Create a HEADER_1 announce packet. */
        fun announce(
            destHash: ByteArray,
            announceData: ByteArray,
        ): RnsPacket {
            return RnsPacket(
                headerType = RnsConstants.HEADER_1,
                packetType = RnsConstants.PACKET_ANNOUNCE,
                destType = RnsConstants.DEST_SINGLE,
                propagationType = RnsConstants.PROPAGATION_BROADCAST,
                contextFlag = false,
                hops = 0,
                transportId = null,
                destHash = destHash.copyOf(),
                context = RnsConstants.CTX_NONE,
                data = announceData,
            )
        }

        /** Create a HEADER_1 link request packet. */
        fun linkRequest(
            destHash: ByteArray,
            requestData: ByteArray,
        ): RnsPacket {
            return RnsPacket(
                headerType = RnsConstants.HEADER_1,
                packetType = RnsConstants.PACKET_LINKREQUEST,
                destType = RnsConstants.DEST_SINGLE,
                propagationType = RnsConstants.PROPAGATION_BROADCAST,
                contextFlag = false,
                hops = 0,
                transportId = null,
                destHash = destHash.copyOf(),
                context = RnsConstants.CTX_NONE,
                data = requestData,
            )
        }

        /** Create a HEADER_1 proof packet. */
        fun proof(
            destHash: ByteArray,
            proofData: ByteArray,
            context: Byte = RnsConstants.CTX_NONE,
        ): RnsPacket {
            val hasContext = context != RnsConstants.CTX_NONE
            return RnsPacket(
                headerType = RnsConstants.HEADER_1,
                packetType = RnsConstants.PACKET_PROOF,
                destType = RnsConstants.DEST_SINGLE,
                propagationType = RnsConstants.PROPAGATION_BROADCAST,
                contextFlag = hasContext,
                hops = 0,
                transportId = null,
                destHash = destHash.copyOf(),
                context = context,
                data = proofData,
            )
        }

        /** Wrap a HEADER_1 packet for transport (adds transport_id, becomes HEADER_2). */
        fun wrapForTransport(
            packet: RnsPacket,
            transportId: ByteArray,
        ): RnsPacket {
            require(transportId.size == RnsConstants.DEST_HASH_LEN) {
                "transport ID must be ${RnsConstants.DEST_HASH_LEN} bytes"
            }
            return packet.copy(
                headerType = RnsConstants.HEADER_2,
                propagationType = RnsConstants.PROPAGATION_TRANSPORT,
                transportId = transportId.copyOf(),
            )
        }

        /** Validate packet size against Reticulum MTU. */
        fun validateSize(packet: RnsPacket): Boolean {
            return packet.wireSize() <= RnsConstants.MTU
        }
    }
}

/** Decoded flag fields from the first header byte. */
data class FlagFields(
    val headerType: Int,
    val contextFlag: Boolean,
    val propagationType: Int,
    val destType: Int,
    val packetType: Int,
)
