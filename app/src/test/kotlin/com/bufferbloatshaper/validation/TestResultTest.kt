package com.bufferbloatshaper.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class TestResultTest {

    @Test
    fun gradesUseDocumentedBoundaryValues() {
        assertEquals(TestResult.Grade.A, TestResult.gradeFromAddedLatency(4.999))
        assertEquals(TestResult.Grade.B, TestResult.gradeFromAddedLatency(5.0))
        assertEquals(TestResult.Grade.B, TestResult.gradeFromAddedLatency(29.999))
        assertEquals(TestResult.Grade.C, TestResult.gradeFromAddedLatency(30.0))
        assertEquals(TestResult.Grade.C, TestResult.gradeFromAddedLatency(59.999))
        assertEquals(TestResult.Grade.D, TestResult.gradeFromAddedLatency(60.0))
        assertEquals(TestResult.Grade.D, TestResult.gradeFromAddedLatency(199.999))
        assertEquals(TestResult.Grade.F, TestResult.gradeFromAddedLatency(200.0))
    }

    @Test
    fun addedLatencyDefaultsToLoadedMinusIdleAndPreservesMeasurements() {
        val result = TestResult(
            idleLatencyMs = 18.5,
            loadedLatencyMs = 42.75,
            grade = TestResult.gradeFromAddedLatency(42.75 - 18.5),
            shapingActive = true,
            timestampMs = 123_456L,
            downloadBytesSec = 12_500_000L,
            uploadBytesSec = 5_000_000L
        )

        assertEquals(24.25, result.addedLatencyMs, 0.0)
        assertEquals(TestResult.Grade.B, result.grade)
        assertEquals(123_456L, result.timestampMs)
        assertEquals(12_500_000L, result.downloadBytesSec)
        assertEquals(5_000_000L, result.uploadBytesSec)
    }
}
