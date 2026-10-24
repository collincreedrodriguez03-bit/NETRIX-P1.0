package com.example.netrix.ui.arm

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.data.local.SpatialSampleEntity
import com.example.netrix.model.DiscoveredAccessPoint
import com.example.netrix.model.SignalRatingBand
import com.example.netrix.model.WifiLinkSnapshot
import com.example.netrix.service.audio.ProximityBeepService
import com.example.netrix.ui.components.TerminalButton
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow

@Composable
fun ApTelemetryBottomSheet(
    bssid: String,
    samples: List<SpatialSampleEntity>,
    discoveredAps: List<DiscoveredAccessPoint>,
    currentLink: WifiLinkSnapshot,
    beepService: ProximityBeepService,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val apInfo = remember(bssid, discoveredAps, currentLink) {
        discoveredAps.find { it.bssid.equals(bssid, ignoreCase = true) }
    }

    val isConnectedAp = currentLink.isConnected && currentLink.bssid?.equals(bssid, ignoreCase = true) == true
    val ssid = if (isConnectedAp) currentLink.ssid ?: apInfo?.ssid else apInfo?.ssid ?: "Unknown SSID"
    val liveRawRssi = if (isConnectedAp) currentLink.rssi else apInfo?.rssi
    val bandRating = SignalRatingBand.fromRssi(liveRawRssi)

    val beepEnabled by beepService.isEnabled.collectAsState()
    val beepTargetBssid by beepService.targetBssid.collectAsState()
    val isTargetBeep = beepEnabled && beepTargetBssid?.equals(bssid, ignoreCase = true) == true

    // Gather 60-second historical RSSI telemetry for this BSSID
    val now = System.currentTimeMillis()
    val windowStart = now - 60_000L
    val recentSamples = remember(samples, bssid, now) {
        samples.filter { s ->
            s.bssid?.equals(bssid, ignoreCase = true) == true &&
                s.timestamp >= windowStart &&
                s.rssi != null
        }.sortedBy { it.timestamp }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(NetrixVoid)
            .border(1.dp, NetrixBorder)
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "AP TELEMETRY: ",
                            color = NetrixPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = ssid ?: "[Redacted / Hidden]",
                            color = NetrixText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "BSSID: $bssid ${if (isConnectedAp) "[CONNECTED]" else ""}",
                        color = if (isConnectedAp) NetrixGreen else NetrixTextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                TerminalButton(
                    text = "[X]",
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(NetrixBorder)
            )

            // RF Parameters Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("LIVE RSSI (RAW):", color = NetrixTextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = liveRawRssi?.let { "$it dBm" } ?: "N/A",
                            color = bandRating?.color ?: NetrixText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        bandRating?.let {
                            Box(
                                modifier = Modifier
                                    .background(it.color.copy(alpha = 0.2f))
                                    .border(1.dp, it.color)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(it.label, color = it.color, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("BAND / CHANNEL:", color = NetrixTextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = "${apInfo?.band ?: currentLink.band ?: "N/A"} (Ch ${apInfo?.channel ?: currentLink.channel ?: "?"})",
                        color = NetrixText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "WIDTH: ${apInfo?.channelWidthMhz ?: currentLink.channelWidthMhz ?: 20} MHz",
                        color = NetrixTextDim,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("SECURITY:", color = NetrixTextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = apInfo?.capabilities ?: "N/A",
                        color = NetrixText,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("READING AGE:", color = NetrixTextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = if (isConnectedAp) "live (500ms)" else "age ${apInfo?.ageSeconds ?: 0}s",
                        color = if (isConnectedAp) NetrixGreen else NetrixYellow,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // 60-Second RSSI Line Graph
            Column {
                Text(
                    text = "60-SECOND RSSI TELEMETRY GRAPH:",
                    color = NetrixTextDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .background(NetrixSurface)
                        .border(1.dp, NetrixBorder)
                ) {
                    if (recentSamples.size < 2) {
                        Box(modifier = Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "[Awaiting survey data points for this AP (${recentSamples.size} recorded)]",
                                color = NetrixTextDim,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Canvas(modifier = Modifier.fillMaxWidth().height(90.dp)) {
                            val w = size.width
                            val h = size.height

                            // Horizontal reference gridlines: -40, -60, -80, -100 dBm
                            val dbmLines = listOf(-40, -60, -80, -100)
                            dbmLines.forEach { dbm ->
                                val normY = (1f - (dbm + 105f) / 75f).coerceIn(0f, 1f) * h
                                drawLine(
                                    color = Color(0xFF2A1F45),
                                    start = Offset(0f, normY),
                                    end = Offset(w, normY),
                                    strokeWidth = 1f
                                )
                            }

                            // Plot RSSI line
                            val path = Path()
                            recentSamples.forEachIndexed { idx, s ->
                                val normX = ((s.timestamp - windowStart).toFloat() / 60_000f).coerceIn(0f, 1f) * w
                                val rssiVal = s.rssi?.toFloat() ?: -95f
                                val normY = (1f - (rssiVal + 105f) / 75f).coerceIn(0f, 1f) * h

                                if (idx == 0) path.moveTo(normX, normY) else path.lineTo(normX, normY)

                                // Dot
                                drawCircle(
                                    color = SignalRatingBand.colorForRssiFloat(rssiVal),
                                    radius = 2.5f,
                                    center = Offset(normX, normY)
                                )
                            }

                            drawPath(
                                path = path,
                                color = SignalRatingBand.colorForRssi(liveRawRssi),
                                style = Stroke(width = 1.8f)
                            )

                            // Labels on native canvas
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = Paint().apply {
                                    color = android.graphics.Color.parseColor("#776699")
                                    textSize = 18f
                                    typeface = android.graphics.Typeface.MONOSPACE
                                }
                                drawText("-40 dBm", 4f, 18f, paint)
                                drawText("-80 dBm", 4f, h * 0.70f, paint)
                                drawText("-60s", 4f, h - 4f, paint)
                                drawText("now", w - 36f, h - 4f, paint)
                            }
                        }
                    }
                }
            }

            // Proximity Beep Target Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TerminalButton(
                    text = if (isTargetBeep) "[✓] BEEPING TO THIS AP" else "[ ] BEEP TO THIS AP",
                    isSelected = isTargetBeep,
                    onClick = {
                        if (isTargetBeep) {
                            beepService.toggleBeep(false)
                        } else {
                            beepService.setTarget(bssid, ssid, isConnectedAp)
                            beepService.toggleBeep(true)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    color = if (isTargetBeep) NetrixGreen else NetrixPrimary
                )

                TerminalButton(
                    text = "CLOSE",
                    onClick = onDismiss,
                    modifier = Modifier.width(70.dp)
                )
            }
        }
    }
}
