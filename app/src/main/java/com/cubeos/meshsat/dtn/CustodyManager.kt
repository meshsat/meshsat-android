package com.cubeos.meshsat.dtn

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages DTN custody transfer — point-to-point, NOT broadcast.
 *
 * Custody is a handshake between sender and specific next hop:
 * 1. Sender prepends CustodyOffer (0x16) header to payload in delivery pipeline
 * 2. Receiver extracts payload, queues locally, sends CustodyACK (0x17) back on SAME interface
 * 3. Sender receives ACK, marks delivery as custody_transferred
 */
class CustodyManager(
    private val localDestHash: ByteArray, // 16 bytes — this node's Reticulum destination
    private val maxCustodyItems: Int = 100,
) {
    companion object {
        private const val TAG = "CustodyManager"
    }

    enum class CustodyStatus { OFFERED, ACCEPTED, TRANSFERRED, FAILED }

    data class CustodyRecord(
        val custodyId: ByteArray,
        val deliveryId: Int,
        val acceptedAt: Long = System.currentTimeMillis(),
        var status: CustodyStatus = CustodyStatus.ACCEPTED,
    ) {
        override fun equals(other: Any?): Boolean = other is CustodyRecord && custodyId.contentEquals(other.custodyId)
        override fun hashCode(): Int = custodyId.contentHashCode()
    }

    private val custody = ConcurrentHashMap<String, CustodyRecord>()

    /** Check if this node can accept custody (has capacity). */
    fun canAcceptCustody(): Boolean = custody.size < maxCustodyItems

    /**
     * Create a custody offer to wrap a payload for delivery.
     * Called in the delivery pipeline before sending.
     */
    fun createOffer(payload: ByteArray, deliveryId: Int): DtnProtocol.CustodyOffer {
        val custodyId = DtnProtocol.generateId()
        custody[custodyId.toHex()] = CustodyRecord(custodyId, deliveryId, status = CustodyStatus.OFFERED)
        return DtnProtocol.CustodyOffer(custodyId, localDestHash, deliveryId, payload)
    }

    /**
     * Handle a received custody offer — accept and create local delivery.
     * Returns the CustodyACK to send back on the SAME transport (point-to-point).
     */
    fun acceptOffer(offer: DtnProtocol.CustodyOffer, signingCallback: (ByteArray) -> ByteArray): DtnProtocol.CustodyAck? {
        if (!canAcceptCustody()) {
            Log.d(TAG, "Custody rejected: at capacity ($maxCustodyItems)")
            return null
        }
        val key = offer.custodyId.toHex()
        custody[key] = CustodyRecord(offer.custodyId, offer.deliveryId, status = CustodyStatus.ACCEPTED)
        Log.i(TAG, "Custody accepted: $key (${offer.payload.size} bytes)")

        // Sign (custodyId || acceptorHash) with Ed25519
        val signData = offer.custodyId + localDestHash
        val signature = signingCallback(signData)
        return DtnProtocol.CustodyAck(offer.custodyId, localDestHash, signature)
    }

    /**
     * Handle a received custody ACK — mark our offer as transferred.
     * Returns true if matched a pending offer.
     */
    fun processAck(ack: DtnProtocol.CustodyAck): Boolean {
        val key = ack.custodyId.toHex()
        val record = custody[key] ?: return false
        record.status = CustodyStatus.TRANSFERRED
        custody.remove(key)
        Log.i(TAG, "Custody transferred: $key → ${ack.acceptorHash.toHex().take(8)}")
        return true
    }

    /** Number of bundles currently in custody. */
    val custodyCount: Int get() = custody.size

    /** Get the delivery ID for a custody record (to update delivery ledger). */
    fun getDeliveryId(custodyId: ByteArray): Int? = custody[custodyId.toHex()]?.deliveryId

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
