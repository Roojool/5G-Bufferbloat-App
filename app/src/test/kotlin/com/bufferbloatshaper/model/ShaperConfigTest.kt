package com.bufferbloatshaper.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaperConfigTest {

    @Test
    fun megabitsAndBytesPerSecondRoundTripAtCommonRates() {
        val rates = listOf(0.0, 0.001, 1.0, 25.0, 1_000.0)

        rates.forEach { mbps ->
            val bytesPerSec = ShaperConfig.mbpsToBytesSec(mbps)
            assertEquals(mbps, ShaperConfig.bytesSecToMbps(bytesPerSec), 0.000001)
        }
    }

    @Test
    fun defaultConfigurationIsInactiveAndUsesConservativeAqmDefaults() {
        val config = ShaperConfig()

        assertEquals(0L, config.egressRateBytesPerSec)
        assertEquals(0L, config.ingressRateBytesPerSec)
        assertFalse(config.isActive)
        assertFalse(config.autoCalibrationEnabled)
        assertEquals(0.85, config.headroomFactor, 0.0)
        assertEquals(5L, config.codelTargetMs)
        assertEquals(100L, config.codelIntervalMs)
        assertEquals(1_024, config.fqBuckets)
        assertEquals(0.02, config.burstFraction, 0.0)
    }

    @Test
    fun copyRetainsUnchangedValuesWhenApplyingManualRates() {
        val automatic = ShaperConfig(
            autoCalibrationEnabled = true,
            smartModeEnabled = true,
            headroomFactor = 0.9,
            codelTargetMs = 10,
            codelIntervalMs = 200,
            fqBuckets = 64,
            burstFraction = 0.04
        )

        val manual = automatic.copy(
            egressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(40.0),
            ingressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(100.0),
            isActive = true
        )

        assertEquals(5_000_000L, manual.egressRateBytesPerSec)
        assertEquals(12_500_000L, manual.ingressRateBytesPerSec)
        assertEquals(automatic.headroomFactor, manual.headroomFactor, 0.0)
        assertEquals(automatic.codelTargetMs, manual.codelTargetMs)
        assertEquals(automatic.fqBuckets, manual.fqBuckets)
        assertEquals(automatic.smartModeEnabled, manual.smartModeEnabled)
    }

    @Test
    fun profilesApplyDocumentedQueueSettingsAndCustomPreservesChoices() {
        val base = ShaperConfig(
            egressRateBytesPerSec = 1_000_000L,
            ingressRateBytesPerSec = 2_000_000L,
            headroomFactor = 0.77,
            codelTargetMs = 37,
            burstFraction = 0.07
        )

        val safe = base.withProfile(ShaperProfile.SAFE)
        val maximum = base.withProfile(ShaperProfile.MAXIMUM_THROUGHPUT)
        val custom = base.withProfile(ShaperProfile.CUSTOM)

        assertEquals(ShaperProfile.SAFE, safe.profile)
        assertEquals(0.80, safe.headroomFactor, 0.0)
        assertEquals(5L, safe.codelTargetMs)
        assertEquals(0.01, safe.burstFraction, 0.0)
        assertEquals(ShaperProfile.MAXIMUM_THROUGHPUT, maximum.profile)
        assertEquals(0.93, maximum.headroomFactor, 0.0)
        assertEquals(10L, maximum.codelTargetMs)
        assertEquals(0.03, maximum.burstFraction, 0.0)
        assertEquals(ShaperProfile.CUSTOM, custom.profile)
        assertEquals(0.77, custom.headroomFactor, 0.0)
        assertEquals(37L, custom.codelTargetMs)
    }

    @Test
    fun validationRejectsUnsafeStartupConfigurationAndInvalidAppLists() {
        val invalid = ShaperConfig(
            egressRateBytesPerSec = 0L,
            ingressRateBytesPerSec = -1L,
            headroomFactor = 0.99,
            codelTargetMs = 0L,
            codelIntervalMs = 1_001L,
            fqBuckets = 0,
            burstFraction = 0.5,
            mtu = 1_000,
            appRoutingPolicy = AppRoutingPolicy(AppRoutingMode.ONLY_SELECTED_APPS)
        )

        val errors = invalid.validationErrors()
        assertEquals(9, errors.size)
        assertTrue(errors.any { it.contains("upload", ignoreCase = true) })
        assertTrue(errors.any { it.contains("app routing", ignoreCase = true) })
    }

    @Test
    fun appRoutingNormalizesValidPackagesButRejectsInvalidEntries() {
        val policy = AppRoutingPolicy(
            mode = AppRoutingMode.EXCLUDE_SELECTED_APPS,
            packageNames = setOf(" com.example.game ", "bad-package", "org.example.client")
        )
        val config = ShaperConfig(
            egressRateBytesPerSec = 1L,
            ingressRateBytesPerSec = 1L,
            appRoutingPolicy = policy
        )

        assertEquals(setOf("com.example.game", "org.example.client"), policy.normalizedPackages())
        assertTrue(policy.validationError()?.contains("valid Android package", ignoreCase = true) == true)
        assertTrue(config.validationErrors().isNotEmpty())
    }
}
