package com.bufferbloatshaper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.ui.theme.*

/**
 * Live latency/throughput graph component.
 * Shows a rolling time-series line chart with gradient fill.
 */
@Composable
fun LatencyGraph(
    dataPoints: List<Float>,
    maxValue: Float,
    label: String,
    modifier: Modifier = Modifier,
    lineColor: Color = ChartLatency,
    thresholdValue: Float? = null,
    thresholdLabel: String = ""
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim
            )
            if (dataPoints.isNotEmpty()) {
                Text(
                    text = "%.1f ms".format(dataPoints.last()),
                    style = MaterialTheme.typography.labelMedium,
                    color = lineColor
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        ) {
            if (dataPoints.isEmpty()) return@Canvas

            val width = size.width
            val height = size.height
            val effectiveMax = maxValue.coerceAtLeast(1f)

            // Draw grid lines
            for (i in 0..3) {
                val y = height * i / 4
                drawLine(
                    color = GlassBorder,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 0.5f
                )
            }

            // Draw threshold line if specified
            thresholdValue?.let { threshold ->
                val thresholdY = height * (1 - threshold / effectiveMax).coerceIn(0f, 1f)
                drawLine(
                    color = Warning.copy(alpha = 0.5f),
                    start = Offset(0f, thresholdY),
                    end = Offset(width, thresholdY),
                    strokeWidth = 1.5f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(8f, 8f)
                    )
                )
            }

            // Draw data line
            val path = Path()
            val stepX = width / (dataPoints.size - 1).coerceAtLeast(1)

            dataPoints.forEachIndexed { index, value ->
                val x = index * stepX
                val y = height * (1 - (value / effectiveMax).coerceIn(0f, 1f))

                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    // Smooth curve using quadratic bezier
                    val prevX = (index - 1) * stepX
                    val prevValue = dataPoints[index - 1]
                    val prevY = height * (1 - (prevValue / effectiveMax).coerceIn(0f, 1f))
                    val cpX = (prevX + x) / 2
                    path.quadraticBezierTo(cpX, prevY, (cpX + x) / 2, (prevY + y) / 2)
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw latest point dot
            if (dataPoints.isNotEmpty()) {
                val lastX = (dataPoints.size - 1) * stepX
                val lastY = height * (1 - (dataPoints.last() / effectiveMax).coerceIn(0f, 1f))
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = Offset(lastX, lastY)
                )
                drawCircle(
                    color = lineColor.copy(alpha = 0.3f),
                    radius = 8.dp.toPx(),
                    center = Offset(lastX, lastY)
                )
            }
        }
    }
}
