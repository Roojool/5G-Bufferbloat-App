package com.bufferbloatshaper.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bufferbloatshaper.ui.theme.*
import com.bufferbloatshaper.validation.BufferbloatTest
import com.bufferbloatshaper.validation.TestResult
import kotlinx.coroutines.launch

/**
 * Validation screen — built-in bufferbloat test (§6).
 *
 * "Bake a simple idle-vs-loaded latency test directly into the app.
 * Run it automatically before and after enabling shaping, and show
 * the before/after numbers side by side."
 */
@Composable
fun ValidationScreen(
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val test = remember { BufferbloatTest() }

    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var progressPercent by remember { mutableIntStateOf(0) }

    var beforeResult by remember { mutableStateOf<TestResult?>(null) }
    var afterResult by remember { mutableStateOf<TestResult?>(null) }
    var latestResult by remember { mutableStateOf<TestResult?>(null) }

    // Wire up progress callback
    LaunchedEffect(Unit) {
        test.onProgress = { phase, percent ->
            progress = phase
            progressPercent = percent
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Bufferbloat Test",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Measures idle vs loaded latency to detect bufferbloat",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Run Test buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    isRunning = true
                    coroutineScope.launch {
                        val result = test.runTest(shapingActive = false)
                        result?.let {
                            beforeResult = it
                            latestResult = it
                        }
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceElevated,
                    contentColor = OnSurface,
                    disabledContainerColor = Surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.SpeedOutlined, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Before")
            }

            Button(
                onClick = {
                    isRunning = true
                    coroutineScope.launch {
                        val result = test.runTest(shapingActive = true)
                        result?.let {
                            afterResult = it
                            latestResult = it
                        }
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = PrimaryVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test After")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress indicator
        AnimatedVisibility(visible = isRunning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = GlassBorder
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Before/After comparison
        if (beforeResult != null || afterResult != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                beforeResult?.let { result ->
                    TestResultCard(
                        label = "Before Shaping",
                        result = result,
                        modifier = Modifier.weight(1f)
                    )
                }
                afterResult?.let { result ->
                    TestResultCard(
                        label = "After Shaping",
                        result = result,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Latest result detail
        latestResult?.let { result ->
            ResultDetailCard(result)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Info card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GlassBackground)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "How This Works",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. Measure idle latency (ping under no load)\n" +
                        "2. Saturate your connection (download at full speed)\n" +
                        "3. Measure loaded latency (ping while downloading)\n" +
                        "4. Compare: the difference is your bufferbloat\n\n" +
                        "Run \"Test Before\" with shaping off, then enable shaping and " +
                        "run \"Test After\" to see the improvement.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun TestResultCard(
    label: String,
    result: TestResult,
    modifier: Modifier = Modifier
) {
    val gradeColor = when (result.grade) {
        TestResult.Grade.A -> GradeA
        TestResult.Grade.B -> GradeB
        TestResult.Grade.C -> GradeC
        TestResult.Grade.D -> GradeD
        TestResult.Grade.F -> GradeF
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBackground)
            .border(1.dp, gradeColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Big grade letter
        Text(
            text = result.grade.label,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = gradeColor
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "+${result.addedLatencyMs.toInt()}ms",
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface
        )

        Text(
            text = result.grade.description,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ResultDetailCard(result: TestResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBackground)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Latest Test Details",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = OnSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        DetailRow("Idle Latency", "${result.idleLatencyMs.toInt()}ms", Success)
        DetailRow("Loaded Latency", "${result.loadedLatencyMs.toInt()}ms",
            if (result.addedLatencyMs > 30) Error else Warning)
        DetailRow("Added Latency", "+${result.addedLatencyMs.toInt()}ms",
            when (result.grade) {
                TestResult.Grade.A, TestResult.Grade.B -> Success
                TestResult.Grade.C -> Warning
                else -> Error
            }
        )
        DetailRow("Shaping Active", if (result.shapingActive) "Yes" else "No",
            if (result.shapingActive) Primary else OnSurfaceDim)
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = valueColor, fontWeight = FontWeight.Medium)
    }
}
