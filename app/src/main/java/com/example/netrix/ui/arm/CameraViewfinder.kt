package com.example.netrix.ui.arm

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.netrix.model.WifiLinkSnapshot
import com.example.netrix.ui.components.StatusIndicatorDot
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid

@Composable
fun CameraViewfinder(
    link: WifiLinkSnapshot,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    Box(
        modifier = modifier
            .border(1.dp, NetrixBorder, RectangleShape)
            .background(NetrixVoid)
    ) {
        if (hasCameraPermission) {
            val previewView = remember { PreviewView(context) }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            DisposableEffect(lifecycleOwner) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(context))

                onDispose {
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProvider.unbindAll()
                    } catch (_: Exception) {}
                }
            }
        } else {
            // Permission fallback: Terminal wireframe preview
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NetrixSurface),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "[CAMERA PERMISSION REQUIRED]",
                        color = NetrixPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enable camera in Tools to activate live AR feed",
                        color = NetrixTextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Terminal AR HUD Overlay (Crosshair & Telemetry Readout)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            // Top HUD Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicatorDot(isActive = hasCameraPermission, color = if (hasCameraPermission) NetrixGreen else NetrixBorder)
                Text(
                    text = " CAMERA (Live)",
                    color = NetrixText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = " — 1920x1080 30FPS",
                    color = NetrixTextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Live Telemetry Mini-Overlay
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 22.dp)
                    .background(NetrixVoid.copy(alpha = 0.75f))
                    .border(1.dp, NetrixBorder.copy(alpha = 0.8f))
                    .padding(6.dp)
            ) {
                Text(
                    text = "RSSI: ${link.rssi?.let { "$it dBm" } ?: "UNAVAILABLE"}",
                    color = if (link.rssi != null && link.rssi >= -70) NetrixGreen else NetrixPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "FREQ: ${link.frequencyMhz?.let { "$it MHz" } ?: "N/A"}",
                    color = NetrixText,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "AP: ${link.ssid ?: "SCANNING..."}",
                    color = NetrixTextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Center AR Crosshair
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center)
                    .border(1.dp, NetrixPrimary.copy(alpha = 0.6f))
            )
        }
    }
}
