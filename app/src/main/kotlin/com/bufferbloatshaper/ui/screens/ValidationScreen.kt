package com.bufferbloatshaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.model.VpnRuntimeStateStore
import com.bufferbloatshaper.model.VpnRuntimeStatus
import com.bufferbloatshaper.ui.theme.Error
import com.bufferbloatshaper.ui.theme.GlassBackground
import com.bufferbloatshaper.ui.theme.GlassBorder
import com.bufferbloatshaper.ui.theme.OnBackground
import com.bufferbloatshaper.ui.theme.OnSurface
import com.bufferbloatshaper.ui.theme.OnSurfaceDim
import com.bufferbloatshaper.ui.theme.Success

/**
 * Honest validation gate. A before/after score is deliberately unavailable
 * until the native engine can verify both shaper state and load measurements.
 */
@Composable
fun ValidationScreen(modifier: Modifier = Modifier) {
    val runtime by VpnRuntimeStateStore.state.collectAsState()
    val engineReady = runtime.nativeEngineAvailable
    val shapingOn = runtime.status == VpnRuntimeStatus.RUNNING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Validation", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OnBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "A result is shown only when it can prove the shaper state, independent upload/download load, and measured outcomes.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        ValidationGate(
            title = "Verified native engine",
            ready = engineReady,
            detail = if (engineReady) "Native capability reported ready." else "The packaged engine is an unavailable stub, so no VPN route or test traffic is created."
        )
        Spacer(modifier = Modifier.height(12.dp))
        ValidationGate(
            title = "State confirmation",
            ready = engineReady,
            detail = if (shapingOn) "A future shaped run can record engine generation ${runtime.generation}." else "A future baseline run must explicitly record that shaping is off."
        )
        Spacer(modifier = Modifier.height(12.dp))
        ValidationGate(
            title = "Independent two-way load",
            ready = false,
            detail = "Not implemented. A credible test needs independent upload and download load, not a small cached download or a guessed upload rate."
        )
        Spacer(modifier = Modifier.height(12.dp))
        ValidationGate(
            title = "Measured comparison",
            ready = false,
            detail = "Not implemented. The future flow must persist local throughput, queue-delay/latency samples, duration, failures, and repeatable off/on comparisons."
        )

        Spacer(modifier = Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GlassBackground)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("Release-test method", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OnSurface)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "1. Record the exact network, endpoint, app version, and shaper-off state.\n" +
                    "2. Generate independent upload and download load.\n" +
                    "3. Measure idle and loaded latency with the same stated method.\n" +
                    "4. Repeat with the verified shaper on.\n" +
                    "5. Keep local, redacted evidence only; publish a comparison only after repeatable results.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ValidationGate(title: String, ready: Boolean, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBackground)
            .border(1.dp, if (ready) Success.copy(alpha = 0.45f) else GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (ready) Icons.Default.CheckCircleOutline else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (ready) Success else Error
        )
        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        }
    }
}
