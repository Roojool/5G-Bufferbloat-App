package com.bufferbloatshaper.shaping

/**
 * CoDel (Controlled Delay) active queue management — RFC 8289 (§3, Layer 2).
 *
 * Tracks how long each packet sits in its queue (sojourn time).
 * If sojourn time stays above a target (5ms default) for a sustained interval
 * (100ms default), starts dropping packets at an increasing rate.
 *
 * This is what prevents the shaper's own queue from becoming a second
 * bufferbloat source: a queue that's actively kept shallow can't add
 * hundreds of ms of latency.
 *
 * Drop rate follows the inverse-square-root schedule from RFC 8289:
 * after the first drop, subsequent drops occur at intervals of
 * interval / sqrt(dropCount), ensuring increasingly aggressive action
 * against persistent congestion while quickly backing off when the queue clears.
 */
class CodelAqm(
    private val targetSojournMs: Long = 5,
    private val intervalMs: Long = 100
) {
    /** Whether we're currently in the dropping state. */
    var dropState: Boolean = false
        private set

    /** Timestamp when sojourn first exceeded target. */
    private var firstAboveTargetNs: Long = 0L

    /** Number of drops in the current drop cycle. */
    var dropCount: Int = 0
        private set

    /** Scheduled time for the next drop. */
    private var nextDropNs: Long = 0L

    /** Total drops since creation (for stats). */
    var totalDrops: Long = 0L
        private set

    /** Last observed sojourn time (for stats). */
    var lastSojournMs: Long = 0L
        private set

    /**
     * Decide whether to drop this packet based on its sojourn time.
     *
     * Call once per packet at dequeue time. If true is returned,
     * the caller should drop this packet (or apply ECN marking as
     * an enhancement — treat as a v1 follow-up per §3).
     *
     * @param sojournNs How long this packet has been queued, in nanoseconds.
     * @param nowNs Current time from System.nanoTime().
     * @return true if this packet should be dropped.
     */
    fun shouldDrop(sojournNs: Long, nowNs: Long): Boolean {
        val sojournMs = sojournNs / 1_000_000
        lastSojournMs = sojournMs

        // If sojourn time is below target, reset everything — queue is healthy
        if (sojournMs < targetSojournMs) {
            firstAboveTargetNs = 0L
            dropState = false
            return false
        }

        // Sojourn is above target — record when this started
        if (firstAboveTargetNs == 0L) firstAboveTargetNs = nowNs

        // Check if we've been above target for a full interval
        val sustainedMs = (nowNs - firstAboveTargetNs) / 1_000_000
        if (sustainedMs < intervalMs) return false

        // We've been congested for a full interval
        if (!dropState) {
            // Enter dropping state — first drop
            dropState = true
            dropCount = 1
            nextDropNs = nowNs
            totalDrops++
            return true
        }

        // Already in dropping state — check if it's time for the next drop
        if (nowNs < nextDropNs) return false

        // Time for another drop — increase drop rate (inverse sqrt schedule)
        dropCount++
        val gapMs = (intervalMs / kotlin.math.sqrt(dropCount.toDouble())).toLong().coerceAtLeast(1)
        nextDropNs = nowNs + gapMs * 1_000_000
        totalDrops++
        return true
    }

    /**
     * Reset the AQM state. Call when the flow's queue is fully drained
     * or the flow becomes inactive.
     */
    fun reset() {
        dropState = false
        firstAboveTargetNs = 0L
        dropCount = 0
        nextDropNs = 0L
        lastSojournMs = 0L
    }
}
