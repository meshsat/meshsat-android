package com.cubeos.meshsat.reticulum

/**
 * Reticulum Network Stack (RNS) protocol constants.
 * Wire-compatible with Python RNS reference implementation.
 *
 * Packet header byte 1 (flags) bit layout (MSB→LSB):
 *   [7:6] header_type   — 0=HEADER_1 (single addr), 1=HEADER_2 (transport, two addrs)
 *   [5]   context_flag  — 0=no context byte, 1=context byte present
 *   [4]   propagation   — 0=BROADCAST (local), 1=TRANSPORT (routed)
 *   [3:2] dest_type     — 0=SINGLE, 1=GROUP, 2=PLAIN, 3=LINK
 *   [1:0] packet_type   — 0=DATA, 1=ANNOUNCE, 2=LINKREQUEST, 3=PROOF
 *
 * Packet header byte 2: hop count (0–255)
 */
object RnsConstants {

    // --- Protocol ---
    const val MTU = 500
    const val HEADER_MINSIZE = 19   // 2 + 1 + 16 (HEADER_1)
    const val HEADER_MAXSIZE = 35   // 2 + 1 + 16 + 16 (HEADER_2)
    const val IFAC_MIN_SIZE = 1
    const val MDU = MTU - HEADER_MAXSIZE - IFAC_MIN_SIZE  // 464
    const val ENCRYPTED_MDU = 383

    // --- Hash / Key sizes ---
    const val TRUNCATED_HASHLENGTH = 128     // bits
    const val DEST_HASH_LEN = TRUNCATED_HASHLENGTH / 8  // 16 bytes
    const val NAME_HASH_LEN = 10            // 80 bits / 8
    const val IDENTITY_HASH_LEN = DEST_HASH_LEN  // 16 bytes
    const val FULL_HASH_LEN = 32            // SHA-256
    const val KEYSIZE = 512                 // bits (256 encryption + 256 signing)
    const val SIG_LEN = 64                  // Ed25519 signature
    const val PUB_KEY_LEN = 32              // X25519 or Ed25519 public key
    const val RATCHET_KEY_LEN = 32          // X25519 ratchet public key

    // --- Header types (2 bits, positions 7:6) ---
    const val HEADER_1 = 0x00              // Single destination address
    const val HEADER_2 = 0x01              // Transport: transport_id + destination

    // --- Packet types (2 bits, positions 1:0) ---
    const val PACKET_DATA = 0x00
    const val PACKET_ANNOUNCE = 0x01
    const val PACKET_LINKREQUEST = 0x02
    const val PACKET_PROOF = 0x03

    // --- Destination types (2 bits, positions 3:2) ---
    const val DEST_SINGLE = 0x00           // Ephemeral ECDH per-packet encryption
    const val DEST_GROUP = 0x01            // Pre-shared AES-256 symmetric key
    const val DEST_PLAIN = 0x02            // No encryption
    const val DEST_LINK = 0x03             // Per-link ECDH forward secrecy

    // --- Propagation types (1 bit, position 4) ---
    const val PROPAGATION_BROADCAST = 0x00 // Local delivery only
    const val PROPAGATION_TRANSPORT = 0x01 // Network-wide routing

    // --- Context types (1 byte, after address fields) ---
    const val CTX_NONE: Byte = 0x00
    const val CTX_RESOURCE: Byte = 0x01
    const val CTX_RESOURCE_ADV: Byte = 0x02
    const val CTX_RESOURCE_REQ: Byte = 0x03
    const val CTX_RESOURCE_HMU: Byte = 0x04
    const val CTX_RESOURCE_PRF: Byte = 0x05
    const val CTX_RESOURCE_ICL: Byte = 0x06
    const val CTX_RESOURCE_RCL: Byte = 0x07
    const val CTX_CACHE_REQUEST: Byte = 0x08
    const val CTX_REQUEST: Byte = 0x09
    const val CTX_RESPONSE: Byte = 0x0A
    const val CTX_PATH_RESPONSE: Byte = 0x0B
    const val CTX_COMMAND: Byte = 0x0C
    const val CTX_COMMAND_STATUS: Byte = 0x0D
    const val CTX_CHANNEL: Byte = 0x0E
    // Protocol enhancements (MESHSAT-407) — must match bridge wire format byte-for-byte
    const val CTX_TIME_SYNC_REQ: Byte = 0x14
    const val CTX_TIME_SYNC_RESP: Byte = 0x15
    const val CTX_CUSTODY_OFFER: Byte = 0x16
    const val CTX_CUSTODY_ACK: Byte = 0x17
    const val CTX_RLNC: Byte = 0x18

    const val CTX_KEEPALIVE: Byte = 0xFA.toByte()
    const val CTX_LINKIDENTIFY: Byte = 0xFB.toByte()
    const val CTX_LINKCLOSE: Byte = 0xFC.toByte()
    const val CTX_LINKPROOF: Byte = 0xFD.toByte()
    const val CTX_LRRTT: Byte = 0xFE.toByte()
    const val CTX_LRPROOF: Byte = 0xFF.toByte()

    // --- Announce ---
    const val RANDOM_HASH_LEN = 10         // 5 bytes random + 5 bytes timestamp
    const val MAX_HOPS = 128
    const val ANNOUNCE_CAP = 2             // percent of interface bandwidth

    // --- Link ---
    const val LINK_TIMEOUT_PER_HOP = 6     // seconds
    const val LINK_KEEPALIVE = 360         // seconds (default)
    const val LINK_STALE_TIME = 720        // seconds

    // --- Ratchet ---
    const val RATCHET_EXPIRY = 2_592_000   // seconds (30 days)
    const val RATCHET_ID_LEN = 10          // 80 bits
}
