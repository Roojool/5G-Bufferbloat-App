package com.bufferbloatshaper.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VpnRuntimeStateStoreTest {

    @Before
    fun resetBefore() = VpnRuntimeStateStore.resetForTests()

    @After
    fun resetAfter() = VpnRuntimeStateStore.resetForTests()

    @Test
    fun timelineRetainsOnlyTheMostRecentHundredMeaningfulTransitions() {
        repeat(101) { index ->
            VpnRuntimeStateStore.publish(
                VpnRuntimeState(
                    generation = (index + 1).toLong(),
                    detail = "Lifecycle transition $index"
                )
            )
        }

        val events = VpnRuntimeStateStore.timeline.value

        assertEquals(100, events.size)
        assertEquals(2L, events.first().generation)
        assertEquals(101L, events.last().generation)
    }

    @Test
    fun metricOnlyUpdateDoesNotPolluteHealthTimeline() {
        VpnRuntimeStateStore.update {
            it.copy(metrics = VpnRuntimeMetrics(bytesEgressed = 1_024))
        }

        assertTrue(VpnRuntimeStateStore.timeline.value.isEmpty())
    }
}
