package com.bufferbloatshaper.shaping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {

    @Test
    fun disabledBucketAllowsTrafficAndNeverRequiresWaiting() {
        val bucket = TokenBucket(rateBytesPerSec = 0.0, burstBytes = 1)

        assertTrue(bucket.tryConsume(10_000_000))
        assertEquals(0L, bucket.nanosUntilAvailable(10_000_000))
    }

    @Test
    fun enabledBucketRejectsTrafficBeyondItsInitialBurst() {
        // The deliberately tiny non-zero rate prevents a test run from refilling a
        // meaningful number of tokens, without sleeping or depending on scheduling.
        val bucket = TokenBucket(rateBytesPerSec = 0.000001, burstBytes = 1_500)

        assertTrue(bucket.tryConsume(1_500))
        assertFalse(bucket.tryConsume(1))
        assertTrue(bucket.nanosUntilAvailable(1) > 0L)
        assertEquals(0.0, bucket.fillRatio(), 0.000001)
    }

    @Test
    fun updatingRateRecalculatesBurstCapacityAndKeepsTokensBounded() {
        val bucket = TokenBucket(rateBytesPerSec = 10_000.0, burstBytes = 2_000)

        bucket.updateRate(newRateBytesPerSec = 100_000.0, burstFraction = 0.05)

        assertEquals(100_000.0, bucket.rateBytesPerSec, 0.0)
        assertEquals(5_000L, bucket.burstCapacity)
        val available = bucket.availableTokens()
        assertTrue(available >= 2_000.0)
        assertTrue(available <= bucket.burstCapacity.toDouble())
    }

    @Test
    fun rateUpdateKeepsTheMinimumMtuBurstAllowance() {
        val bucket = TokenBucket(rateBytesPerSec = 10_000.0, burstBytes = 2_000)

        bucket.updateRate(newRateBytesPerSec = 1.0, burstFraction = 0.02)

        assertEquals(1_500L, bucket.burstCapacity)
        assertTrue(bucket.availableTokens() <= bucket.burstCapacity.toDouble())
    }
}
