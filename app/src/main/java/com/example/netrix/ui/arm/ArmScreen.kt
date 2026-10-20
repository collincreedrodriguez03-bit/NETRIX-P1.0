package com.example.netrix.ui.arm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.model.HeatmapLayer
import com.example.netrix.model.VitalMetric
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.components.TerminalButton
import com.example.netrix.ui.components.TerminalCard
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixSecondary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow

@Composable
fun ArmScreen(
    viewModel: NetrixViewModel,
    modifier: Modifier = Modifier
) {
    val activeSession by viewModel.activeSession.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val currentLayer by viewModel.selectedLayer.collectAsState()
    val isRunning by viewModel.surveyRunning.collectAsState()
    val elapsedSec by viewModel.surveyElapsedSec.collectAsState()
    val currentPos by viewModel.currentPosition.collectAsState()
    val currentLink by viewModel.telemetryEngine.currentLink.collectAsState()
    val vitals by viewModel.telemetryEngine.vitals.collectAsState()

    var showCameraView by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .padding(6.dp)
    ) {
        // 1. Live Survey Status & Telemetry HUD Ribbon
        TerminalCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AP: ${currentLink.ssid ?: "SCANNING..."}",
                        color = NetrixPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "BSSID: ${currentLink.bssid ?: "UNAVAILABLE"}",
                        color = NetrixTextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "RSSI: ${currentLink.rssi?.let { "$it dBm" } ?: "N/A"}",
                            color = if (currentLink.rssi != null && currentLink.rssi!! >= -70) NetrixGreen else NetrixYellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "PING: ${vitals[VitalMetric.LATENCY]?.valueString ?: "N/A"}",
                            color = NetrixText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "SAMPLES: ${samples.size}",
                            color = NetrixPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        val mm = (elapsedSec / 60).toString().padStart(2, '0')
                        val ss = (elapsedSec % 60).toString().padStart(2, '0')
                        Text(
                            text = "TIME: $mm:$ss",
                            color = if (isRunning) NetrixGreen else NetrixTextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. View Toggle Bar (Floor Plan vs Camera HUD)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TerminalButton(
                    text = "FLOOR PLAN",
                    isSelected = !showCameraView,
                    onClick = { showCameraView = false },
                    modifier = Modifier.height(34.dp)
                )
                TerminalButton(
                    text = "CAMERA (AR)",
                    isSelected = showCameraView,
                    onClick = { showCameraView = true },
                    modifier = Modifier.height(34.dp)
                )
            }

            Text(
                text = if (isRunning) "[●] RUNNING" else "[||] PAUSED",
                color = if (isRunning) NetrixGreen else NetrixYellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3. Main Spatial Content Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, NetrixBorder)
        ) {
            if (showCameraView) {
                CameraViewfinder(
                    link = currentLink,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FloorPlanCanvas(
                    samples = samples,
                    currentLayer = currentLayer,
                    currentPos = currentPos,
                    scalePixelsPerMeter = activeSession?.scalePixelsPerMeter ?: 40.0f,
                    onTapLocation = { x, y ->
                        if (isRunning) {
                            viewModel.addCheckpoint(x, y)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 4. Heatmap Layer Selection Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HeatmapLayer.entries.forEach { layer ->
                TerminalButton(
                    text = layer.displayName,
                    isSelected = currentLayer == layer,
                    onClick = { viewModel.setLayer(layer) },
                    modifier = Modifier.height(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 5. Bottom Survey Controller Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (activeSession == null) {
                TerminalButton(
                    text = "[>] START SURVEY",
                    onClick = { viewModel.startNewSurvey() },
                    modifier = Modifier.weight(1f),
                    color = NetrixGreen,
                    textColor = NetrixGreen
                )
            } else {
                if (isRunning) {
                    TerminalButton(
                        text = "[||] PAUSE",
                        onClick = { viewModel.pauseSurvey() },
                        modifier = Modifier.weight(1f),
                        color = NetrixYellow,
                        textColor = NetrixYellow
                    )
                } else {
                    TerminalButton(
                        text = "[>] RESUME",
                        onClick = { viewModel.resumeSurvey() },
                        modifier = Modifier.weight(1f),
                        color = NetrixGreen,
                        textColor = NetrixGreen
                    )
                }

                TerminalButton(
                    text = "[★] PIN CHECKPOINT",
                    onClick = {
                        currentPos?.let { (x, y) -> viewModel.addCheckpoint(x, y) }
                    },
                    modifier = Modifier.weight(1f),
                    color = NetrixPrimary,
                    textColor = NetrixPrimary
                )

                TerminalButton(
                    text = "[■] FINALIZE",
                    onClick = { viewModel.finalizeSurvey() },
                    modifier = Modifier.weight(1f),
                    color = NetrixRed,
                    textColor = NetrixRed
                )
            }
        }
    }
}
