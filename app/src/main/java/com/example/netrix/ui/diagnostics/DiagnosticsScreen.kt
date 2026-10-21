package com.example.netrix.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.components.TerminalButton
import com.example.netrix.ui.components.TerminalCard
import com.example.netrix.ui.components.TerminalProgressBar
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixSurfaceSubtle
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow

@Composable
fun DiagnosticsScreen(
    viewModel: NetrixViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val consoleLogs by viewModel.diagnosticConsoleLogs.collectAsState()
    val isTesting by viewModel.isTestingActive.collectAsState()
    val recentProbes by viewModel.recentProbeResults.collectAsState()
    val listState = rememberLazyListState()

    var customTarget by remember { mutableStateOf("dns.google") }

    LaunchedEffect(consoleLogs.size) {
        if (consoleLogs.isNotEmpty()) {
            listState.animateScrollToItem(consoleLogs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .padding(6.dp)
    ) {
        // 1. Top Header & Run Control
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "NETWORK DIAGNOSTICS",
                    color = NetrixPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Real Sockets & Protocol Probes",
                    color = NetrixTextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            TerminalButton(
                text = if (isTesting) "[TESTING...]" else "[>] RUN FULL SUITE",
                onClick = { viewModel.runFullDiagnostics() },
                enabled = !isTesting,
                modifier = Modifier.height(34.dp),
                color = if (isTesting) NetrixYellow else NetrixGreen,
                textColor = if (isTesting) NetrixYellow else NetrixGreen
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        TerminalProgressBar(isLoading = isTesting)
        Spacer(modifier = Modifier.height(4.dp))

        // 2. Target Configuration & Individual Probe Shortcuts
        TerminalCard(title = "Diagnostic Target & Probes") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextField(
                        value = customTarget,
                        onValueChange = { customTarget = it },
                        label = { Text("Target Host / IP", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = NetrixSurfaceSubtle,
                            unfocusedContainerColor = NetrixSurfaceSubtle,
                            focusedTextColor = NetrixText,
                            unfocusedTextColor = NetrixText,
                            focusedIndicatorColor = NetrixPrimary,
                            unfocusedIndicatorColor = NetrixBorder
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .border(1.dp, NetrixBorder)
                    )

                    TerminalButton(
                        text = "TEST",
                        onClick = { viewModel.runFullDiagnostics() },
                        enabled = !isTesting,
                        modifier = Modifier.height(48.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Probe History: ${recentProbes.size}", color = NetrixTextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("Zero Fake Data Enforced", color = NetrixGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 3. Terminal Console Output Window Header & Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOG STREAM (pts/0):",
                color = NetrixTextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TerminalButton(
                    text = "COPY",
                    onClick = {
                        val text = consoleLogs.joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("DIAG_LOGS", text))
                        Toast.makeText(context, "Terminal logs copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.height(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, NetrixBorder)
                .background(NetrixSurface)
                .padding(8.dp)
        ) {
            if (consoleLogs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "NETRIX DIAGNOSTIC TERMINAL READY",
                        color = NetrixPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Press '[>] RUN FULL SUITE' above to execute real network probes.",
                        color = NetrixTextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(consoleLogs) { line ->
                        val lineColor = when {
                            line.contains("[SUCCESS]") || line.contains("AVAILABLE") -> NetrixGreen
                            line.contains("failed") || line.contains("ERROR") -> NetrixYellow
                            line.contains("===") -> NetrixPrimary
                            else -> NetrixText
                        }
                        Text(
                            text = line,
                            color = lineColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}
