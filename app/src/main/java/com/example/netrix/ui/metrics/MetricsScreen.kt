package com.example.netrix.ui.metrics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.model.DiscoveredAccessPoint
import com.example.netrix.model.MetricRecord
import com.example.netrix.model.MetricStatus
import com.example.netrix.model.SignalRatingBand
import com.example.netrix.model.VitalMetric
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.components.TerminalBadge
import com.example.netrix.ui.components.TerminalButton
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixSecondary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixSurfaceSubtle
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow

enum class MetricsViewMode {
    VITALS_TABLE,
    ACCESS_POINTS_LIST
}

@Composable
fun MetricsScreen(
    viewModel: NetrixViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vitalsMap by viewModel.telemetryEngine.vitals.collectAsState()
    val discoveredAps by viewModel.telemetryEngine.discoveredAps.collectAsState()
    val isScanThrottled by viewModel.telemetryEngine.isScanThrottled.collectAsState()
    val scanStatusMessage by viewModel.telemetryEngine.scanStatusMessage.collectAsState()
    val wifiState by viewModel.telemetryEngine.wifiState.collectAsState()

    var viewMode by remember { mutableStateOf(MetricsViewMode.VITALS_TABLE) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<MetricStatus?>(null) }
    var inspectedRecord by remember { mutableStateOf<MetricRecord?>(null) }
    var inspectedAp by remember { mutableStateOf<DiscoveredAccessPoint?>(null) }

    val allRecords = remember(vitalsMap, wifiState) {
        VitalMetric.entries.map { vital ->
            val record = vitalsMap[vital]
            if (vital == VitalMetric.RSSI && wifiState.rssi != null) {
                val smoothedStr = wifiState.smoothedRssi?.let { String.format("%.1f", it) }
                MetricRecord(
                    vital = vital,
                    valueString = "${wifiState.rssi} dBm (raw)" + (if (smoothedStr != null) " | smooth: $smoothedStr" else ""),
                    numericValue = wifiState.rssi?.toDouble(),
                    status = MetricStatus.AVAILABLE,
                    sourceApi = "WifiInfo.rssi (500ms 2Hz loop)"
                )
            } else if (vital == VitalMetric.SSID) {
                record ?: MetricRecord(
                    vital = vital,
                    valueString = wifiState.ssid ?: (wifiState.redactedReason ?: "hidden by Android"),
                    status = if (wifiState.ssid != null) MetricStatus.AVAILABLE else MetricStatus.PERMISSION_REQUIRED
                )
            } else if (vital == VitalMetric.BSSID) {
                record ?: MetricRecord(
                    vital = vital,
                    valueString = wifiState.bssid ?: (wifiState.redactedReason ?: "hidden by Android"),
                    status = if (wifiState.bssid != null) MetricStatus.AVAILABLE else MetricStatus.PERMISSION_REQUIRED
                )
            } else {
                record ?: MetricRecord(
                    vital = vital,
                    valueString = MetricStatus.NOT_TESTED.label,
                    status = MetricStatus.NOT_TESTED
                )
            }
        }
    }

    val filteredRecords = remember(allRecords, searchQuery, selectedFilter) {
        allRecords.filter { record ->
            val matchesQuery = searchQuery.isBlank() ||
                record.vital.displayName.contains(searchQuery, ignoreCase = true) ||
                record.vital.code.contains(searchQuery, ignoreCase = true) ||
                record.valueString.contains(searchQuery, ignoreCase = true)
            val matchesFilter = selectedFilter == null || record.status == selectedFilter
            matchesQuery && matchesFilter
        }
    }

    val filteredAps = remember(discoveredAps, searchQuery) {
        if (searchQuery.isBlank()) discoveredAps else {
            discoveredAps.filter { ap ->
                ap.ssid.contains(searchQuery, ignoreCase = true) ||
                ap.bssid.contains(searchQuery, ignoreCase = true) ||
                ap.band.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .padding(6.dp)
    ) {
        // 1. Top Mode Selector & Export
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TerminalButton(
                    text = "VITALS (27)",
                    isSelected = viewMode == MetricsViewMode.VITALS_TABLE,
                    onClick = { viewMode = MetricsViewMode.VITALS_TABLE },
                    modifier = Modifier.height(32.dp)
                )
                TerminalButton(
                    text = "AP LIST (${discoveredAps.size})",
                    isSelected = viewMode == MetricsViewMode.ACCESS_POINTS_LIST,
                    onClick = { viewMode = MetricsViewMode.ACCESS_POINTS_LIST },
                    modifier = Modifier.height(32.dp)
                )
            }

            TerminalButton(
                text = "EXPORT CSV",
                onClick = {
                    val csv = viewModel.exportAllObservationsCsv()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NETRIX_CSV", csv))
                    Toast.makeText(context, "CSV copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.height(32.dp),
                color = NetrixSecondary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Search Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        if (viewMode == MetricsViewMode.VITALS_TABLE) "Filter 27 vitals (e.g. RSSI, DNS)..."
                        else "Search APs (SSID, BSSID, Band)...",
                        color = NetrixTextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NetrixSurface,
                    unfocusedContainerColor = NetrixSurface,
                    focusedTextColor = NetrixText,
                    unfocusedTextColor = NetrixText,
                    focusedIndicatorColor = NetrixPrimary,
                    unfocusedIndicatorColor = NetrixBorder
                ),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .border(1.dp, NetrixBorder)
            )

            if (searchQuery.isNotEmpty()) {
                TerminalButton(
                    text = "CLR",
                    onClick = { searchQuery = "" },
                    modifier = Modifier.height(42.dp).width(42.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (viewMode == MetricsViewMode.VITALS_TABLE) {
            // Filter Pills for Vitals
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TerminalButton(
                    text = "ALL (27)",
                    isSelected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    modifier = Modifier.height(28.dp)
                )
                TerminalButton(
                    text = "AVAILABLE",
                    isSelected = selectedFilter == MetricStatus.AVAILABLE,
                    onClick = { selectedFilter = MetricStatus.AVAILABLE },
                    modifier = Modifier.height(28.dp),
                    color = NetrixGreen
                )
                TerminalButton(
                    text = "UNAVAILABLE",
                    isSelected = selectedFilter == MetricStatus.UNAVAILABLE,
                    onClick = { selectedFilter = MetricStatus.UNAVAILABLE },
                    modifier = Modifier.height(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Vitals Table
            val hScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, NetrixBorder)
                    .background(NetrixSurface)
            ) {
                Column(modifier = Modifier.horizontalScroll(hScrollState)) {
                    Row(
                        modifier = Modifier
                            .background(NetrixSurfaceSubtle)
                            .border(1.dp, NetrixBorder)
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("#", modifier = Modifier.width(32.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("VITAL", modifier = Modifier.width(86.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("NAME", modifier = Modifier.width(170.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("VALUE (RAW/REAL)", modifier = Modifier.width(200.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("STATUS", modifier = Modifier.width(105.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("SOURCE / REASON", modifier = Modifier.width(240.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filteredRecords) { index, record ->
                            val rowBg = if (index % 2 == 0) NetrixSurface else NetrixSurfaceSubtle

                            Row(
                                modifier = Modifier
                                    .background(rowBg)
                                    .clickable { inspectedRecord = record }
                                    .border(0.5.dp, NetrixBorder.copy(alpha = 0.4f))
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = record.vital.id.toString().padStart(2, '0'),
                                    modifier = Modifier.width(32.dp),
                                    color = NetrixTextDim,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = record.vital.code,
                                    modifier = Modifier.width(86.dp),
                                    color = NetrixPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = record.vital.displayName,
                                    modifier = Modifier.width(170.dp),
                                    color = NetrixText,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = record.valueString,
                                    modifier = Modifier.width(200.dp),
                                    color = if (record.status == MetricStatus.AVAILABLE) NetrixGreen else NetrixYellow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Box(modifier = Modifier.width(105.dp)) {
                                    TerminalBadge(status = record.status)
                                }
                                Text(
                                    text = record.sourceApi,
                                    modifier = Modifier.width(240.dp),
                                    color = NetrixTextDim,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Discovered Access Points List
            if (isScanThrottled || scanStatusMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NetrixSurface)
                        .border(1.dp, NetrixYellow)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[!] ${scanStatusMessage ?: "Scan throttled by Android OS"}",
                            color = NetrixYellow,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        TerminalButton(
                            text = "SCAN",
                            onClick = { viewModel.telemetryEngine.triggerApScan() },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            val apScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, NetrixBorder)
                    .background(NetrixSurface)
            ) {
                Column(modifier = Modifier.horizontalScroll(apScrollState)) {
                    Row(
                        modifier = Modifier
                            .background(NetrixSurfaceSubtle)
                            .border(1.dp, NetrixBorder)
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SSID", modifier = Modifier.width(170.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("BSSID", modifier = Modifier.width(140.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("LEVEL (RAW)", modifier = Modifier.width(100.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("BAND / CH", modifier = Modifier.width(90.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("WIDTH", modifier = Modifier.width(70.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("AGE", modifier = Modifier.width(75.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("SECURITY", modifier = Modifier.width(180.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    if (filteredAps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No Access Points discovered yet.\nGrant ACCESS_FINE_LOCATION and enable Location to populate.",
                                color = NetrixTextDim,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(filteredAps) { index, ap ->
                                val rowBg = if (index % 2 == 0) NetrixSurface else NetrixSurfaceSubtle
                                val bandColor = SignalRatingBand.colorForRssi(ap.rssi)

                                Row(
                                    modifier = Modifier
                                        .background(rowBg)
                                        .clickable { inspectedAp = ap }
                                        .border(0.5.dp, NetrixBorder.copy(alpha = 0.4f))
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ap.ssid + if (ap.isConnected) " [CONNECTED]" else "",
                                        modifier = Modifier.width(170.dp),
                                        color = if (ap.isConnected) NetrixGreen else NetrixText,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = ap.bssid,
                                        modifier = Modifier.width(140.dp),
                                        color = NetrixTextDim,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "${ap.rssi} dBm",
                                        modifier = Modifier.width(100.dp),
                                        color = bandColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "${ap.band} (${ap.channel ?: "?"})",
                                        modifier = Modifier.width(90.dp),
                                        color = NetrixText,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "${ap.channelWidthMhz} MHz",
                                        modifier = Modifier.width(70.dp),
                                        color = NetrixTextDim,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "age ${ap.ageSeconds}s",
                                        modifier = Modifier.width(75.dp),
                                        color = NetrixYellow,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = ap.capabilities,
                                        modifier = Modifier.width(180.dp),
                                        color = NetrixTextDim,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Inspector Dialog for Metric
    inspectedRecord?.let { r ->
        AlertDialog(
            onDismissRequest = { inspectedRecord = null },
            title = {
                Text(
                    text = "[${r.vital.code}] ${r.vital.displayName}",
                    color = NetrixPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Value: ${r.valueString}", color = NetrixGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Status: ${r.status.label}", color = NetrixText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("Source: ${r.sourceApi}", color = NetrixTextDim, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                    Text("Method: ${r.calculationMethod}", color = NetrixTextDim, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TerminalButton(text = "[CLOSE]", onClick = { inspectedRecord = null })
            },
            containerColor = NetrixVoid,
            modifier = Modifier.border(1.dp, NetrixBorder)
        )
    }

    // Detail Inspector Dialog for AP
    inspectedAp?.let { ap ->
        AlertDialog(
            onDismissRequest = { inspectedAp = null },
            title = {
                Text(
                    text = ap.ssid,
                    color = NetrixPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("BSSID: ${ap.bssid}", color = NetrixText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("Raw RSSI: ${ap.rssi} dBm (${SignalRatingBand.fromRssi(ap.rssi)?.label})", color = SignalRatingBand.colorForRssi(ap.rssi), fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Frequency: ${ap.frequencyMhz} MHz (${ap.band}, Channel ${ap.channel})", color = NetrixText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("Width: ${ap.channelWidthMhz} MHz", color = NetrixText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("Reading Age: ${ap.ageSeconds} seconds ago", color = NetrixYellow, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("Security: ${ap.capabilities}", color = NetrixTextDim, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TerminalButton(
                    text = "[BEEP TO THIS AP]",
                    onClick = {
                        viewModel.telemetryEngine.proximityBeepService.setTarget(ap.bssid, ap.ssid, ap.isConnected)
                        viewModel.telemetryEngine.proximityBeepService.toggleBeep(true)
                        inspectedAp = null
                        Toast.makeText(context, "Proximity beep tracking ${ap.ssid}", Toast.LENGTH_SHORT).show()
                    }
                )
            },
            dismissButton = {
                TerminalButton(text = "[CLOSE]", onClick = { inspectedAp = null })
            },
            containerColor = NetrixVoid,
            modifier = Modifier.border(1.dp, NetrixBorder)
        )
    }
}
