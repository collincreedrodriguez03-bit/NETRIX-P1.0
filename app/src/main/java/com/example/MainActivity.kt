package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.ui.theme.NetrixSecondary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixTheme
import com.example.ui.theme.NetrixVoid

class MainActivity : ComponentActivity() {

    private val viewModel: NetrixViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetrixTheme {
                MainTerminalApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainTerminalApp(viewModel: NetrixViewModel) {
    val currentTab by viewModel.currentTab.collectAsState()
    val isDrawerOpen by viewModel.isDrawerOpen.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val link by viewModel.telemetryEngine.currentLink.collectAsState()

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
            .statusBarsPadding(),
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
                        .height(44.dp)
                        .background(NetrixSurface)
                        .border(1.dp, NetrixBorder)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TerminalButton(
                            text = if (isDrawerOpen) "[X]" else "[=]",
                            onClick = { viewModel.toggleDrawer() },
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = "NETRIX@android:~#",
                            color = NetrixPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = currentTab.tag,
                            color = NetrixGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusIndicatorDot(isActive = link.isConnected)
                        Text(
                            text = link.rssi?.let { "$it dBm" } ?: "OFFLINE",
                            color = if (link.isConnected) NetrixGreen else NetrixTextDim,
                            fontSize = 11.sp,
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
                    linkConnected = link.isConnected,
                    samplesCount = samples.size
                )
            }
        }
    }
}

@Composable
fun TerminalNavigationDrawer(
    currentTab: NavigationTab,
    onSelectTab: (NavigationTab) -> Unit,
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
            // Header
            Text(
                text = "NETRIX v1.0",
                color = NetrixPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Network Intelligence Terminal",
                color = NetrixTextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

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
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
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
                Text(" Scanner: Active", color = NetrixText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicatorDot(isActive = linkConnected)
                Text(if (linkConnected) " Wi-Fi: Connected" else " Wi-Fi: Disconnected", color = NetrixText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                text = " Samples: $samplesCount",
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
