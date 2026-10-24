package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.example.netrix.model.SignalRatingBand
import com.example.netrix.ui.NavigationTab
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.arm.ArmScreen
import com.example.netrix.ui.components.StatusIndicatorDot
import com.example.netrix.ui.components.TerminalButton
import com.example.netrix.ui.diagnostics.DiagnosticsScreen
import com.example.netrix.ui.metrics.MetricsScreen
import com.example.netrix.ui.tools.ToolsScreen
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixSecondary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixTheme
import com.example.ui.theme.NetrixVoid
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: NetrixViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
            setContent {
                NetrixTheme {
                    MainTerminalApp(viewModel = viewModel)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "Error during MainActivity startup: ${t.message}", t)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.telemetryEngine.proximityBeepService.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.telemetryEngine.proximityBeepService.onResume()
    }

    override fun onStop() {
        super.onStop()
        viewModel.telemetryEngine.proximityBeepService.release()
    }
}

@Composable
fun MainTerminalApp(viewModel: NetrixViewModel) {
    val context = LocalContext.current
    val currentTab by viewModel.currentTab.collectAsState()
    val isDrawerOpen by viewModel.isDrawerOpen.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val link by viewModel.telemetryEngine.currentLink.collectAsState()

    // Requirement 0: Check for last_crash.txt on startup
    var crashLogText by remember {
        mutableStateOf(
            File(context.filesDir, "last_crash.txt").let { file ->
                if (file.exists()) {
                    try { file.readText() } catch (_: Throwable) { null }
                } else null
            }
        )
    }

    BackHandler(enabled = isDrawerOpen || currentTab != NavigationTab.ARM_HOME) {
        if (isDrawerOpen) {
            viewModel.toggleDrawer()
        } else {
            viewModel.selectTab(NavigationTab.ARM_HOME)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(NetrixVoid)
            .statusBarsPadding()
            .imePadding(),
        containerColor = NetrixVoid
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Terminal Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(NetrixSurface)
                        .border(1.dp, NetrixBorder)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TerminalButton(
                            text = if (isDrawerOpen) "[X]" else "[=]",
                            onClick = { viewModel.toggleDrawer() },
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "NETRIX@android:~#",
                            color = NetrixPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = currentTab.tag,
                            color = NetrixGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusIndicatorDot(isActive = link.isConnected)
                        val bandColor = SignalRatingBand.colorForRssi(link.rssi)
                        Text(
                            text = link.rssi?.let { "$it dBm" } ?: (link.unavailableReason ?: "OFFLINE"),
                            color = if (link.isConnected) bandColor else NetrixTextDim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Main Content View Switcher
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (currentTab) {
                        NavigationTab.ARM_HOME -> ArmScreen(viewModel = viewModel)
                        NavigationTab.METRICS -> MetricsScreen(viewModel = viewModel)
                        NavigationTab.DIAGNOSTICS -> DiagnosticsScreen(viewModel = viewModel)
                        NavigationTab.TOOLS -> ToolsScreen(viewModel = viewModel)
                    }
                }
            }

            // Dark Scrim when Navigation Drawer is Open
            AnimatedVisibility(
                visible = isDrawerOpen,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable { viewModel.toggleDrawer() }
                )
            }

            // Slide-out Retractable Navigation Drawer
            AnimatedVisibility(
                visible = isDrawerOpen,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
                modifier = Modifier.fillMaxHeight()
            ) {
                TerminalNavigationDrawer(
                    currentTab = currentTab,
                    onSelectTab = { viewModel.selectTab(it) },
                    onCloseDrawer = { viewModel.toggleDrawer() },
                    linkConnected = link.isConnected,
                    samplesCount = samples.size
                )
            }
        }
    }

    // Requirement 0: Crash Logger Dialog on Startup if last_crash.txt exists
    if (crashLogText != null) {
        val stackTrace = crashLogText ?: ""
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "[!] UNCAUGHT CRASH REPORT",
                    color = NetrixRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "A crash occurred during previous execution. The stack trace was captured to last_crash.txt:",
                        color = NetrixTextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(NetrixSurface)
                            .border(1.dp, NetrixBorder)
                            .padding(6.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = stackTrace,
                            color = NetrixText,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TerminalButton(
                    text = "[COPY CRASH LOG]",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("CRASH_LOG", stackTrace))
                        Toast.makeText(context, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            },
            dismissButton = {
                TerminalButton(
                    text = "[DISMISS]",
                    onClick = {
                        try {
                            File(context.filesDir, "last_crash.txt").delete()
                        } catch (_: Throwable) {}
                        crashLogText = null
                    }
                )
            },
            containerColor = NetrixVoid,
            modifier = Modifier.border(1.dp, NetrixBorder)
        )
    }
}

@Composable
fun TerminalNavigationDrawer(
    currentTab: NavigationTab,
    onSelectTab: (NavigationTab) -> Unit,
    onCloseDrawer: () -> Unit,
    linkConnected: Boolean,
    samplesCount: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(NetrixVoid)
            .border(1.dp, NetrixBorder)
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header with explicit close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NETRIX v1.0",
                        color = NetrixPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Terminal Intelligence",
                        color = NetrixTextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                TerminalButton(
                    text = "[X]",
                    onClick = onCloseDrawer,
                    modifier = Modifier.size(30.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(NetrixBorder)
            )

            // Primary Navigation Destinations
            Text(
                text = "MAIN DESTINATIONS:",
                color = NetrixTextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

            NavigationTab.entries.forEach { tab ->
                val isSelected = currentTab == tab
                TerminalButton(
                    text = tab.label,
                    isSelected = isSelected,
                    onClick = { onSelectTab(tab) },
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(NetrixBorder)
            )

            // Live Engine Status
            Text(
                text = "ENGINE STATUS:",
                color = NetrixTextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicatorDot(isActive = true)
                Text(" Scanner: Active (2 Hz)", color = NetrixText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicatorDot(isActive = linkConnected)
                Text(if (linkConnected) " Wi-Fi: Connected" else " Wi-Fi: Disconnected", color = NetrixText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                text = " Survey Points: $samplesCount",
                color = NetrixText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "netrix:~$",
                color = NetrixPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
