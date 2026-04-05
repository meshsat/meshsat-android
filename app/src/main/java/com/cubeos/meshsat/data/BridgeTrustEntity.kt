package com.cubeos.meshsat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TOFU (Trust On First Use) pinning record for bridge signing keys (MESHSAT-495).
 *
 * When a `meshsat://key/` bundle is scanned for the first time from a given bridge
 * (identified by `bridgeHash`), we pin its Ed25519 signing public key here.
 * Subsequent imports from the same bridge hash must present the same pubkey, or
 * the import is rejected as a possible impersonation attempt.
 */
@Entity(tableName = "bridge_trust")
data class BridgeTrustEntity(
    /** Hex-encoded 16-byte bridge identifier from the bundle header. */
    @PrimaryKey val bridgeHash: String,

    /** Raw 32-byte Ed25519 public key pinned on first use. */
    val pubkey: ByteArray,

    /** Unix milliseconds of first import from this bridge. */
    val firstSeen: Long,

    /** Unix milliseconds of most recent import from this bridge. */
    val lastSeen: Long,

    /** User-editable friendly name (defaults to first 8 chars of bridgeHash). */
    val label: String,

    /** Number of successful imports from this bridge since first seen. */
    val importCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BridgeTrustEntity
        if (bridgeHash != other.bridgeHash) return false
        if (!pubkey.contentEquals(other.pubkey)) return false
        if (firstSeen != other.firstSeen) return false
        if (lastSeen != other.lastSeen) return false
        if (label != other.label) return false
        if (importCount != other.importCount) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bridgeHash.hashCode()
        result = 31 * result + pubkey.contentHashCode()
        result = 31 * result + firstSeen.hashCode()
        result = 31 * result + lastSeen.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + importCount
        return result
    }
}
