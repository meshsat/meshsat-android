package com.cubeos.meshsat.ratelimit

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Token bucket rate limiter. Thread-safe.
 * Port of meshsat/internal/ratelimit/limiter.go.
 *
 * @param maxTokens Burst capacity (max tokens in bucket).
 * @param refillRate Tokens added per second.
 */
class TokenBucket(
    private val maxTokens: Double,
    private val refillRate: Double,
) {
    private val lock = ReentrantLock()
    private var tokens: Double = maxTokens
    private var lastRefill: Long = System.nanoTime()

    /** Check if a token is available and consume it. Returns true if allowed. */
    fun allow(): Boolean = lock.withLock {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    /** Current token count (for monitoring). */
    fun tokens(): Double = lock.withLock {
        refill()
        tokens
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefill) / 1_000_000_000.0
        lastRefill = now

        tokens = (tokens + elapsedSeconds * refillRate).coerceAtMost(maxTokens)
    }

    companion object {
        /**
         * Global limiter for injecting external messages into the mesh.
         * Default: max 6 messages per minute (1 per 10s).
         */
        fun meshInjectionLimiter(): TokenBucket = TokenBucket(6.0, 0.1)

        /**
         * Per-rule rate limiter from rule config.
         * @param perWindow Max messages per window.
         * @param windowSeconds Window duration in seconds.
         * @return TokenBucket, or null if no rate limit configured.
         */
        fun ruleLimiter(perWindow: Int, windowSeconds: Int): TokenBucket? {
            if (perWindow <= 0 || windowSeconds <= 0) return null
            val rate = perWindow.toDouble() / windowSeconds.toDouble()
            return TokenBucket(perWindow.toDouble(), rate)
        }
    }
}
