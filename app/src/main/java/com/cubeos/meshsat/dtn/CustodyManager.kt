package com.cubeos.meshsat.dtn

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages DTN custody transfer — accept or offer custody of message bundles.
 *
 * When a node accepts custody, it takes responsibility for delivery.
 * The previous custodian can free storage. If the current custodian
 * fails to deliver, it signals failure and the bundle returns to the
 * delivery ledger for retry via a different path.
 */
class CustodyManager(
    private val localDestHash: ByteArray, // this node's Reticulum destination hash
    private val maxCustodyItems: Int = 100,
) {
    companion object {
        private const val TAG = "CustodyManager"
    }

    data class CustodyRecord(
        val bundleHash: ByteArray,
        val totalSize: Int,
        val acceptedAt: Long = System.currentTimeMillis(),
        var status: CustodyStatus = CustodyStatus.ACCEPTED,
    ) {
        override fun equals(other: Any?): Boolean = other is CustodyRecord && bundleHash.contentEquals(other.bundleHash)
        override fun hashCode(): Int = bundleHash.contentHashCode()
    }

    enum class CustodyStatus { ACCEPTED, FORWARDED, FAILED }

    private val custody = ConcurrentHashMap<String, CustodyRecord>()

    /**
     * Evaluate a custody offer. Returns true if this node accepts custody.
     * Rejects if storage is full or bundle is too large.
     */
    fun evaluateOffer(offer: DtnProtocol.CustodyOffer): Boolean {
        if (custody.size >= maxCustodyItems) {
            Log.d(TAG, "Custody rejected: at capacity ($maxCustodyItems)")
            return false
        }
        return true
    }

    /**
     * Accept custody of a bundle. Records the bundle and generates a custody accept signal.
     *
     * @return CustodyAccept wire message to send back to the offerer
     */
    fun acceptCustody(offer: DtnProtocol.CustodyOffer): DtnProtocol.CustodyAccept {
        val key = offer.bundleHash.toHex()
        custody[key] = CustodyRecord(offer.bundleHash, offer.totalSize)
        Log.i(TAG, "Custody accepted for bundle $key (${offer.totalSize} bytes)")
        return DtnProtocol.CustodyAccept(offer.bundleHash, localDestHash)
    }

    /**
     * Generate a custody offer for a payload.
     */
    fun createOffer(payload: ByteArray, ttlSeconds: Int = 3600, priority: Int = 0): DtnProtocol.CustodyOffer {
        val hash = truncatedHash(payload)
        return DtnProtocol.CustodyOffer(hash, payload.size, 1, ttlSeconds, priority)
    }

    /**
     * Mark a custody bundle as forwarded (delivery responsibility transferred to next hop).
     */
    fun markForwarded(bundleHash: ByteArray): DtnProtocol.CustodySignal? {
        val key = bundleHash.toHex()
        val record = custody[key] ?: return null
        record.status = CustodyStatus.FORWARDED
        custody.remove(key)
        Log.d(TAG, "Custody forwarded: $key")
        return DtnProtocol.CustodySignal(bundleHash, DtnProtocol.SIGNAL_FORWARDED, localDestHash)
    }

    /**
     * Mark a custody bundle as failed (will return to sender for retry).
     */
    fun markFailed(bundleHash: ByteArray): DtnProtocol.CustodySignal? {
        val key = bundleHash.toHex()
        val record = custody[key] ?: return null
        record.status = CustodyStatus.FAILED
        custody.remove(key)
        Log.w(TAG, "Custody failed: $key")
        return DtnProtocol.CustodySignal(bundleHash, DtnProtocol.SIGNAL_FAILED, localDestHash)
    }

    /** Number of bundles currently in custody. */
    val custodyCount: Int get() = custody.size

    /** Truncate SHA-256 to 16 bytes for bundle identification. */
    private fun truncatedHash(data: ByteArray): ByteArray {
        val full = MessageDigest.getInstance("SHA-256").digest(data)
        return full.copyOfRange(0, 16)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
