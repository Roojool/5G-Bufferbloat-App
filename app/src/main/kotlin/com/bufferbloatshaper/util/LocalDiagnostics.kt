package com.bufferbloatshaper.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.model.VpnHealthEvent
import com.bufferbloatshaper.model.VpnRuntimeState
import com.bufferbloatshaper.model.VpnRuntimeStateStore
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Produces a local, deliberately redacted support summary. Nothing is sent
 * unless the user taps the system share sheet and chooses a destination.
 */
object LocalDiagnostics {

    data class CapabilitySummary(
        val androidVersion: String,
        val device: String,
        val transport: String,
        val validated: Boolean,
        val captivePortalDetected: Boolean,
        val mtuHint: String
    )

    fun capabilitySummary(
        context: Context,
        config: ShaperConfig = Preferences(context).loadConfig()
    ): CapabilitySummary {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        return CapabilitySummary(
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            transport = transportLabel(capabilities),
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortalDetected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            mtuHint = "Configured TUN MTU: ${config.mtu}"
        )
    }

    fun redactedReport(context: Context): String {
        val state = VpnRuntimeStateStore.state.value
        // Runtime state only has a configuration while the service is active.
        // Preferences is the source of truth for an exported local report.
        val config = state.config ?: Preferences(context).loadConfig()
        val summary = capabilitySummary(context, config)
        return formatReport(summary, state, config, VpnRuntimeStateStore.timeline.value)
    }

    /**
     * Renders only structured, app-owned fields. Do not add free-form service
     * detail or exception text here: those fields can contain network or app
     * identifiers that cannot be safely redacted with pattern matching.
     */
    internal fun formatReport(
        summary: CapabilitySummary,
        state: VpnRuntimeState,
        config: ShaperConfig,
        timeline: List<VpnHealthEvent>
    ): String {
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())

        return buildString {
            appendLine("Bufferbloat Shaper — redacted local diagnostic")
            appendLine("Generated: ${dateFormat.format(Date())}")
            appendLine()
            appendLine("Device: ${summary.device}")
            appendLine("OS: ${summary.androidVersion}")
            appendLine("Transport: ${summary.transport}")
            appendLine("Validated network: ${summary.validated}")
            appendLine("Captive portal indicated: ${summary.captivePortalDetected}")
            appendLine(summary.mtuHint)
            appendLine()
            appendLine("Runtime status: ${state.status}")
            appendLine("Runtime generation: ${state.generation}")
            appendLine("Native engine available: ${state.nativeEngineAvailable}")
            appendLine("IPv6 supported: ${state.ipv6Supported}")
            appendLine("Profile: ${config?.profile ?: "not configured"}")
            appendLine("App routing mode: ${config?.appRoutingPolicy?.mode ?: "not configured"}")
            appendLine("Selected-app count: ${config?.appRoutingPolicy?.normalizedPackages()?.size ?: 0}")
            appendLine("Upload cap (bytes/s): ${config?.egressRateBytesPerSec ?: 0}")
            appendLine("Download cap (bytes/s): ${config?.ingressRateBytesPerSec ?: 0}")
            appendLine()
            appendLine("Health timeline (redacted):")
            timeline.forEach { event ->
                appendLine("- ${dateFormat.format(Date(event.timestampMs))} [${event.status}] generation ${event.generation}")
            }
            appendLine()
            appendLine("Not included: free-form error text, traffic payloads, DNS names, IP addresses, app package names, phone identifiers, SIM identifiers, Wi-Fi names, or precise location.")
        }
    }

    fun shareRedactedReport(context: Context) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bufferbloat Shaper diagnostic (redacted)")
            putExtra(Intent.EXTRA_TEXT, redactedReport(context))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share redacted diagnostic").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun transportLabel(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "No active network reported"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Other/unknown"
    }

}
