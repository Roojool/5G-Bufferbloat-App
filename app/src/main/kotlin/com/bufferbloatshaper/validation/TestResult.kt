package com.bufferbloatshaper.validation

/**
 * Result of a bufferbloat test (§6).
 */
data class TestResult(
    /** Latency under idle conditions in milliseconds. */
    val idleLatencyMs: Double,

    /** Latency under load in milliseconds. */
    val loadedLatencyMs: Double,

    /** Added latency due to bufferbloat (loaded - idle). */
    val addedLatencyMs: Double = loadedLatencyMs - idleLatencyMs,

    /** Bufferbloat grade (A–F). */
    val grade: Grade,

    /** Whether shaping was active during this test. */
    val shapingActive: Boolean,

    /** Timestamp of the test. */
    val timestampMs: Long = System.currentTimeMillis(),

    /** Measured download speed during the test (bytes/sec). */
    val downloadBytesSec: Long = 0L,

    /** Measured upload speed during the test (bytes/sec). */
    val uploadBytesSec: Long = 0L
) {
    /**
     * Bufferbloat grade based on added latency under load.
     * Roughly mirrors Waveform-style grading (§8):
     *   A: < 5ms added — excellent
     *   B: < 30ms added — good (§8 target)
     *   C: < 60ms added — fair
     *   D: < 200ms added — poor
     *   F: >= 200ms added — failing (typical unbuffered connection)
     */
    enum class Grade(val label: String, val description: String) {
        A("A", "Excellent — negligible bufferbloat"),
        B("B", "Good — minor bufferbloat, within target"),
        C("C", "Fair — noticeable bufferbloat"),
        D("D", "Poor — significant bufferbloat"),
        F("F", "Failing — severe bufferbloat")
    }

    companion object {
        fun gradeFromAddedLatency(addedMs: Double): Grade = when {
            addedMs < 5 -> Grade.A
            addedMs < 30 -> Grade.B
            addedMs < 60 -> Grade.C
            addedMs < 200 -> Grade.D
            else -> Grade.F
        }
    }
}
