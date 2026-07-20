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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.ui.theme.*
import com.bufferbloatshaper.util.Preferences

/**
 * Settings screen — manual rate entry, calibration config, smart mode toggle.
 * Includes upfront disclaimers from §10.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    var config by remember { mutableStateOf(preferences.loadConfig()) }

    var uploadMbps by remember {
        mutableStateOf(ShaperConfig.bytesSecToMbps(config.egressRateBytesPerSec).let {
            if (it > 0) "%.1f".format(it) else ""
        })
    }
    var downloadMbps by remember {
        mutableStateOf(ShaperConfig.bytesSecToMbps(config.ingressRateBytesPerSec).let {
            if (it > 0) "%.1f".format(it) else ""
        })
    }

    fun saveConfig(newConfig: ShaperConfig) {
        config = newConfig
        preferences.saveConfig(newConfig)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ---- Manual Rate Entry ----
        SectionCard(title = "Rate Limits") {
            OutlinedTextField(
                value = uploadMbps,
                onValueChange = { value ->
                    uploadMbps = value
                    value.toDoubleOrNull()?.let { mbps ->
                        saveConfig(config.copy(egressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(mbps)))
                    }
                },
                label = { Text("Upload Limit (Mbps)") },
                leadingIcon = {
                    Icon(Icons.Default.Upload, contentDescription = null, tint = ChartUpload)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = GlassBorder,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedLabelColor = Primary,
                    unfocusedLabelColor = OnSurfaceDim,
                    cursorColor = Primary
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = downloadMbps,
                onValueChange = { value ->
                    downloadMbps = value
                    value.toDoubleOrNull()?.let { mbps ->
                        saveConfig(config.copy(ingressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(mbps)))
                    }
                },
                label = { Text("Download Limit (Mbps)") },
                leadingIcon = {
                    Icon(Icons.Default.Download, contentDescription = null, tint = ChartDownload)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Secondary,
                    unfocusedBorderColor = GlassBorder,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedLabelColor = Secondary,
                    unfocusedLabelColor = OnSurfaceDim,
                    cursorColor = Secondary
                ),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Auto-Calibration ----
        SectionCard(title = "Auto-Calibration (Phase 2)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Auto-Calibration", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(
                        "Automatically adjust rates based on measured network capacity",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                Switch(
                    checked = config.autoCalibrationEnabled,
                    onCheckedChange = { saveConfig(config.copy(autoCalibrationEnabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Headroom: ${(config.headroomFactor * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Text(
                "Percentage of estimated capacity to use",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Slider(
                value = config.headroomFactor.toFloat(),
                onValueChange = { saveConfig(config.copy(headroomFactor = it.toDouble())) },
                valueRange = 0.7f..0.95f,
                steps = 4,
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
                    inactiveTrackColor = GlassBorder
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Smart Mode ----
        SectionCard(title = "Adaptive Smart Mode (Phase 4)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Smart Mode", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(
                        "Auto-detect latency-sensitive flows and prioritize them",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                Switch(
                    checked = config.smartModeEnabled,
                    onCheckedChange = { saveConfig(config.copy(smartModeEnabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Secondary,
                        checkedTrackColor = Secondary.copy(alpha = 0.3f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Advanced: CoDel Tuning ----
        SectionCard(title = "AQM Tuning (Advanced)") {
            Text(
                "CoDel Target: ${config.codelTargetMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Slider(
                value = config.codelTargetMs.toFloat(),
                onValueChange = { saveConfig(config.copy(codelTargetMs = it.toLong())) },
                valueRange = 1f..20f,
                steps = 18,
                colors = SliderDefaults.colors(
                    thumbColor = Warning,
                    activeTrackColor = Warning,
                    inactiveTrackColor = GlassBorder
                )
            )

            Text(
                "CoDel Interval: ${config.codelIntervalMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Slider(
                value = config.codelIntervalMs.toFloat(),
                onValueChange = { saveConfig(config.copy(codelIntervalMs = it.toLong())) },
                valueRange = 50f..200f,
                steps = 14,
                colors = SliderDefaults.colors(
                    thumbColor = Warning,
                    activeTrackColor = Warning,
                    inactiveTrackColor = GlassBorder
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Disclaimers (§10) ----
        SectionCard(title = "Important Information") {
            DisclaimerItem(
                icon = Icons.Default.SignalCellularAlt,
                text = "This app does NOT force 5G SA, NSA, or carrier aggregation — those are network-controlled decisions outside any app's control."
            )
            Spacer(modifier = Modifier.height(8.dp))
            DisclaimerItem(
                icon = Icons.Default.Warning,
                text = "Download shaping is effective for TCP traffic but has no effect on QUIC/UDP-heavy downloads. This is a fundamental protocol limitation, not a bug."
            )
            Spacer(modifier = Modifier.height(8.dp))
            DisclaimerItem(
                icon = Icons.Default.TrendingUp,
                text = "The chosen shaping rate is a statistical estimate. A sudden drop in signal can still cause a brief spike before recalibration catches up."
            )
            Spacer(modifier = Modifier.height(8.dp))
            DisclaimerItem(
                icon = Icons.Default.Shield,
                text = "This app uses the VPN interface purely for local traffic interception. NO traffic is routed through any remote server."
            )
            Spacer(modifier = Modifier.height(8.dp))
            DisclaimerItem(
                icon = Icons.Default.BatteryStd,
                text = "Running a local traffic relay adds some CPU overhead. Battery impact is typically in the low single-digit percentage range."
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionCard(
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

@Composable
private fun DisclaimerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OnSurfaceDim,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}
