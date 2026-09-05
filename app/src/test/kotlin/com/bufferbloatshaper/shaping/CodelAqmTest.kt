package com.bufferbloatshaper.shaping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodelAqmTest {

    private val millisecond = 1_000_000L

    @Test
    fun onlyDropsAfterSojournHasExceededTargetForTheFullInterval() {
        val codel = CodelAqm(targetSojournMs = 5, intervalMs = 100)
        val start = 1_000L * millisecond

        assertFalse(codel.shouldDrop(5 * millisecond, start))
        assertFalse(codel.shouldDrop(5 * millisecond, start + 99 * millisecond))
        assertTrue(codel.shouldDrop(5 * millisecond, start + 100 * millisecond))

        assertTrue(codel.dropState)
        assertEquals(1, codel.dropCount)
        assertEquals(1L, codel.totalDrops)
        assertEquals(5L, codel.lastSojournMs)
    }

    @Test
    fun droppingScheduleAcceleratesAndWaitsUntilNextScheduledDrop() {
        val codel = CodelAqm(targetSojournMs = 5, intervalMs = 100)
        val firstObservation = 10_000L * millisecond
        val firstDrop = firstObservation + 100 * millisecond

        assertFalse(codel.shouldDrop(10 * millisecond, firstObservation))
        assertTrue(codel.shouldDrop(10 * millisecond, firstDrop))
        assertTrue(codel.shouldDrop(10 * millisecond, firstDrop))
        assertEquals(2, codel.dropCount)

        // floor(100 / sqrt(2)) is 70ms, so an early dequeue must not drop.
        assertFalse(codel.shouldDrop(10 * millisecond, firstDrop + 69 * millisecond))
        assertTrue(codel.shouldDrop(10 * millisecond, firstDrop + 70 * millisecond))
        assertEquals(3L, codel.totalDrops)
    }

    @Test
    fun healthyQueueLeavesDroppingStateAndResetClearsCycleState() {
        val codel = CodelAqm(targetSojournMs = 5, intervalMs = 100)
        val start = 5_000L * millisecond

        assertFalse(codel.shouldDrop(8 * millisecond, start))
        assertTrue(codel.shouldDrop(8 * millisecond, start + 100 * millisecond))
        assertFalse(codel.shouldDrop(4 * millisecond, start + 101 * millisecond))
        assertFalse(codel.dropState)

        codel.reset()

        assertFalse(codel.dropState)
        assertEquals(0, codel.dropCount)
        assertEquals(0L, codel.lastSojournMs)
        assertEquals(1L, codel.totalDrops)
    }
}
