package com.bufferbloatshaper.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bufferbloatshaper.ui.theme.*

/**
 * Animated speed gauge component with gradient arc.
 * Shows measured throughput as a percentage of the configured limit. A null
 * sample is rendered as an em dash rather than a made-up zero.
 */
@Composable
fun SpeedGauge(
    currentSpeedMbps: Double?,
    maxSpeedMbps: Double,
    label: String,
    modifier: Modifier = Modifier,
    gaugeColor: Color = Primary,
    secondaryColor: Color = Secondary
) {
    val ratio = if (currentSpeedMbps != null && maxSpeedMbps > 0) {
        (currentSpeedMbps / maxSpeedMbps).coerceIn(0.0, 1.0)
    } else 0.0

    // Animate the sweep angle
    val animatedRatio by animateFloatAsState(
        targetValue = ratio.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "gauge_sweep"
    )

    // Subtle pulse animation when active
    val infiniteTransition = rememberInfiniteTransition(label = "gauge_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier.size(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val strokeWidth = 12.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            // Background arc (track)
            drawArc(
                color = GlassBackground,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active arc (gradient)
            val sweepAngle = 270f * animatedRatio
            if (sweepAngle > 0) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(gaugeColor, secondaryColor, gaugeColor)
                    ),
                    startAngle = 135f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    alpha = if ((currentSpeedMbps ?: 0.0) > 0) pulseAlpha else 1f
                )
            }
        }

        // Center text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = currentSpeedMbps?.let { "%.1f".format(it) } ?: "—",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = OnBackground
            )
            Text(
                text = "Mbps",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if ((currentSpeedMbps ?: 0.0) > 0) Secondary else OnSurfaceDim
            )
        }
    }
}
