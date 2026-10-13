package net.meshsat.android.lxmf

import net.meshsat.android.rns.Hkdf
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.Msgpack
import net.meshsat.android.rns.Rns
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * LXMF stamps (LXMF/LXStamper.py): proof of work a receiver can demand. The
 * workblock is 3000 HKDF expansions of the message id, each salted with
 * SHA-256(id || msgpack(n)); a stamp is 32 random bytes such that
 * SHA-256(workblock || stamp) has at least [cost] leading zero bits.
 * [MESHSAT-1553]
 */
object LxStamper {
    const val WORKBLOCK_EXPAND_ROUNDS = 3000
    /** A propagation stamp (over the transient id, for a propagation node) expands 1000 rounds. */
    const val WORKBLOCK_EXPAND_ROUNDS_PN = 1000
    const val STAMP_SIZE = 32

    fun workblock(messageId: ByteArray, expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS): ByteArray {
        val out = ByteArray(expandRounds * 256)
        for (n in 0 until expandRounds) {
            val salt = Rns.sha256(messageId, Msgpack.pack(MpInt(n.toLong())))
            Hkdf.derive(256, messageId, salt).copyInto(out, n * 256)
        }
        return out
    }

    /** The number of leading zero bits of SHA-256(workblock || stamp). */
    fun stampValue(workblock: ByteArray, stamp: ByteArray): Int {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(workblock); d.update(stamp)
        return leadingZeroBits(d.digest())
    }

    private fun leadingZeroBits(h: ByteArray): Int {
        var value = 0
        for (b in h) {
            val x = b.toInt() and 0xFF
            if (x == 0) { value += 8; continue }
            value += Integer.numberOfLeadingZeros(x) - 24
            break
        }
        return value
    }

    fun stampValid(workblock: ByteArray, stamp: ByteArray, cost: Int): Boolean {
        if (cost <= 0 || cost >= 256 || stamp.isEmpty()) return false
        return stampValue(workblock, stamp) >= cost
    }

    /**
     * Searches random stamps until one meets [cost], on every core, or returns
     * null when [cancelled] says so first. Returns the stamp and its value.
     */
    fun generateStamp(messageId: ByteArray, cost: Int, cancelled: () -> Boolean = { false }, expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS): Pair<ByteArray, Int>? {
        if (cost <= 0) return null
        val wb = workblock(messageId, expandRounds)
        val found = AtomicBoolean(false)
        val result = AtomicReference<ByteArray>()
        val workers = maxOf(1, Runtime.getRuntime().availableProcessors())
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "lxmf-stamp").apply { isDaemon = true } }
        try {
            val tasks = (0 until workers).map {
                pool.submit {
                    val rng = java.security.SecureRandom()
                    val stamp = ByteArray(STAMP_SIZE)
                    val d = MessageDigest.getInstance("SHA-256")
                    var i = 0
                    while (!found.get()) {
                        if (i++ % 64 == 0 && cancelled()) return@submit
                        rng.nextBytes(stamp)
                        d.reset(); d.update(wb); d.update(stamp)
                        if (leadingZeroBits(d.digest()) >= cost) {
                            if (found.compareAndSet(false, true)) result.set(stamp.copyOf())
                            return@submit
                        }
                    }
                }
            }
            tasks.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val s = result.get() ?: return null
        return s to stampValue(wb, s)
    }
}
