package com.bufferbloatshaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.bufferbloatshaper.model.AppRoutingMode
import com.bufferbloatshaper.model.AppRoutingPolicy
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.model.ShaperProfile
import com.bufferbloatshaper.model.VpnRuntimeStateStore
import com.bufferbloatshaper.model.VpnRuntimeStatus
import com.bufferbloatshaper.ui.theme.*
import com.bufferbloatshaper.util.Preferences
import com.bufferbloatshaper.vpn.ShaperVpnService

/**
 * Settings screen — manual rate entry, calibration config, smart mode toggle.
 * Includes upfront disclaimers from §10.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    var config by remember { mutableStateOf(preferences.loadConfig()) }
    val runtime by VpnRuntimeStateStore.state.collectAsState()
    var appPackages by remember {
        mutableStateOf(config.appRoutingPolicy.normalizedPackages().joinToString(", "))
    }

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
        val safeConfig = newConfig.copy(autoCalibrationEnabled = false)
        config = safeConfig
        preferences.saveConfig(safeConfig)
        if (runtime.status == VpnRuntimeStatus.RUNNING) {
            ShaperVpnService.updateConfiguration(context)
        }
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

        // ---- Safe Profiles ----
        SectionCard(title = "Safe Profile") {
            Text(
                "Profiles choose local headroom and queue settings. They do not change your carrier, radio, bands, or 5G mode.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShaperProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = config.profile == profile,
                        onClick = { saveConfig(config.withProfile(profile)) },
                        label = { Text(profile.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Manual Rate Entry ----
        SectionCard(title = "Rate Limits") {
            OutlinedTextField(
                value = uploadMbps,
                onValueChange = { value ->
                    uploadMbps = value
                    value.toDoubleOrNull()?.let { mbps ->
                        saveConfig(config.copy(
                            egressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(mbps),
                            profile = ShaperProfile.CUSTOM
                        ))
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
                        saveConfig(config.copy(
                            ingressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(mbps),
                            profile = ShaperProfile.CUSTOM
                        ))
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

        // ---- Per-app routing ----
        SectionCard(title = "Per-app VPN Routing") {
            Text(
                "Optional. Use Android's supported app routing APIs to shape only selected apps or bypass selected apps. Enter package names such as com.example.app; unavailable packages block startup safely.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppRoutingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.appRoutingPolicy.mode == mode,
                        onClick = {
                            saveConfig(config.copy(appRoutingPolicy = config.appRoutingPolicy.copy(mode = mode)))
                        },
                        label = { Text(routingModeLabel(mode)) }
                    )
                }
            }
            if (config.appRoutingPolicy.mode != AppRoutingMode.ALL_APPS) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = appPackages,
                    onValueChange = { text ->
                        appPackages = text
                        saveConfig(config.copy(
                            appRoutingPolicy = AppRoutingPolicy(
                                mode = config.appRoutingPolicy.mode,
                                packageNames = text.split(',', ';', '\n')
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .toSet()
                            )
                        ))
                    },
                    label = { Text("Package names (comma separated)") },
                    supportingText = { Text("Routing changes restart the local VPN when it is active.") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Secondary,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        focusedLabelColor = Secondary,
                        unfocusedLabelColor = OnSurfaceDim,
                        cursorColor = Secondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Calibration safety ----
        SectionCard(title = "Capacity Calibration") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automatic calibration is unavailable", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(
                        "The old passive estimator can feed already-shaped traffic back into its own rate limit. It is disabled until direct physical-network probes and per-network samples pass verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                Switch(
                    checked = false,
                    onCheckedChange = null,
                    enabled = false,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Manual headroom: ${(config.headroomFactor * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Text(
                "Percent of your independently measured capacity to configure",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Slider(
                value = config.headroomFactor.toFloat(),
                onValueChange = { saveConfig(config.copy(
                    headroomFactor = it.toDouble(),
                    autoCalibrationEnabled = false,
                    profile = ShaperProfile.CUSTOM
                )) },
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
        SectionCard(title = "Native Flow Priority") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Smart Mode", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(
                        "Stored for the verified native engine; this source build does not inspect payloads or claim flow classification is active.",
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
                onValueChange = { saveConfig(config.copy(
                    codelTargetMs = it.toLong(),
                    profile = ShaperProfile.CUSTOM
                )) },
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
                onValueChange = { saveConfig(config.copy(
                    codelIntervalMs = it.toLong(),
                    profile = ShaperProfile.CUSTOM
                )) },
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
                text = "A future native engine will target TCP ingress only. QUIC/UDP download control is intentionally out of scope."
            )
            Spacer(modifier = Modifier.height(8.dp))
            DisclaimerItem(
                icon = Icons.Default.TrendingUp,
                text = "Set caps from an independent physical-network measurement. Automatic calibration stays disabled until direct probe evidence is available."
            )
            Spacer(modifier = Modifier.height(8.dp))
            DisclaimerItem(
                icon = Icons.Default.Shield,
                text = "This app is local-only: no remote proxy, telemetry server, TLS interception, or payload logging."
            )
            Spacer(modifier = Modifier.height(8.dp))
            DisclaimerItem(
                icon = Icons.Default.BatteryStd,
                text = "The checked-in source fails closed while its native packet engine is unavailable; it does not capture or relay traffic in that state."
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

private fun routingModeLabel(mode: AppRoutingMode): String = when (mode) {
    AppRoutingMode.ALL_APPS -> "All apps"
    AppRoutingMode.ONLY_SELECTED_APPS -> "Only selected"
    AppRoutingMode.EXCLUDE_SELECTED_APPS -> "Exclude selected"
}
