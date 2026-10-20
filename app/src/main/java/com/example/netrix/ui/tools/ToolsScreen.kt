package com.example.netrix.ui.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.components.StatusIndicatorDot
import com.example.netrix.ui.components.TerminalButton
import com.example.netrix.ui.components.TerminalCard
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid

@Composable
fun ToolsScreen(
    viewModel: NetrixViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allSessions by viewModel.allSessions.collectAsState()
    val observations by viewModel.recentObservations.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val grantedCount = permissions.values.count { it }
        Toast.makeText(context, "$grantedCount permissions updated", Toast.LENGTH_SHORT).show()
    }

    val permissionsList = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION to "Location (Wi-Fi SSID/BSSID)",
        Manifest.permission.CAMERA to "Camera (Live AR HUD)",
        Manifest.permission.ACTIVITY_RECOGNITION to "Activity Sensors (Step Tracking)"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "NETRIX SYSTEM TOOLS & SETTINGS",
            color = NetrixPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        // 1. Android Permission Status
        TerminalCard(title = "Hardware & Platform Permissions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            Text(desc, color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = if (isGranted) "GRANTED" else "DENIED",
                            color = if (isGranted) NetrixGreen else NetrixRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                TerminalButton(
                    text = "[REQUEST ALL PERMISSIONS]",
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 2. Local Database & Survey History
        TerminalCard(title = "Local Storage (Room SQLite)") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stored Survey Sessions: ${allSessions.size}", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Persisted Observations: ${observations.size}", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Database Engine: Room 2.7.0 Local-First", color = NetrixTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TerminalButton(
                        text = "EXPORT SURVEY CSV",
                        onClick = {
                            val csv = viewModel.exportCurrentSurveyCsv()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SURVEY_CSV", csv))
                            Toast.makeText(context, "Survey CSV copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TerminalButton(
                        text = "EXPORT OBSERVATIONS",
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

        // 3. Survey Calibration & Thresholds
        TerminalCard(title = "Survey & RF Thresholds") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Dead-Zone RSSI Threshold: < -80 dBm (Standard)", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Floor Plan Scale: 40.0 px/m (Default 5m calibration)", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Heatmap Engine: Inverse Distance Weighting (IDW)", color = NetrixTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Extrapolation Policy: Unmeasured cells remain masked", color = NetrixGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // 4. About & Policy Compliance
        TerminalCard(title = "About NETRIX") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("NETRIX v1.0 — Network Intelligence Terminal", color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Architecture: 2 Core Engines (ARM + Scanner)", color = NetrixText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Truthfulness Policy: Zero fake data, real measurements only.", color = NetrixTextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
