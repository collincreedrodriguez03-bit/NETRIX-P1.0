package com.example.netrix.ui.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import com.example.netrix.model.SignalRatingBand
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.components.StatusIndicatorDot
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
fun ToolsScreen(
    viewModel: NetrixViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allSessions by viewModel.allSessions.collectAsState()
    val observations by viewModel.recentObservations.collectAsState()
    val isLocationEnabled by viewModel.telemetryEngine.isLocationEnabled.collectAsState()
    val currentLink by viewModel.telemetryEngine.currentLink.collectAsState()
    val discoveredAps by viewModel.telemetryEngine.discoveredAps.collectAsState()

    val beepService = viewModel.telemetryEngine.proximityBeepService
    val isBeepEnabled by beepService.isEnabled.collectAsState()
    val beepTargetBssid by beepService.targetBssid.collectAsState()
    val beepTargetSsid by beepService.targetSsid.collectAsState()
    val isTargetConnected by beepService.isTargetConnected.collectAsState()
    val beepStrength by beepService.currentStrength.collectAsState()
    val beepRssi by beepService.currentRssi.collectAsState()

    var showHistoryDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val grantedCount = permissions.values.count { it }
        Toast.makeText(context, "$grantedCount permissions updated", Toast.LENGTH_SHORT).show()
        viewModel.telemetryEngine.triggerApScan()
    }

    val permissionsList = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION to "Precise Location (Wi-Fi SSID/BSSID)",
        Manifest.permission.CAMERA to "Camera (Live AR/CAD Viewfinder)",
        Manifest.permission.ACTIVITY_RECOGNITION to "Activity Sensors (Step Tracking)"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "SYSTEM TOOLS & DIAGNOSTICS",
            color = NetrixPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        // 1. Requirement 5: Proximity Beep Controller (Toggle in Tools, default off)
        TerminalCard(title = "Proximity Beep Generator (880 Hz PCM)") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "STATUS: ${if (isBeepEnabled) "ACTIVE (BEEPING)" else "OFF"}",
                            color = if (isBeepEnabled) NetrixGreen else NetrixTextDim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        val targetLabel = beepTargetSsid ?: beepTargetBssid ?: currentLink.ssid ?: "Strongest AP"
                        Text(
                            text = "Target AP: $targetLabel",
                            color = NetrixPrimary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    TerminalButton(
                        text = if (isBeepEnabled) "[BEEP: ON]" else "[BEEP: OFF]",
                        isSelected = isBeepEnabled,
                        onClick = {
                            if (!isBeepEnabled && beepTargetBssid == null && currentLink.bssid != null) {
                                beepService.setTarget(currentLink.bssid, currentLink.ssid, isConnected = true)
                            }
                            beepService.toggleBeep(!isBeepEnabled)
                        },
                        modifier = Modifier.width(110.dp).height(34.dp),
                        color = if (isBeepEnabled) NetrixGreen else NetrixSecondary
                    )
                }

                if (!isTargetConnected && beepTargetBssid != null) {
                    Text(
                        text = "[!] Following non-connected AP: updating slowly (scan cadence)",
                        color = NetrixYellow,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Live Audio Synthesis Telemetry
                val calculatedDurationMs = (40f + (600f - 40f) * beepStrength).toLong()
                val calculatedVolumePct = ((0.1f + (1.0f - 0.1f) * beepStrength) * 100).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Strength: ${(beepStrength * 100).toInt()}% (RSSI ${beepRssi ?: currentLink.rssi ?: "N/A"} dBm)",
                        color = NetrixText,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Pulse: ${calculatedDurationMs}ms | Vol: $calculatedVolumePct%",
                        color = NetrixTextDim,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Visual Strength Ramp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(NetrixSurface)
                        .border(1.dp, NetrixBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(beepStrength.coerceIn(0.01f, 1f))
                            .fillMaxSize()
                            .background(SignalRatingBand.colorForRssi(beepRssi ?: currentLink.rssi))
                    )
                }

                Text(
                    text = "Acoustic formula: 880 Hz sine, EMA alpha 0.3, 5ms envelope ramp, ~300ms gap.",
                    color = NetrixTextDim,
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 2. Android Hardware & Location Gating
        TerminalCard(title = "Hardware Permissions & Location Gating") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                permissionsList.forEach { (perm, desc) ->
                    val isGranted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicatorDot(isActive = isGranted)
                            Spacer(modifier = Modifier.padding(3.dp))
                            Text(desc, color = NetrixText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = if (isGranted) "GRANTED" else "DENIED",
                            color = if (isGranted) NetrixGreen else NetrixRed,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicatorDot(isActive = isLocationEnabled)
                        Spacer(modifier = Modifier.padding(3.dp))
                        Text("Location Services (Settings)", color = NetrixText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        text = if (isLocationEnabled) "ENABLED" else "DISABLED",
                        color = if (isLocationEnabled) NetrixGreen else NetrixRed,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (!isLocationEnabled) {
                    Spacer(modifier = Modifier.height(2.dp))
                    TerminalButton(
                        text = "[!] OPEN LOCATION SETTINGS",
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            } catch (_: Throwable) {}
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = NetrixYellow,
                        textColor = NetrixYellow
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TerminalButton(
                        text = "[REQUEST PERMISSIONS]",
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.ACTIVITY_RECOGNITION
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TerminalButton(
                        text = "[TRIGGER SCAN]",
                        onClick = {
                            viewModel.telemetryEngine.triggerApScan()
                        },
                        modifier = Modifier.weight(1f),
                        color = NetrixSecondary
                    )
                }
            }
        }

        // 3. Requirement 4: RF Rating Bands Constant Reference Table
        TerminalCard(title = "Signal Rating Bands (Constant Table)") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SignalRatingBand.entries.forEach { band ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(band.color))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(band.label, color = band.color, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        val rangeText = when (band) {
                            SignalRatingBand.EXCELLENT -> ">= -40 dBm (Teal/Green)"
                            SignalRatingBand.VERY_GOOD -> "-41 .. -50 dBm"
                            SignalRatingBand.GOOD -> "-51 .. -60 dBm"
                            SignalRatingBand.FAIR -> "-61 .. -70 dBm"
                            SignalRatingBand.POOR -> "-71 .. -80 dBm"
                            SignalRatingBand.UNUSABLE -> "< -80 dBm (Red)"
                        }
                        Text(rangeText, color = NetrixTextDim, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // 4. Local Database & Survey History
        TerminalCard(title = "Local Storage (Room SQLite)") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Survey Sessions: ${allSessions.size}", color = NetrixText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Persisted Observations: ${observations.size}", color = NetrixText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Discovered APs in Cache: ${discoveredAps.size}", color = NetrixTextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TerminalButton(
                        text = "VIEW SESSIONS (${allSessions.size})",
                        onClick = { showHistoryDialog = true },
                        modifier = Modifier.weight(1f),
                        color = NetrixSecondary
                    )
                    TerminalButton(
                        text = "EXPORT CSV",
                        onClick = {
                            val csv = viewModel.exportAllObservationsCsv()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("OBS_CSV", csv))
                            Toast.makeText(context, "Observations CSV copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 5. About & Architecture
        TerminalCard(title = "About NETRIX") {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("NETRIX v1.0 — Network Intelligence Terminal", color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Live sampling: 2 Hz direct connected-AP RSSI polling", color = NetrixText, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                Text("Truthfulness Policy: Zero fake/simulated data anywhere.", color = NetrixTextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    if (showHistoryDialog) {
        SurveyHistoryDialog(
            viewModel = viewModel,
            onDismiss = { showHistoryDialog = false }
        )
    }
}
