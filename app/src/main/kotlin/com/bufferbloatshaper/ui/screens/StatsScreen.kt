package com.bufferbloatshaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.ui.components.LatencyGraph
import com.bufferbloatshaper.ui.components.StatusCard
import com.bufferbloatshaper.ui.theme.*

/**
 * Live statistics screen showing real-time shaper metrics.
 * Displays egress stats, ingress stats, calibration info, and battery impact.
 */
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier
) {
    // Placeholder data — in production, this would observe the VPN service
    val sampleLatencyData = remember { (1..30).map { (Math.random() * 20 + 5).toFloat() } }
    val sampleThroughputData = remember { (1..30).map { (Math.random() * 50 + 10).toFloat() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Live Statistics",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ---- Egress Shaper Stats ----
        StatsSection(title = "Egress Shaper") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    title = "Rate",
                    value = "—",
                    subtitle = "Mbps limit",
                    icon = Icons.Default.Upload,
                    accentColor = ChartUpload,
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    title = "Token Fill",
                    value = "—",
                    subtitle = "bucket ratio",
                    icon = Icons.Default.Water,
                    accentColor = Primary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    title = "Queued",
                    value = "0",
                    subtitle = "packets",
                    icon = Icons.Default.Queue,
                    accentColor = Warning,
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    title = "Dropped",
                    value = "0",
                    subtitle = "by CoDel",
                    icon = Icons.Default.RemoveCircle,
                    accentColor = Error,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LatencyGraph(
                dataPoints = sampleLatencyData,
                maxValue = 50f,
                label = "Sojourn Time",
                lineColor = ChartLatency,
                thresholdValue = 5f,
                thresholdLabel = "CoDel target"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Ingress Controller Stats ----
        StatsSection(title = "Ingress Controller (TCP)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    title = "Target Rate",
                    value = "—",
                    subtitle = "Mbps",
                    icon = Icons.Default.Download,
                    accentColor = ChartDownload,
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    title = "Window",
                    value = "—",
                    subtitle = "rwnd bytes",
                    icon = Icons.Default.Visibility,
                    accentColor = Secondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = OnSurfaceDim,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "QUIC/UDP traffic is not shaped on ingress (by design — see §4)",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Calibration Stats ----
        StatsSection(title = "Calibration Engine") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    title = "Samples",
                    value = "0",
                    subtitle = "in window",
                    icon = Icons.Default.DataUsage,
                    accentColor = Primary,
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    title = "Network",
                    value = "—",
                    subtitle = "type",
                    icon = Icons.Default.CellTower,
                    accentColor = Secondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LatencyGraph(
                dataPoints = sampleThroughputData,
                maxValue = 100f,
                label = "Throughput Estimate",
                lineColor = ChartDownload
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Battery Stats ----
        StatsSection(title = "Battery Impact") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    title = "CPU Usage",
                    value = "—",
                    subtitle = "shaper process",
                    icon = Icons.Default.Memory,
                    accentColor = Warning,
                    modifier = Modifier.weight(1f)
                )
                StatusCard(
                    title = "Est. Drain",
                    value = "—",
                    subtitle = "additional %",
                    icon = Icons.Default.BatteryStd,
                    accentColor = Success,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun StatsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBackground)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = OnSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}
