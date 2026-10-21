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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.model.MetricRecord
import com.example.netrix.model.MetricStatus
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

@Composable
fun MetricsScreen(
    viewModel: NetrixViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vitalsMap by viewModel.telemetryEngine.vitals.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<MetricStatus?>(null) }
    var inspectedRecord by remember { mutableStateOf<MetricRecord?>(null) }

    val allRecords = remember(vitalsMap) {
        VitalMetric.entries.map { vital ->
            vitalsMap[vital] ?: MetricRecord(
                vital = vital,
                valueString = MetricStatus.UNAVAILABLE.label,
                status = MetricStatus.UNAVAILABLE
            )
        }
    }

    val filteredRecords = remember(allRecords, searchQuery, selectedFilter) {
        allRecords.filter { record ->
            val matchesQuery = searchQuery.isBlank() ||
                record.vital.displayName.contains(searchQuery, ignoreCase = true) ||
                record.vital.code.contains(searchQuery, ignoreCase = true)
            val matchesFilter = selectedFilter == null || record.status == selectedFilter
            matchesQuery && matchesFilter
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .padding(6.dp)
    ) {
        // 1. Top Header & Action Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "NETWORK METRICS (Live)",
                    color = NetrixPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Showing ${filteredRecords.size} of 27 vitals",
                    color = NetrixTextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            TerminalButton(
                text = "EXPORT CSV",
                onClick = {
                    val csv = viewModel.exportAllObservationsCsv()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NETRIX_CSV", csv))
                    Toast.makeText(context, "CSV copied to clipboard (${csv.lines().size} rows)", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.height(30.dp),
                color = NetrixSecondary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Search Bar with Clear Affordance
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text("Search 27 vitals (e.g. RSSI, DNS, JITTER)...", color = NetrixTextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
                    .height(44.dp)
                    .border(1.dp, NetrixBorder)
            )

            if (searchQuery.isNotEmpty()) {
                TerminalButton(
                    text = "CLR",
                    onClick = { searchQuery = "" },
                    modifier = Modifier.height(44.dp).width(44.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Status Filter Buttons
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
            TerminalButton(
                text = "PERM REQ",
                isSelected = selectedFilter == MetricStatus.PERMISSION_REQUIRED,
                onClick = { selectedFilter = MetricStatus.PERMISSION_REQUIRED },
                modifier = Modifier.height(28.dp)
            )
            TerminalButton(
                text = "ERROR",
                isSelected = selectedFilter == MetricStatus.ERROR,
                onClick = { selectedFilter = MetricStatus.ERROR },
                modifier = Modifier.height(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Spreadsheet-First Horizontally & Vertically Scrollable Grid
        val hScrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, NetrixBorder)
                .background(NetrixSurface)
        ) {
            Column(modifier = Modifier.horizontalScroll(hScrollState)) {
                // Table Header Row
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
                    Text("VALUE", modifier = Modifier.width(140.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("STATUS", modifier = Modifier.width(105.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("METHOD / SOURCE", modifier = Modifier.width(220.dp), color = NetrixPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }

                // Table Data Rows with Alternating Row Contrast
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
                                modifier = Modifier.width(140.dp),
                                color = if (record.status == MetricStatus.AVAILABLE) NetrixGreen else NetrixTextDim,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Box(modifier = Modifier.width(105.dp)) {
                                TerminalBadge(status = record.status)
                            }
                            Text(
                                text = record.calculationMethod,
                                modifier = Modifier.width(220.dp),
                                color = NetrixTextDim,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    // 3. Metric Record Detail Inspector Dialog
    inspectedRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { inspectedRecord = null },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TerminalButton(
                        text = "COPY",
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("VITAL", "${record.vital.displayName}: ${record.valueString}"))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.height(34.dp)
                    )
                    TerminalButton(
                        text = "CLOSE",
                        onClick = { inspectedRecord = null },
                        modifier = Modifier.height(34.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "${record.vital.code} — ${record.vital.displayName}",
                    color = NetrixPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Vital ID: #${record.vital.id}", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Current Value: ${record.valueString}", color = NetrixGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("Status: ${record.status.name}", color = NetrixPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Unit: ${record.vital.defaultUnit.ifBlank { "N/A" }}", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Source API: ${record.sourceApi}", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Calculation / Method: ${record.calculationMethod}", color = NetrixText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Timestamp: ${record.timestamp}", color = NetrixTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    if (record.status == MetricStatus.UNAVAILABLE) {
                        Text(
                            text = "\n[TRUTHFULNESS]: No simulated default values are shown. Requires explicit device or OS API exposure.",
                            color = NetrixTextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            containerColor = NetrixSurface,
            shape = RectangleShape
        )
    }
}
