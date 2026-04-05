package com.cubeos.meshsat.engine

import android.util.Log
import com.cubeos.meshsat.data.AuditLogDao
import com.cubeos.meshsat.data.AuditLogEntity
import com.cubeos.meshsat.routing.KeyValueStore
import com.cubeos.meshsat.routing.toHex
import com.cubeos.meshsat.routing.hexToBytes
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ed25519 signing service with tamper-evident SHA-256 hash-chain audit logging.
 * Port of meshsat/internal/engine/signing.go.
 *
 * Generates or loads an Ed25519 keypair, persists it via KeyValueStore,
 * and records audit events as an immutable hash chain in Room.
 */
class SigningService(
    private val auditDao: AuditLogDao,
    store: KeyValueStore,
) {
    private val lock = Mutex()
    private val signingPrivate: PrivateKey
    private val signingPublic: PublicKey

    /** Hex-encoded public key (signer ID). */
    val signerId: String

    private var lastHash: String = ""

    init {
        val privHex = store.get(KEY_PRIVATE)
        val pubHex = store.get(KEY_PUBLIC)

        if (privHex != null && pubHex != null) {
            val factory = KeyFactory.getInstance("Ed25519", "BC")
            signingPrivate = factory.generatePrivate(PKCS8EncodedKeySpec(privHex.hexToBytes()))
            signingPublic = factory.generatePublic(X509EncodedKeySpec(pubHex.hexToBytes()))
        } else {
            val kp = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
            signingPrivate = kp.private
            signingPublic = kp.public
            store.set(KEY_PRIVATE, signingPrivate.encoded.toHex())
            store.set(KEY_PUBLIC, signingPublic.encoded.toHex())
        }

        // Extract raw 32-byte public key from X.509 DER (12-byte header + 32 bytes)
        val pubEncoded = signingPublic.encoded
        val rawPub = if (pubEncoded.size == 44) pubEncoded.copyOfRange(12, 44) else pubEncoded
        signerId = rawPub.toHex()

        Log.i(TAG, "signing service initialized: ${signerId.take(16)}...")
    }

    /** Sign data with Ed25519. Returns 64-byte signature. */
    fun sign(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519", "BC")
        sig.initSign(signingPrivate)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Load last hash from DB for chain continuity.
     * Must be called from a coroutine after construction.
     */
    suspend fun loadLastHash() {
        val entries = auditDao.getRecent(1)
        if (entries.isNotEmpty()) {
            lastHash = entries[0].hash
        }
    }

    /**
     * Record a tamper-evident audit log entry with hash chain.
     * Must be called from a coroutine.
     */
    suspend fun auditEvent(
        eventType: String,
        interfaceId: String? = null,
        direction: String? = null,
        deliveryId: Long? = null,
        ruleId: Long? = null,
        detail: String = "",
    ) {
        lock.withLock {
            val now = UTC_FORMAT.get()!!.format(Date())

            // Hash chain: SHA-256(prev_hash + timestamp + event_type + detail)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(lastHash.toByteArray())
            digest.update(now.toByteArray())
            digest.update(eventType.toByteArray())
            digest.update(detail.toByteArray())
            val hash = digest.digest().toHex()

            val entry = AuditLogEntity(
                timestamp = now,
                interfaceId = interfaceId,
                direction = direction,
                eventType = eventType,
                deliveryId = deliveryId,
                ruleId = ruleId,
                detail = detail,
                prevHash = lastHash,
                hash = hash,
            )

            try {
                auditDao.insert(entry)
                lastHash = hash
            } catch (e: Exception) {
                Log.e(TAG, "audit log insert failed: ${e.message}")
            }
        }
    }

    /**
     * Verify the integrity of the last N audit log entries.
     * Returns (validCount, brokenAtIndex). brokenAtIndex is -1 if all valid.
     */
    suspend fun verifyChain(limit: Int = 1000): Pair<Int, Int> {
        val entries = auditDao.getRecent(limit)
        if (entries.isEmpty()) return Pair(0, -1)

        // Entries are newest-first; reverse for chain verification
        val ordered = entries.reversed()

        for (i in ordered.indices) {
            val entry = ordered[i]
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(entry.prevHash.toByteArray())
            digest.update(entry.timestamp.toByteArray())
            digest.update(entry.eventType.toByteArray())
            digest.update(entry.detail.toByteArray())
            val expected = digest.digest().toHex()

            if (entry.hash != expected) {
                return Pair(i, i)
            }
        }

        return Pair(ordered.size, -1)
    }

    companion object {
        private const val TAG = "SigningService"
        private const val KEY_PRIVATE = "signing_private_key"
        private const val KEY_PUBLIC = "signing_public_key"

        private val UTC_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            }
        }

        /** Verify an Ed25519 signature against a hex-encoded public key. */
        fun verify(pubKeyHex: String, data: ByteArray, signature: ByteArray): Boolean {
            return try {
                val pubBytes = pubKeyHex.hexToBytes()
                // Reconstruct X.509 DER from raw 32-byte key
                val der = if (pubBytes.size == 32) {
                    ED25519_X509_PREFIX + pubBytes
                } else {
                    pubBytes
                }
                val pubKey = KeyFactory.getInstance("Ed25519", "BC")
                    .generatePublic(X509EncodedKeySpec(der))
                val sig = Signature.getInstance("Ed25519", "BC")
                sig.initVerify(pubKey)
                sig.update(data)
                sig.verify(signature)
            } catch (_: Exception) {
                false
            }
        }

        private val ED25519_X509_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )
    }
}
