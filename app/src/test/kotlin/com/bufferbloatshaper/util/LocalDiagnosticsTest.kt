package com.bufferbloatshaper.util

import com.bufferbloatshaper.model.AppRoutingMode
import com.bufferbloatshaper.model.AppRoutingPolicy
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.model.ShaperProfile
import com.bufferbloatshaper.model.VpnHealthEvent
import com.bufferbloatshaper.model.VpnRuntimeState
import com.bufferbloatshaper.model.VpnRuntimeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticsTest {

    private val summary = LocalDiagnostics.CapabilitySummary(
        androidVersion = "Android 15 (API 35)",
        device = "Example Device",
        transport = "Cellular",
        validated = true,
        captivePortalDetected = false,
        mtuHint = "Configured TUN MTU: 1400"
    )

    @Test
    fun reportUsesSuppliedSavedConfigurationWhenRuntimeHasNoConfiguration() {
        val saved = ShaperConfig(
            egressRateBytesPerSec = 1_250_000,
            ingressRateBytesPerSec = 5_000_000,
            profile = ShaperProfile.SAFE,
            appRoutingPolicy = AppRoutingPolicy(AppRoutingMode.ONLY_SELECTED_APPS, setOf("com.example.app"))
        )

        val report = LocalDiagnostics.formatReport(summary, VpnRuntimeState(), saved, emptyList())

        assertTrue(report.contains("Profile: SAFE"))
        assertTrue(report.contains("App routing mode: ONLY_SELECTED_APPS"))
        assertTrue(report.contains("Selected-app count: 1"))
        assertTrue(report.contains("Upload cap (bytes/s): 1250000"))
    }

    @Test
    fun reportNeverExportsFreeFormHealthDetailOrErrorText() {
        val sensitiveDetail = "https://[2001:db8::1]/lookup?name=private.example"
        val sensitiveError = "Connection to 10.0.0.7 failed for com.private.app"
        val event = VpnHealthEvent(
            timestampMs = 0,
            generation = 4,
            status = VpnRuntimeStatus.ERROR,
            detail = sensitiveDetail,
            recoverableError = sensitiveError
        )

        val report = LocalDiagnostics.formatReport(summary, VpnRuntimeState(), ShaperConfig(), listOf(event))

        assertTrue(report.contains("[ERROR] generation 4"))
        assertFalse(report.contains(sensitiveDetail))
        assertFalse(report.contains(sensitiveError))
        assertFalse(report.contains("2001:db8"))
        assertFalse(report.contains("10.0.0.7"))
        assertFalse(report.contains("private.example"))
        assertFalse(report.contains("com.private.app"))
    }
}
