package com.bufferbloatshaper.shaping

/**
 * Token bucket rate limiter — the base rate-enforcement primitive (§3, Layer 1).
 *
 * Tokens accumulate at [rateBytesPerSec]; each byte sent consumes a token.
 * Bucket capacity caps burst size so the shaper itself doesn't introduce bursts.
 *
 * Per the plan: ~20ms worth of burst allowance by default, so you're not
 * perfectly rigid on every packet.
 */
class TokenBucket(
    @Volatile var rateBytesPerSec: Double,
    burstBytes: Long = (rateBytesPerSec * 0.02).toLong().coerceAtLeast(1500)
) {
    @Volatile
    var burstCapacity: Long = burstBytes
        private set

    private var tokens: Double = burstBytes.toDouble()
    private var lastRefillNs: Long = System.nanoTime()

    /**
     * Try to consume [bytes] tokens. Returns true if enough tokens were
     * available and consumed; false if the caller should wait.
     */
    @Synchronized
    fun tryConsume(bytes: Int): Boolean {
        if (rateBytesPerSec <= 0.0) return true
        refill()
        if (tokens < bytes) return false
        tokens -= bytes
        return true
    }

    /**
     * How many nanoseconds until [bytes] tokens will be available.
     * Returns 0 if tokens are already available or rate limit is disabled (<= 0).
     */
    @Synchronized
    fun nanosUntilAvailable(bytes: Int): Long {
        if (rateBytesPerSec <= 0.0) return 0L
        refill()
        val deficit = bytes - tokens
        return if (deficit <= 0) 0L
        else ((deficit / rateBytesPerSec) * 1_000_000_000L).toLong()
    }

    /**
     * Update the rate dynamically (used by CalibrationEngine).
     * Recalculates burst capacity proportionally.
     */
    @Synchronized
    fun updateRate(newRateBytesPerSec: Double, burstFraction: Double = 0.02) {
        refill() // settle current state before changing rate
        rateBytesPerSec = newRateBytesPerSec
        burstCapacity = (newRateBytesPerSec * burstFraction).toLong().coerceAtLeast(1500)
        // Don't let existing tokens exceed new burst capacity
        tokens = tokens.coerceAtMost(burstCapacity.toDouble())
    }

    /** Current number of available tokens. */
    @Synchronized
    fun availableTokens(): Double {
        refill()
        return tokens
    }

    /** Current fill ratio (0.0–1.0). */
    @Synchronized
    fun fillRatio(): Double {
        refill()
        return if (burstCapacity > 0) tokens / burstCapacity else 0.0
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillNs) / 1_000_000_000.0
        tokens = (tokens + elapsedSec * rateBytesPerSec).coerceAtMost(burstCapacity.toDouble())
        lastRefillNs = now
    }
}
