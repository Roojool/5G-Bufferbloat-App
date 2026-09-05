package com.bufferbloatshaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.model.VpnRuntimeStateStore
import com.bufferbloatshaper.model.VpnRuntimeStatus
import com.bufferbloatshaper.ui.components.StatusCard
import com.bufferbloatshaper.ui.theme.ChartDownload
import com.bufferbloatshaper.ui.theme.ChartUpload
import com.bufferbloatshaper.ui.theme.Error
import com.bufferbloatshaper.ui.theme.GlassBackground
import com.bufferbloatshaper.ui.theme.GlassBorder
import com.bufferbloatshaper.ui.theme.OnBackground
import com.bufferbloatshaper.ui.theme.OnSurface
import com.bufferbloatshaper.ui.theme.OnSurfaceDim
import com.bufferbloatshaper.ui.theme.Primary
import com.bufferbloatshaper.ui.theme.Secondary
import com.bufferbloatshaper.ui.theme.Warning

/** Displays only configuration and aggregate counters reported by the engine. */
@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val runtime by VpnRuntimeStateStore.state.collectAsState()
    val metrics = runtime.metrics
    val active = runtime.status == VpnRuntimeStatus.RUNNING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Live Statistics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OnBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (active) "Aggregate measurements from the local engine" else "No live engine measurements are available.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Spacer(modifier = Modifier.height(24.dp))

        StatsSection(title = "Upload") {
            MetricRow(
                left = Metric("Measured", metrics.measuredEgressBytesPerSec?.toMbpsLabel() ?: "—", "Mbps", Icons.Default.Upload, ChartUpload),
                right = Metric("Configured cap", metrics.egressRateBytesPerSec.toMbpsLabel(), "Mbps", Icons.Default.DataUsage, Primary)
            )
            Spacer(modifier = Modifier.height(12.dp))
            MetricRow(
                left = Metric("Bytes sent", metrics.bytesEgressed.toByteLabel(), "aggregate", Icons.Default.DataUsage, ChartUpload),
                right = Metric("Flows", if (active) metrics.activeFlows.toString() else "—", "active", Icons.Default.Storage, Secondary)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        StatsSection(title = "Download (TCP engine scope)") {
            MetricRow(
                left = Metric("Measured", metrics.measuredIngressBytesPerSec?.toMbpsLabel() ?: "—", "Mbps", Icons.Default.Download, ChartDownload),
                right = Metric("Configured cap", (metrics.ingressTargetBytesPerSec.toDouble()).toMbpsLabel(), "Mbps", Icons.Default.DataUsage, Secondary)
            )
            Spacer(modifier = Modifier.height(8.dp))
            InfoLine("QUIC/UDP download shaping is intentionally not claimed. IPv6 bypasses the IPv4-only engine until dual-stack tests pass.")
        }

        Spacer(modifier = Modifier.height(16.dp))
        StatsSection(title = "Queue health") {
            MetricRow(
                left = Metric("Queued", metrics.queuedBytes.toByteLabel(), "bytes", Icons.Default.Storage, Warning),
                right = Metric("AQM drops", if (active) metrics.droppedPackets.toString() else "—", "reported", Icons.Default.RemoveCircle, Error)
            )
            Spacer(modifier = Modifier.height(8.dp))
            InfoLine("A future gVisor engine must own TCP recovery before CoDel/FQ may drop packets. No fallback relay is shipped.")
        }

        Spacer(modifier = Modifier.height(16.dp))
        StatsSection(title = "Engine and diagnostics") {
            MetricRow(
                left = Metric("Native engine", if (runtime.nativeEngineAvailable) "Ready" else "Unavailable", "capability", Icons.Default.VerifiedUser, if (runtime.nativeEngineAvailable) Primary else Error),
                right = Metric("Generation", runtime.generation.toString(), "local only", Icons.Default.DataUsage, Secondary)
            )
            Spacer(modifier = Modifier.height(8.dp))
            InfoLine(runtime.recoverableError ?: runtime.detail)
            Spacer(modifier = Modifier.height(8.dp))
            InfoLine("Automatic calibration is disabled until direct, independent physical-network probes are implemented and verified.")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private data class Metric(
    val title: String,
    val value: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: androidx.compose.ui.graphics.Color
)

@Composable
private fun MetricRow(left: Metric, right: Metric) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusCard(left.title, left.value, left.icon, Modifier.weight(1f), left.subtitle, left.color)
        StatusCard(right.title, right.value, right.icon, Modifier.weight(1f), right.subtitle, right.color)
    }
}

@Composable
private fun InfoLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, null, tint = OnSurfaceDim, modifier = Modifier.padding(top = 2.dp))
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
    }
}

@Composable
private fun StatsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBackground)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

private fun Double.toMbpsLabel(): String = "%.2f".format(this * 8.0 / 1_000_000)

private fun Long.toByteLabel(): String = when {
    this >= 1_000_000 -> "%.2f MB".format(this / 1_000_000.0)
    this >= 1_000 -> "%.1f KB".format(this / 1_000.0)
    else -> "$this B"
}
