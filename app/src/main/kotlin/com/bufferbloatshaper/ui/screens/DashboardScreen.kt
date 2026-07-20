package com.bufferbloatshaper.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.ui.components.SpeedGauge
import com.bufferbloatshaper.ui.components.StatusCard
import com.bufferbloatshaper.ui.theme.*
import com.bufferbloatshaper.util.Preferences
import com.bufferbloatshaper.vpn.ShaperVpnService

/**
 * Main dashboard screen — the primary interface.
 * Big toggle to enable/disable shaper, speed gauges, and key metrics.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    var config by remember { mutableStateOf(preferences.loadConfig()) }
    var isActive by remember { mutableStateOf(false) }

    // VPN permission launcher
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(context, config)
            isActive = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "Bufferbloat Shaper",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isActive) "Actively shaping traffic" else "Traffic shaping is off",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isActive) Success else OnSurfaceDim
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Big power toggle
        PowerToggle(
            isActive = isActive,
            onClick = {
                if (isActive) {
                    stopVpnService(context)
                    isActive = false
                } else {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        vpnLauncher.launch(intent)
                    } else {
                        startVpnService(context, config)
                        isActive = true
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Speed gauges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SpeedGauge(
                currentSpeedMbps = if (isActive) ShaperConfig.bytesSecToMbps(config.egressRateBytesPerSec) else 0.0,
                maxSpeedMbps = ShaperConfig.bytesSecToMbps(config.egressRateBytesPerSec).coerceAtLeast(1.0),
                label = "Upload",
                gaugeColor = ChartUpload,
                secondaryColor = Primary
            )

            SpeedGauge(
                currentSpeedMbps = if (isActive) ShaperConfig.bytesSecToMbps(config.ingressRateBytesPerSec) else 0.0,
                maxSpeedMbps = ShaperConfig.bytesSecToMbps(config.ingressRateBytesPerSec).coerceAtLeast(1.0),
                label = "Download",
                gaugeColor = ChartDownload,
                secondaryColor = Secondary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Status cards grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Active Flows",
                value = if (isActive) "0" else "—",
                icon = Icons.Default.SwapVert,
                accentColor = Secondary,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Latency",
                value = if (isActive) "—" else "—",
                icon = Icons.Default.Speed,
                accentColor = Success,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Queue Depth",
                value = if (isActive) "0" else "—",
                icon = Icons.Default.Storage,
                accentColor = Warning,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Drops",
                value = if (isActive) "0" else "—",
                icon = Icons.Default.RemoveCircleOutline,
                accentColor = Error,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Mode indicator
        AnimatedVisibility(visible = isActive) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Active Configuration",
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                ConfigRow("Mode", if (config.autoCalibrationEnabled) "Auto-Calibration" else "Manual")
                ConfigRow("Egress", "%.1f Mbps".format(ShaperConfig.bytesSecToMbps(config.egressRateBytesPerSec)))
                ConfigRow("Ingress", "%.1f Mbps".format(ShaperConfig.bytesSecToMbps(config.ingressRateBytesPerSec)))
                ConfigRow("CoDel Target", "${config.codelTargetMs}ms")
                ConfigRow("Smart Mode", if (config.smartModeEnabled) "On" else "Off")
                ConfigRow("Headroom", "${(config.headroomFactor * 100).toInt()}%")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PowerToggle(isActive: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.0f else 0.95f,
        animationSpec = spring(dampingRatio = 0.5f),
        label = "toggle_scale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (isActive) {
                    Brush.radialGradient(
                        colors = listOf(
                            Primary.copy(alpha = glowAlpha),
                            Secondary.copy(alpha = glowAlpha * 0.5f),
                            Background
                        )
                    )
                } else {
                    Brush.radialGradient(
                        colors = listOf(SurfaceElevated, Surface, Background)
                    )
                }
            )
            .border(
                2.dp,
                if (isActive) Primary else GlassBorder,
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = if (isActive) "Disable shaper" else "Enable shaper",
            tint = if (isActive) OnPrimary else OnSurfaceDim,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = FontWeight.Medium)
    }
}

private fun startVpnService(context: Context, config: ShaperConfig) {
    val intent = Intent(context, ShaperVpnService::class.java).apply {
        action = ShaperVpnService.ACTION_START
        putExtra(ShaperVpnService.EXTRA_UPLOAD_MBPS, ShaperConfig.bytesSecToMbps(config.egressRateBytesPerSec))
        putExtra(ShaperVpnService.EXTRA_DOWNLOAD_MBPS, ShaperConfig.bytesSecToMbps(config.ingressRateBytesPerSec))
        putExtra(ShaperVpnService.EXTRA_AUTO_CALIBRATE, config.autoCalibrationEnabled)
        putExtra(ShaperVpnService.EXTRA_SMART_MODE, config.smartModeEnabled)
        putExtra(ShaperVpnService.EXTRA_HEADROOM, config.headroomFactor)
    }
    context.startForegroundService(intent)
}

private fun stopVpnService(context: Context) {
    val intent = Intent(context, ShaperVpnService::class.java).apply {
        action = ShaperVpnService.ACTION_STOP
    }
    context.startService(intent)
}
