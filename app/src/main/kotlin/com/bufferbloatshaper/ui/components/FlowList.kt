package com.bufferbloatshaper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.model.FlowType
import com.bufferbloatshaper.ui.theme.*

/**
 * Displays a list of active network flows with their stats.
 */
@Composable
fun FlowList(
    flows: List<FlowItem>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(flows) { flow ->
            FlowRow(flow)
        }
    }
}

data class FlowItem(
    val destination: String,
    val port: Int,
    val protocol: String,
    val flowType: FlowType,
    val queueDepth: Int,
    val sojournMs: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val inDropState: Boolean
)

@Composable
private fun FlowRow(flow: FlowItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GlassBackground)
            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flow type indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when (flow.flowType) {
                        FlowType.INTERACTIVE -> GradeA
                        FlowType.VIDEO_CALL -> Secondary
                        FlowType.DNS -> GradeB
                        FlowType.WEB_BROWSING -> Primary
                        FlowType.BULK_TRANSFER -> Warning
                        FlowType.UNKNOWN -> OnSurfaceDim
                    }
                )
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Destination info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${flow.destination}:${flow.port}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Text(
                text = "${flow.protocol} · ${flow.flowType.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
        }

        // Queue stats
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "Q:${flow.queueDepth}",
                style = MaterialTheme.typography.labelSmall,
                color = if (flow.inDropState) Error else OnSurfaceDim
            )
            Text(
                text = "${flow.sojournMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    flow.sojournMs > 30 -> Error
                    flow.sojournMs > 5 -> Warning
                    else -> Success
                }
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Data transferred
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "↑${formatBytes(flow.bytesSent)}",
                style = MaterialTheme.typography.labelSmall,
                color = ChartUpload
            )
            Text(
                text = "↓${formatBytes(flow.bytesReceived)}",
                style = MaterialTheme.typography.labelSmall,
                color = ChartDownload
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
    else -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
}
