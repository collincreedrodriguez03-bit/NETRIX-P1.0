package com.example.netrix.ui.arm

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.model.HeatmapLayer
import com.example.netrix.model.SignalRatingBand
import com.example.netrix.model.VitalMetric
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.components.TerminalButton
import com.example.netrix.ui.components.TerminalCard
import com.example.netrix.ui.history.SurveyHistoryDialog
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
    val context = LocalContext.current
    val activeSession by viewModel.activeSession.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val currentLayer by viewModel.selectedLayer.collectAsState()
    val isRunning by viewModel.surveyRunning.collectAsState()
    val elapsedSec by viewModel.surveyElapsedSec.collectAsState()
    val currentPos by viewModel.currentPosition.collectAsState()
    val wifiState by viewModel.telemetryEngine.wifiState.collectAsState()
    val discoveredAps by viewModel.telemetryEngine.discoveredAps.collectAsState()
    val vitals by viewModel.telemetryEngine.vitals.collectAsState()

    val beepService = viewModel.telemetryEngine.proximityBeepService
    val isBeepEnabled by beepService.isEnabled.collectAsState()
    val beepStrength by beepService.currentStrength.collectAsState()
    val beepTargetSsid by beepService.targetSsid.collectAsState()
    val beepTargetBssid by beepService.targetBssid.collectAsState()
    val beepRssi by beepService.currentRssi.collectAsState()

    var showCameraView by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var customFloorPlanBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedApBssid by remember { mutableStateOf<String?>(null) }
    var surveyPreconditionError by remember { mutableStateOf<String?>(null) }

    val floorPlanPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        customFloorPlanBitmap = bmp
                        Toast.makeText(context, "Floor plan loaded successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load floor plan: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .padding(6.dp)
    ) {
        // 1. Live Survey Status & Telemetry HUD Ribbon (Requirement E: HUD RSSI/FREQ from WifiState)
        TerminalCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val ssidDisplay = if (wifiState.connected) {
                        wifiState.ssid ?: "hidden by Android"
                    } else {
                        "DISCONNECTED"
                    }
                    val bssidDisplay = if (wifiState.connected) {
                        wifiState.bssid ?: "hidden by Android"
                    } else {
                        "UNAVAILABLE"
                    }
                    Text(
                        text = "AP: $ssidDisplay",
                        color = NetrixPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Text(
                        text = "BSSID: $bssidDisplay",
                        color = NetrixTextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        val rssiColor = SignalRatingBand.colorForRssi(wifiState.rssi)
                        Text(
                            text = "RSSI: ${wifiState.rssi?.let { "$it dBm" } ?: "N/A"}",
                            color = rssiColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "FREQ: ${wifiState.freqMhz?.let { "$it MHz" } ?: "N/A"}",
                            color = NetrixText,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "PTS: ${samples.size}",
                            color = NetrixPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        val mm = (elapsedSec / 60).toString().padStart(2, '0')
                        val ss = (elapsedSec % 60).toString().padStart(2, '0')
                        Text(
                            text = "T: $mm:$ss",
                            color = if (isRunning) NetrixGreen else NetrixTextDim,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Proximity Beep Controller with pulsing radar HUD
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NetrixSurface)
                .border(1.dp, if (isBeepEnabled) NetrixGreen else NetrixBorder)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "arm_beep_pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 400, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "arm_pulse"
                )
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(
                            if (isBeepEnabled) NetrixGreen.copy(alpha = pulseAlpha) else NetrixTextDim
                        )
                )
                val targetName = beepTargetSsid ?: beepTargetBssid ?: (wifiState.ssid ?: "Connected AP")
                Text(
                    text = if (isBeepEnabled) "BEEP ACTIVE: $targetName (${beepRssi ?: wifiState.rssi ?: "?"} dBm)" else "PROXIMITY BEEP: STANDBY",
                    color = if (isBeepEnabled) NetrixGreen else NetrixTextDim,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            TerminalButton(
                text = if (isBeepEnabled) "[STOP BEEP]" else "[START BEEP]",
                isSelected = isBeepEnabled,
                onClick = {
                    if (!isBeepEnabled && beepTargetBssid == null && wifiState.bssid != null) {
                        beepService.setTarget(wifiState.bssid, wifiState.ssid, isConnected = true)
                    }
                    beepService.toggleBeep(!isBeepEnabled)
                },
                modifier = Modifier.height(26.dp),
                color = if (isBeepEnabled) NetrixGreen else NetrixSecondary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. View Toggle Bar & Action Bar (Floor Plan vs Camera HUD + Plan Import + History)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TerminalButton(
                    text = "FLOOR PLAN",
                    isSelected = !showCameraView,
                    onClick = { showCameraView = false },
                    modifier = Modifier.height(32.dp)
                )
                TerminalButton(
                    text = "CAMERA HUD",
                    isSelected = showCameraView,
                    onClick = { showCameraView = true },
                    modifier = Modifier.height(32.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TerminalButton(
                    text = "LOAD PLAN",
                    onClick = { floorPlanPicker.launch("image/*") },
                    modifier = Modifier.height(32.dp),
                    color = NetrixSecondary
                )
                TerminalButton(
                    text = "HISTORY",
                    onClick = { showHistoryDialog = true },
                    modifier = Modifier.height(32.dp),
                    color = NetrixSecondary
                )
            }
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
                    link = wifiState,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FloorPlanCanvas(
                    samples = samples,
                    currentLayer = currentLayer,
                    currentPos = currentPos,
                    scalePixelsPerMeter = activeSession?.scalePixelsPerMeter ?: 0.0f,
                    customFloorPlanBitmap = customFloorPlanBitmap,
                    onTapLocation = { x, y ->
                        if (isRunning) {
                            viewModel.addCheckpoint(x, y)
                        }
                    },
                    onTapApMarker = { tappedBssid ->
                        selectedApBssid = tappedBssid
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Position Status Indicator Banner
                if (isRunning) {
                    val pos = currentPos
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(NetrixSurface.copy(alpha = 0.88f))
                            .border(1.dp, if (pos == null) NetrixYellow else NetrixGreen)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (pos == null) {
                                "[!] POSITION UNCERTAIN: TAP CANVAS TO PLACE CHECKPOINT"
                            } else {
                                "[✓] LAST CHECKPOINT: (${pos.first.toInt()}, ${pos.second.toInt()})"
                            },
                            color = if (pos == null) NetrixYellow else NetrixGreen,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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
                    modifier = Modifier.height(30.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 5. Bottom Survey Controller Controls (Requirement 1: Verify preconditions safely before starting)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (activeSession == null) {
                TerminalButton(
                    text = "[>] START SURVEY",
                    onClick = {
                        val (ready, reason) = viewModel.verifySurveyPreconditions(context)
                        if (!ready) {
                            surveyPreconditionError = reason
                        } else {
                            viewModel.startNewSurvey()
                        }
                    },
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
                        onClick = {
                            val (ready, reason) = viewModel.verifySurveyPreconditions(context)
                            if (!ready) {
                                surveyPreconditionError = reason
                            } else {
                                viewModel.resumeSurvey()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        color = NetrixGreen,
                        textColor = NetrixGreen
                    )
                }

                TerminalButton(
                    text = "[★] PIN POINT",
                    onClick = {
                        val pos = currentPos
                        if (pos != null) {
                            viewModel.addCheckpoint(pos.first, pos.second)
                        } else {
                            Toast.makeText(context, "Tap on floor plan canvas to place checkpoint", Toast.LENGTH_SHORT).show()
                        }
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

    // Survey Preconditions Failure Alert Dialog
    if (surveyPreconditionError != null) {
        val errorText = surveyPreconditionError ?: ""
        val isLocationOff = errorText.contains("Location", ignoreCase = true) && errorText.contains("OFF", ignoreCase = true)

        AlertDialog(
            onDismissRequest = { surveyPreconditionError = null },
            title = {
                Text(
                    text = "[!] SURVEY INITIALIZATION BLOCKED",
                    color = NetrixRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = errorText,
                        color = NetrixText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "All spatial and RF prerequisites must be verified prior to starting an active survey.",
                        color = NetrixTextDim,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                if (isLocationOff) {
                    TerminalButton(
                        text = "[OPEN LOCATION SETTINGS]",
                        onClick = {
                            surveyPreconditionError = null
                            try {
                                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            } catch (_: Throwable) {}
                        }
                    )
                } else {
                    TerminalButton(
                        text = "[OK]",
                        onClick = { surveyPreconditionError = null }
                    )
                }
            },
            dismissButton = {
                if (isLocationOff) {
                    TerminalButton(
                        text = "[DISMISS]",
                        onClick = { surveyPreconditionError = null }
                    )
                }
            },
            containerColor = NetrixVoid,
            modifier = Modifier.border(1.dp, NetrixBorder)
        )
    }

    // Requirement 6: Map AP Pings Telemetry Bottom Sheet
    selectedApBssid?.let { bssid ->
        ApTelemetryBottomSheet(
            bssid = bssid,
            samples = samples,
            discoveredAps = discoveredAps,
            currentLink = wifiState,
            beepService = viewModel.telemetryEngine.proximityBeepService,
            onDismiss = { selectedApBssid = null }
        )
    }

    if (showHistoryDialog) {
        SurveyHistoryDialog(
            viewModel = viewModel,
            onDismiss = { showHistoryDialog = false }
        )
    }
}
