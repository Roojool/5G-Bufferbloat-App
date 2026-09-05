package com.bufferbloatshaper.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.model.VpnRuntimeStateStore
import com.bufferbloatshaper.model.VpnRuntimeStatus
import com.bufferbloatshaper.nativeengine.NativeEngineBridge
import com.bufferbloatshaper.ui.components.SpeedGauge
import com.bufferbloatshaper.ui.components.StatusCard
import com.bufferbloatshaper.ui.theme.Background
import com.bufferbloatshaper.ui.theme.ChartDownload
import com.bufferbloatshaper.ui.theme.ChartUpload
import com.bufferbloatshaper.ui.theme.Error
import com.bufferbloatshaper.ui.theme.GlassBackground
import com.bufferbloatshaper.ui.theme.GlassBorder
import com.bufferbloatshaper.ui.theme.OnBackground
import com.bufferbloatshaper.ui.theme.OnPrimary
import com.bufferbloatshaper.ui.theme.OnSurface
import com.bufferbloatshaper.ui.theme.OnSurfaceDim
import com.bufferbloatshaper.ui.theme.Primary
import com.bufferbloatshaper.ui.theme.Secondary
import com.bufferbloatshaper.ui.theme.Success
import com.bufferbloatshaper.ui.theme.Surface
import com.bufferbloatshaper.ui.theme.SurfaceElevated
import com.bufferbloatshaper.ui.theme.Warning
import com.bufferbloatshaper.util.Preferences
import com.bufferbloatshaper.vpn.ShaperVpnService

/** Main dashboard backed solely by the service's observable runtime state. */
@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    val runtime by VpnRuntimeStateStore.state.collectAsState()
    val displayConfig = runtime.config ?: preferences.loadConfig()
    val isActive = runtime.status == VpnRuntimeStatus.RUNNING
    val isTransitioning = runtime.status == VpnRuntimeStatus.STARTING ||
        runtime.status == VpnRuntimeStatus.STOPPING

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(context, preferences.loadConfig())
        }
    }

    val uploadMeasured = runtime.metrics.measuredEgressBytesPerSec?.let { it * 8.0 / 1_000_000 }
    val downloadMeasured = runtime.metrics.measuredIngressBytesPerSec?.let { it * 8.0 / 1_000_000 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Bufferbloat Shaper",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = runtime.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = when (runtime.status) {
                VpnRuntimeStatus.RUNNING -> Success
                VpnRuntimeStatus.ERROR, VpnRuntimeStatus.UNSUPPORTED -> Error
                else -> OnSurfaceDim
            }
        )

        runtime.recoverableError?.let { error ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Error.copy(alpha = 0.12f))
                    .border(1.dp, Error.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = Error)
                Spacer(modifier = Modifier.size(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = OnSurface)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        PowerToggle(
            isActive = isActive || isTransitioning,
            onClick = {
                if (isTransitioning) return@PowerToggle
                if (isActive) {
                    stopVpnService(context)
                } else {
                    // Do not show Android's VPN-consent sheet for the checked-in
                    // unavailable stub. Starting the service surfaces the same
                    // recoverable reason without requesting a route permission.
                    if (!NativeEngineBridge.capability().available) {
                        startVpnService(context, preferences.loadConfig())
                        return@PowerToggle
                    }
                    val permissionIntent = VpnService.prepare(context)
                    if (permissionIntent != null) {
                        vpnLauncher.launch(permissionIntent)
                    } else {
                        startVpnService(context, preferences.loadConfig())
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SpeedGauge(
                currentSpeedMbps = uploadMeasured,
                maxSpeedMbps = ShaperConfig.bytesSecToMbps(displayConfig.egressRateBytesPerSec).coerceAtLeast(1.0),
                label = "Upload throughput",
                gaugeColor = ChartUpload,
                secondaryColor = Primary
            )
            SpeedGauge(
                currentSpeedMbps = downloadMeasured,
                maxSpeedMbps = ShaperConfig.bytesSecToMbps(displayConfig.ingressRateBytesPerSec).coerceAtLeast(1.0),
                label = "Download throughput",
                gaugeColor = ChartDownload,
                secondaryColor = Secondary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Active Flows",
                value = if (isActive) runtime.metrics.activeFlows.toString() else "—",
                icon = Icons.Default.SwapVert,
                accentColor = Secondary,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Queue",
                value = if (isActive) "${runtime.metrics.queuedBytes / 1024} KB" else "—",
                icon = Icons.Default.Storage,
                accentColor = Warning,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "AQM Drops",
                value = if (isActive) runtime.metrics.droppedPackets.toString() else "—",
                icon = Icons.Default.RemoveCircleOutline,
                accentColor = Error,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Latency",
                value = "—",
                subtitle = "No native RTT sample",
                icon = Icons.Default.Speed,
                accentColor = Success,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        AnimatedVisibility(visible = runtime.status != VpnRuntimeStatus.STOPPED) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text("Configuration", style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                ConfigRow("Profile", displayConfig.profile.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
                ConfigRow("Upload cap", "%.1f Mbps".format(ShaperConfig.bytesSecToMbps(displayConfig.egressRateBytesPerSec)))
                ConfigRow("Download cap", "%.1f Mbps".format(ShaperConfig.bytesSecToMbps(displayConfig.ingressRateBytesPerSec)))
                ConfigRow("App routing", displayConfig.appRoutingPolicy.mode.name.replace('_', ' ').lowercase())
                ConfigRow("IPv6", if (runtime.ipv6Supported) "Supported" else "Bypasses IPv4-only engine pending tests")
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
                if (isActive) Brush.radialGradient(listOf(Primary.copy(alpha = glowAlpha), Secondary.copy(alpha = glowAlpha * 0.5f), Background))
                else Brush.radialGradient(listOf(SurfaceElevated, Surface, Background))
            )
            .border(2.dp, if (isActive) Primary else GlassBorder, CircleShape)
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
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Text(value, style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = FontWeight.Medium)
    }
}

private fun startVpnService(context: Context, config: ShaperConfig) {
    Preferences(context).saveConfig(config)
    context.startForegroundService(Intent(context, ShaperVpnService::class.java).apply {
        action = ShaperVpnService.ACTION_START
    })
}

private fun stopVpnService(context: Context) {
    context.startService(Intent(context, ShaperVpnService::class.java).apply {
        action = ShaperVpnService.ACTION_STOP
    })
}
